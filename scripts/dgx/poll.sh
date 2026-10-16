#!/usr/bin/env bash
# PAA GPU queue poller on the DGX: the ONLY way PAA jobs start, and the off-campus control channel.
# Same design as LocalAI's scripts/cloud/dgx/queue_poll.sh, as a separate lane (~/paa, tmux "paa-queue"):
# the DGX has no inbound access, so everything goes through GitHub, outbound only.
#   - reads queue.yaml (branch gpu-queue) every PAA_QUEUE_INTERVAL s
#   - `jobs:`    starts each new job id once (whitelisted steps only, code pinned to a commit on main)
#   - `control:` status: <any new value> -> status post · logs: [id] -> log tail · stop: [id] -> stop it
#   - posts launches, finishes, crash tails, eval results and a 6-hourly heartbeat to the control issue
#   - backs up artifacts + queue state to a private Hugging Face dataset, restores them when missing
# Changing these scripts needs campus SSH (deliberately: no remote code updates of the tools themselves).
#
# Env (optional): PAA_HOME (~/paa)  PAA_CONTROL_SLUG (repo with the gpu-queue branch + issue)  PAA_CODE_SLUG (repo
#   jobs' code comes from)  PAA_QUEUE_REF (gpu-queue)  PAA_QUEUE_INTERVAL (180)  PAA_ISSUE (empty = don't post)
#   PAA_HEARTBEAT (21600)  PAA_MAX_GPU_JOBS (2)  PAA_LEAVE_FREE (2)  PAA_HF_REPO  PAA_BACKUP_EVERY (1800)
set -uo pipefail
shopt -s nullglob   # empty jobs/ or artifacts/ must loop zero times, not over a literal "*"
ROOT="${PAA_HOME:-$HOME/paa}"
[ -f "$ROOT/secrets.env" ] && { set -a; . "$ROOT/secrets.env"; set +a; }
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$(cd "$HERE/../.." && pwd)"
CSLUG="${PAA_CONTROL_SLUG:-ShreyashPoddar/PersonalAssistantAgent}"
CODE="${PAA_CODE_SLUG:-ShreyashPoddar/PersonalAssistantAgent}"
QREF="${PAA_QUEUE_REF:-gpu-queue}"
INTERVAL="${PAA_QUEUE_INTERVAL:-180}"
ISSUE="${PAA_ISSUE-}"
HEARTBEAT="${PAA_HEARTBEAT:-21600}"
MAXGPU="${PAA_MAX_GPU_JOBS:-2}"   # up to 2 PAA cards, only while PAA_LEAVE_FREE stay idle for LocalAI
ART="$ROOT/artifacts"; mkdir -p "$ART" "$ROOT/jobs"
STATE="$ROOT/queue_state"; touch "$STATE"      # job ids already launched (or rejected)
POSTED="$ROOT/queue_posted"; touch "$POSTED"   # job ids whose finish was announced
SEEN="$ROOT/control_seen.json"
MIRROR="$ROOT/PAA.git"
[ -x "$ROOT/venv/bin/python" ] && . "$ROOT/venv/bin/activate"
export HF_HOME="$ROOT/hf_cache"
: "${GITHUB_TOKEN:=}"
TOOLS_SHA=$(git -C "$TOOLS" rev-parse --short HEAD 2>/dev/null || echo "?")

api() { curl -sS -m 30 -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }

post() {  # $1 = markdown; no-op (stdout) without issue/token
  [ -n "$ISSUE" ] && [ -n "$GITHUB_TOKEN" ] || { echo "[local] $1"; return; }
  api -X POST "https://api.github.com/repos/$CSLUG/issues/$ISSUE/comments" \
      -d "$(python3 -c 'import json,sys; print(json.dumps({"body": sys.argv[1][:60000]}))' "$1")" >/dev/null || echo "post failed"
}

log_tail() {  # $1 = job id -> last 30 meaningful lines, token-like strings redacted
  grep -vE 'it/s\]|%\|' "$ROOT/jobs/$1/run.log" 2>/dev/null | tail -30 | sed -E 's/(hf_|ghp_|github_pat_)[A-Za-z0-9_]+/***/g'
}

running_ids() {
  local d
  for d in "$ROOT"/jobs/*/; do
    [ -f "$d/run.sh" ] && ! grep -qs '^exit=' "$d/run.log" && basename "$d"
  done
}

status_snapshot() {
  local gpus run="" q id
  gpus=$(nvidia-smi --query-gpu=index,memory.used,utilization.gpu --format=csv,noheader 2>/dev/null \
         | awk -F', ' '{printf "gpu%s %s %s · ", $1, $2, $3}')
  for id in $(running_ids); do
    run="$run- \`$id\` $(grep -E '^(chunk|\{.loss|train |eval )' "$ROOT/jobs/$id/run.log" 2>/dev/null | tail -1 | cut -c1-160)"$'\n'
  done
  q=$(cut -d$'\x1f' -f1 "$ROOT/queue.parsed" 2>/dev/null | grep -vxF -f "$STATE" | tr '\n' ' ')
  post "[PAA DGX] **status** (tools \`$TOOLS_SHA\`, $(date '+%F %H:%M'))"$'\n'"GPUs: $gpus"$'\n\n'"Running:"$'\n'"${run:-none}"$'\n'"Queued/waiting: ${q:-none}"$'\n'"Artifacts: $(ls "$ART" 2>/dev/null | tr '\n' ' ')"$'\n'"Last backup: $(grep -E 'backed up' "$ROOT/backup.log" 2>/dev/null | tail -1)"
}

stop_job() {
  if running_ids | grep -qxF "$1"; then
    tmux kill-session -t "paa-$1" 2>/dev/null
    echo "exit=stopped" >> "$ROOT/jobs/$1/run.log"
    post "[PAA DGX] stopped \`$1\` (control.stop)."
  else post "[PAA DGX] control.stop: no running job \`$1\`."; fi
}

sync_mirror() {
  [ -d "$MIRROR" ] || git init -q --bare "$MIRROR"
  [ -n "$GITHUB_TOKEN" ] || return 1
  # time limit: on 2026-10-13 a network drop left this fetch hanging for 3 days with the poller frozen behind it
  timeout 300 git --git-dir="$MIRROR" -c credential.helper= fetch -q \
      "https://x-access-token:${GITHUB_TOKEN}@github.com/$CODE.git" "+refs/heads/main:refs/heads/main" 2>/dev/null
}
on_main() { git --git-dir="$MIRROR" merge-base --is-ancestor "$1" refs/heads/main 2>/dev/null; }

BKPID="$ROOT/backup.pid"; BKEVERY="${PAA_BACKUP_EVERY:-1800}"
backup_busy() { [ -f "$BKPID" ] && kill -0 "$(cat "$BKPID")" 2>/dev/null; }
bg_backup() { ( timeout 3600 python "$HERE/hf_backup.py" "$@" >> "$ROOT/backup.log" 2>&1 ) & echo $! > "$BKPID"; }

backups() {  # one upload at a time: finished outputs first, then state, then small files of long-running jobs
  backup_busy && return
  local d n now; now=$(date +%s)
  for d in "$ART"/*/; do
    n=$(basename "$d")
    [ -f "$d/.done" ] && [ ! -f "$d/.backed_up" ] && { bg_backup done "$ART" "$n"; return; }
  done
  if [ -f "$ROOT/state_dirty" ]; then rm -f "$ROOT/state_dirty"; bg_backup state "$ROOT"; return; fi
  for d in "$ART"/*/; do
    [ -f "$d/.done" ] && continue
    [ $(( now - $(cat "$d/.backup_at" 2>/dev/null || stat -c %Y "$d" 2>/dev/null || echo "$now") )) -ge "$BKEVERY" ] || continue
    date +%s > "$d/.backup_at"; bg_backup partial "$ART" "$(basename "$d")"; return
  done
}

token_days() {
  local e
  e=$(api -I "https://api.github.com/repos/$CSLUG" 2>/dev/null | tr -d '\r' | sed -n 's/^github-authentication-token-expiration: //Ip' | head -1)
  [ -n "$e" ] && echo $(( ( $(date -d "$e" +%s) - $(date +%s) ) / 86400 ))
}
check_token() {
  local today days; today=$(date +%F)
  [ "$(cat "$ROOT/token_checked" 2>/dev/null)" = "$today" ] && return
  echo "$today" > "$ROOT/token_checked"
  days=$(token_days)
  [ -n "$days" ] && [ "$days" -le 14 ] && post "[PAA DGX] ⚠ **GitHub token expires in $days days.** Replacing it needs a campus visit (~/paa/secrets.env)."
}

echo "paa poller up: tools $TOOLS_SHA, $CSLUG@$QREF every ${INTERVAL}s, code $CODE, issue=${ISSUE:-none}"
# announce a start at most every 6 h (a crash loop restarted by cron must not flood the issue)
if [ $(( $(date +%s) - $(cat "$ROOT/last_start_post" 2>/dev/null || echo 0) )) -ge 21600 ]; then
  date +%s > "$ROOT/last_start_post"
  post "[PAA DGX] poller started (tools \`$TOOLS_SHA\`, polling \`$QREF/queue.yaml\` every ${INTERVAL}s)."
fi
last_hb=$(date +%s)

while true; do
  q=$([ -n "$GITHUB_TOKEN" ] && api -H "Accept: application/vnd.github.raw" "https://api.github.com/repos/$CSLUG/contents/queue.yaml?ref=$QREF")
  if [ -n "$q" ] && printf '%s' "$q" | grep -q '^jobs:'; then
    printf '%s' "$q" > "$ROOT/queue.current.yaml"
    python "$HERE/parse_queue.py" "$ROOT/queue.current.yaml" "$SEEN" "$ROOT/queue.parsed" > "$ROOT/queue.actions" 2> "$ROOT/queue.parse_errors" || true
    while IFS=$'\t' read -r act arg; do
      case "$act" in
        STATUS) status_snapshot ;;
        LOGS)   if [ -f "$ROOT/jobs/$arg/run.log" ]; then post "[PAA DGX] log tail \`$arg\`:"$'\n```\n'"$(log_tail "$arg")"$'\n```'
                else post "[PAA DGX] control.logs: no job \`$arg\`."; fi ;;
        STOP)   stop_job "$arg" ;;
        BAD)    grep -qxF "bad:$arg" "$STATE" || { echo "bad:$arg" >> "$STATE"; post "[PAA DGX] ignored a queue entry: $arg"; } ;;
      esac
    done < "$ROOT/queue.actions"

    sync_mirror || echo "$(date +%T) mirror sync failed"
    gpu_jobs=$(running_ids | while read -r i; do grep -q 'CUDA_VISIBLE_DEVICES=[0-9]' "$ROOT/jobs/$i/run.sh" && echo "$i"; done | grep -c . || true)
    while IFS=$'\x1f' read -r id step ref out after gpus resume args; do
      [ -n "$id" ] || continue
      grep -qxF "$id" "$STATE" && continue
      if ! on_main "$ref"; then
        echo "$id" >> "$STATE"; touch "$ROOT/state_dirty"
        post "[PAA DGX] rejected \`$id\`: ref \`$ref\` is not a commit on main of $CODE. Re-queue with a new id and a main SHA."
        continue
      fi
      # inputs missing here but backed up (box wiped, or produced elsewhere)? fetch them first
      # only when the folder is absent here: restoring over a job that is still writing it would roll its output back
      if [ -n "$after" ] && [ ! -d "$ART/$after" ]; then
        timeout 900 python "$HERE/hf_backup.py" restore "$ART" "$after" >> "$ROOT/backup.log" 2>&1 \
          && [ -f "$ART/$after/.done" ] && post "[PAA DGX] restored \`$after\` from Hugging Face for \`$id\`."
      fi
      # resuming into an output dir this box no longer has: bring back its partial backup
      if [ "$resume" = 1 ] && [ ! -d "$ART/$out" ]; then
        timeout 900 python "$HERE/hf_backup.py" restore "$ART" "$out" >> "$ROOT/backup.log" 2>&1 || true
      fi
      want=$gpus; [ -z "$want" ] && { [ "$step" = export ] && want=0 || want=1; }
      if [ "$want" -gt 0 ] && [ "$gpu_jobs" -ge "$MAXGPU" ]; then continue; fi
      largs=(--id "$id" --step "$step" --ref "$ref" --out "$out")
      [ -n "$after" ] && largs+=(--after "$after")
      [ -n "$gpus" ] && largs+=(--gpus "$gpus")
      [ "$resume" = 1 ] && largs+=(--resume)
      read -ra extra <<< "$args"
      out_txt=$(timeout 300 bash "$HERE/launch.sh" "${largs[@]}" -- "${extra[@]}" 2>&1); rc=$?
      case "$rc" in
        0) echo "$id" >> "$STATE"; touch "$ROOT/state_dirty"
           [ "$want" -gt 0 ] && gpu_jobs=$((gpu_jobs + 1))
           post "[PAA DGX] launched \`$id\` ($step${args:+ $args}) — $(printf '%s' "$out_txt" | grep -m1 '^gpu')."
           sleep 5 ;;
        3|4) : ;;   # waiting for its input / for a free card: retry next cycle
        *) echo "$id" >> "$STATE"; touch "$ROOT/state_dirty"
           post "[PAA DGX] could not launch \`$id\`:"$'\n```\n'"$out_txt"$'\n```' ;;
      esac
    done < "$ROOT/queue.parsed"
  else
    echo "$(date +%T) queue fetch failed (token/branch?)"
  fi

  # finished jobs: announce once (crash -> log tail; eval/export -> their result line)
  for d in "$ROOT"/jobs/*/; do
    id=$(basename "$d")
    grep -qs '^exit=' "$d/run.log" || continue
    grep -qxF "$id" "$POSTED" && continue
    code=$(sed -n 's/^exit=//p' "$d/run.log" | tail -1)
    msg="[PAA DGX] \`$id\` finished exit=$code."
    case "$code" in
      0) res=$(grep -E '^(stats|summary|eval|bundle) ' "$d/run.log" | tail -1 | cut -c1-3000)
         [ -n "$res" ] && msg="$msg"$'\n```\n'"$res"$'\n```' ;;
      stopped) ;;
      *) msg="$msg **Crashed**, last log lines:"$'\n```\n'"$(log_tail "$id")"$'\n```' ;;
    esac
    post "$msg"; echo "$id" >> "$POSTED"; touch "$ROOT/state_dirty"
  done

  backups
  check_token
  if [ $(( $(date +%s) - last_hb )) -ge "$HEARTBEAT" ]; then status_snapshot; last_hb=$(date +%s); fi
  sleep "$INTERVAL"
done

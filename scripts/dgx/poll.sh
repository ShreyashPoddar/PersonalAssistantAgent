#!/usr/bin/env bash
# PAA GPU queue poller on the DGX: the ONLY way PAA jobs start, and the off-campus control channel.
# Same design as LocalAI's scripts/cloud/dgx/queue_poll.sh, as a separate lane (~/paa, tmux "paa-queue"):
# the DGX has no inbound access, so everything goes through GitHub, outbound only.
#   - reads queue.yaml (branch gpu-queue) every PAA_QUEUE_INTERVAL s
#   - `jobs:`    starts each new job id once (whitelisted steps only, code pinned to a commit on main)
#   - `control:` status: <any new value> -> status post · logs: [id] -> log tail · stop: [id] -> stop it
#   - ONE GPU budget for both projects (paa_policy.py, rules = LocalAI's queue.yaml `limits:`): PAA launches only
#     into what LocalAI leaves free and pauses its own jobs when the total goes over (they resume automatically)
#   - posts launches, finishes, crash tails, eval results, budget changes and a 6-hourly heartbeat to the issue
#   - backs up artifacts + queue state to a private Hugging Face dataset, restores them when missing
#   - self-updates: merged changes to scripts/dgx on main are picked up automatically (syntax-checked first;
#     the watchdog rolls back to the last commit that completed a loop if new code crash-loops)
#
# Env (optional): PAA_HOME (~/paa)  PAA_CONTROL_SLUG  PAA_CODE_SLUG  PAA_QUEUE_REF (gpu-queue)  PAA_QUEUE_INTERVAL (180)
#   PAA_ISSUE (empty = don't post)  PAA_HEARTBEAT (21600)  PAA_MAX_GPU_JOBS (2, PAA's own extra cap)  PAA_HF_REPO
#   PAA_BACKUP_EVERY (1800)
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
MAXGPU="${PAA_MAX_GPU_JOBS:-2}"   # PAA's own cap; the shared budget (paa_policy.py) is applied on top
ART="$ROOT/artifacts"; mkdir -p "$ART" "$ROOT/jobs"
STATE="$ROOT/queue_state"; touch "$STATE"      # job ids already launched (or rejected)
POSTED="$ROOT/queue_posted"; touch "$POSTED"   # job ids whose finish was announced
PAUSED="$ROOT/paused"; touch "$PAUSED"         # job ids paused by the budget, resumed first (oldest first)
SEEN="$ROOT/control_seen.json"
MIRROR="$ROOT/PAA.git"
[ -x "$ROOT/venv/bin/python" ] && . "$ROOT/venv/bin/activate"
export HF_HOME="$ROOT/hf_cache"
: "${GITHUB_TOKEN:=}"
TOOLS_SHA=$(git -C "$TOOLS" rev-parse --short HEAD 2>/dev/null || echo "?")
MODE="?"; ALLOW=0

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

budget_line() {
  python3 -c 'import json,sys; s=json.load(open(sys.argv[1])); r=s.get("rule",{}); print("%s: cap %s cards for both projects, keep %s idle, min %s · both projects use %s (PAA %s) · idle %s · PAA may take %s more · rules from %s" % (s["mode"], r.get("cap"), r.get("keep_free"), r.get("floor"), s["ours"], s["paa"], s["idle"], s["allow"], s["rules_from"]))' \
    "$ROOT/sched.json" 2>/dev/null || echo "unknown"
}

status_snapshot() {
  local gpus run="" q id
  gpus=$(nvidia-smi --query-gpu=index,memory.used,utilization.gpu --format=csv,noheader 2>/dev/null \
         | awk -F', ' '{printf "gpu%s %s %s · ", $1, $2, $3}')
  for id in $(running_ids); do
    run="$run- \`$id\` $(grep -E '^(chunk|\{.loss|train |eval |holding|PAUSED)' "$ROOT/jobs/$id/run.log" 2>/dev/null | tail -1 | cut -c1-160)"$'\n'
  done
  q=$(cut -d$'\x1f' -f1 "$ROOT/queue.parsed" 2>/dev/null | grep -vxF -f "$STATE" | tr '\n' ' ')
  post "[PAA DGX] **status** (tools \`$TOOLS_SHA\`, $(date '+%F %H:%M'))"$'\n'"GPUs: $gpus"$'\n'"Budget: $(budget_line)"$'\n\n'"Running:"$'\n'"${run:-none}"$'\n'"Paused (resume first): $(tr '\n' ' ' < "$PAUSED")"$'\n'"Queued/waiting: ${q:-none}"$'\n'"Artifacts: $(ls "$ART" 2>/dev/null | tr '\n' ' ')"$'\n'"Last backup: $(grep -E 'backed up' "$ROOT/backup.log" 2>/dev/null | tail -1)"
}

stop_job() {
  if running_ids | grep -qxF "$1"; then
    tmux kill-session -t "paa-$1" 2>/dev/null
    echo "exit=stopped" >> "$ROOT/jobs/$1/run.log"
    post "[PAA DGX] stopped \`$1\` (control.stop)."
  else post "[PAA DGX] control.stop: no running job \`$1\`."; fi
}

pause_job() {  # $1 = job id: SIGUSR1 to its python processes -> save a checkpoint, exit 75 (resumed later, nothing lost)
  local d="$ROOT/jobs/$1" pid n=0
  [ -f "$d/.preempting" ] && return       # already asked
  for pid in $(pgrep -u "$(id -u)" -f python); do
    case "$(readlink /proc/$pid/cwd 2>/dev/null)" in "$d"/*|"$d") kill -USR1 "$pid" 2>/dev/null && n=$((n + 1)) ;; esac
  done
  touch "$d/.preempting"
  post "[PAA DGX] giving a card back: pausing \`$1\` ($MODE budget for both projects; it saves and resumes automatically)."
}

sync_mirror() {
  [ -d "$MIRROR" ] || git init -q --bare "$MIRROR"
  [ -n "$GITHUB_TOKEN" ] || return 1
  # time limit: on 2026-10-13 a network drop left this fetch hanging for 3 days with the poller frozen behind it
  timeout 300 git --git-dir="$MIRROR" -c credential.helper= fetch -q \
      "https://x-access-token:${GITHUB_TOKEN}@github.com/$CODE.git" "+refs/heads/main:refs/heads/main" 2>/dev/null
}
on_main() { git --git-dir="$MIRROR" merge-base --is-ancestor "$1" refs/heads/main 2>/dev/null; }

tools_valid() {  # every DGX script parses (bash -n / py_compile); prints the first failure
  local f
  for f in "$HERE"/*.sh; do bash -n "$f" 2>/dev/null || { echo "bash -n $(basename "$f")"; return 1; }; done
  for f in "$HERE"/*.py; do python3 -m py_compile "$f" 2>/dev/null || { echo "py_compile $(basename "$f")"; return 1; }; done
}

self_update() {  # fast-forward the tools checkout to merged main when scripts/dgx changed, then restart into it
  local new old why
  new=$(git --git-dir="$MIRROR" rev-parse refs/heads/main 2>/dev/null) || return 0
  old=$(git -C "$TOOLS" rev-parse HEAD 2>/dev/null) || return 0
  [ "$new" = "$old" ] && return 0
  git --git-dir="$MIRROR" diff --quiet "$old" "$new" -- scripts/dgx 2>/dev/null && return 0   # tools unchanged
  grep -qxF "$new" "$ROOT/tools.bad" 2>/dev/null && return 0                                  # known bad
  if timeout 120 git -C "$TOOLS" fetch -q "$MIRROR" main 2>/dev/null && git -C "$TOOLS" merge -q --ff-only FETCH_HEAD 2>/dev/null; then
    if ! why=$(tools_valid); then   # never restart into code that doesn't parse: roll back, remember the bad commit
      git -C "$TOOLS" reset -q --hard "$old"; echo "$new" >> "$ROOT/tools.bad"
      post "[PAA DGX] ⚠ tools \`${new:0:7}\` failed the syntax check ($why); stayed on \`${old:0:7}\`. Push a fix; it is picked up automatically."
      return 0
    fi
    post "[PAA DGX] tools updated \`${old:0:7}\` -> \`${new:0:7}\`; restarting the poller."
    exec bash "$HERE/poll.sh"
  fi
  echo "$new" >> "$ROOT/tools.bad"
  post "[PAA DGX] ⚠ tools update to \`${new:0:7}\` failed (checkout can't fast-forward); still on \`${old:0:7}\`."
}

gpu_wanting() {  # PAA GPU jobs ready to start now: paused ones + queued GPU jobs whose input is done
  local n id step ref out after gpus resume args
  n=$(grep -c . "$PAUSED" 2>/dev/null || true); n=${n:-0}
  while IFS=$'\x1f' read -r id step ref out after gpus resume args; do
    [ -n "$id" ] || continue
    grep -qxF "$id" "$STATE" && continue
    [ "$step" = export ] || [ "$gpus" = 0 ] && continue
    [ -z "$after" ] || [ -f "$ART/$after/.done" ] || continue
    n=$((n + ${gpus:-1}))
  done < "$ROOT/queue.parsed"
  echo "$n"
}

budget() {  # shared GPU budget for both projects -> MODE, ALLOW; pauses PAA jobs when the total is over
  local pol last
  pol=$(PAA_WANTING=$(gpu_wanting) timeout 60 python "$HERE/paa_policy.py" "$ROOT" 2>"$ROOT/sched.err") || pol="MODE error"$'\n'"ALLOW 0"
  MODE=$(printf '%s\n' "$pol" | sed -n 's/^MODE //p'); ALLOW=$(printf '%s\n' "$pol" | sed -n 's/^ALLOW //p'); ALLOW=${ALLOW:-0}
  for j in $(printf '%s\n' "$pol" | sed -n 's/^PREEMPT //p'); do pause_job "$j"; ALLOW=0; done
  last=$(cat "$ROOT/last_mode" 2>/dev/null)
  if [ "${MODE%% *}" != "${last%% *}" ]; then   # day <-> night (or an error): say so once
    echo "$MODE" > "$ROOT/last_mode"
    post "[PAA DGX] GPU budget now **${MODE%% *}** — $(budget_line)"
  fi
}

launch_one() {  # $@ = id step ref out after gpus resume args -> launch.sh rc; records the job line for resuming
  local id=$1 step=$2 ref=$3 out=$4 after=$5 gpus=$6 resume=$7 args=$8 largs extra
  largs=(--id "$id" --step "$step" --ref "$ref" --out "$out")
  [ -n "$after" ] && largs+=(--after "$after")
  [ -n "$gpus" ] && largs+=(--gpus "$gpus")
  [ "$resume" = 1 ] && largs+=(--resume)
  read -ra extra <<< "$args"
  # the budget (paa_policy.py) already decided how many cards we may take; launch.sh only picks an idle one
  OUT_TXT=$(PAA_LEAVE_FREE=0 timeout 300 bash "$HERE/launch.sh" "${largs[@]}" -- "${extra[@]}" 2>&1); local rc=$?
  [ "$rc" = 0 ] && printf '%s\x1f%s\x1f%s\x1f%s\x1f%s\x1f%s\x1f%s\n' "$step" "$ref" "$out" "$after" "$gpus" "1" "$args" > "$ROOT/jobs/$id/job.line"
  return $rc
}

resume_paused() {  # relaunch budget-paused jobs first, oldest first, while the budget allows
  local id base n new step ref out after gpus resume args
  while [ "$ALLOW" -gt 0 ] && [ -s "$PAUSED" ]; do
    id=$(head -1 "$PAUSED")
    [ -f "$ROOT/jobs/$id/job.line" ] || { sed -i '1d' "$PAUSED"; continue; }
    IFS=$'\x1f' read -r step ref out after gpus resume args < "$ROOT/jobs/$id/job.line"
    base=${id%-r[0-9]*}; n=1; while [ -e "$ROOT/jobs/$base-r$n" ]; do n=$((n + 1)); done; new="$base-r$n"
    if launch_one "$new" "$step" "$ref" "$out" "$after" "$gpus" 1 "$args"; then
      sed -i '1d' "$PAUSED"; echo "$new" >> "$STATE"; touch "$ROOT/state_dirty"; ALLOW=$((ALLOW - 1))
      post "[PAA DGX] resumed paused \`$id\` as \`$new\` — $(printf '%s' "$OUT_TXT" | grep -m1 '^gpu') ($MODE)."
      sleep 5
    else
      break   # no card right now; try again next cycle
    fi
  done
}

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
  date +%s > "$ROOT/poller.alive"   # the watchdog restarts a poller that stops looping (hung) for 30 min
  q=$([ -n "$GITHUB_TOKEN" ] && api -H "Accept: application/vnd.github.raw" "https://api.github.com/repos/$CSLUG/contents/queue.yaml?ref=$QREF")
  if [ -n "$q" ] && printf '%s' "$q" | grep -q '^jobs:'; then
    printf '%s' "$q" > "$ROOT/queue.current.yaml"
    python "$HERE/parse_queue.py" "$ROOT/queue.current.yaml" "$SEEN" "$ROOT/queue.parsed" > "$ROOT/queue.actions" 2> "$ROOT/queue.parse_errors" || true
    while IFS=$'\t' read -r act arg; do
      case "$act" in
        STATUS) budget; status_snapshot ;;
        LOGS)   if [ -f "$ROOT/jobs/$arg/run.log" ]; then post "[PAA DGX] log tail \`$arg\`:"$'\n```\n'"$(log_tail "$arg")"$'\n```'
                else post "[PAA DGX] control.logs: no job \`$arg\`."; fi ;;
        STOP)   stop_job "$arg" ;;
        BAD)    grep -qxF "bad:$arg" "$STATE" || { echo "bad:$arg" >> "$STATE"; post "[PAA DGX] ignored a queue entry: $arg"; } ;;
      esac
    done < "$ROOT/queue.actions"

    sync_mirror || echo "$(date +%T) mirror sync failed"
    self_update
    budget
    resume_paused
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
      if [ "$resume" = 1 ] && [ ! -d "$ART/$out" ]; then
        timeout 900 python "$HERE/hf_backup.py" restore "$ART" "$out" >> "$ROOT/backup.log" 2>&1 || true
      fi
      want=$gpus; [ -z "$want" ] && { [ "$step" = export ] && want=0 || want=1; }
      if [ "$want" -gt 0 ]; then
        [ "$gpu_jobs" -ge "$MAXGPU" ] && continue          # PAA's own cap
        [ "$want" -gt "$ALLOW" ] && continue               # the shared budget says wait
      fi
      launch_one "$id" "$step" "$ref" "$out" "$after" "$gpus" "$resume" "$args"; rc=$?
      case "$rc" in
        0) echo "$id" >> "$STATE"; touch "$ROOT/state_dirty"
           [ "$want" -gt 0 ] && { gpu_jobs=$((gpu_jobs + want)); ALLOW=$((ALLOW - want)); }
           post "[PAA DGX] launched \`$id\` ($step${args:+ $args}) — $(printf '%s' "$OUT_TXT" | grep -m1 '^gpu') (${MODE%% (*})."
           sleep 5 ;;
        3|4) : ;;   # waiting for its input / for a free card: retry next cycle
        *) echo "$id" >> "$STATE"; touch "$ROOT/state_dirty"
           post "[PAA DGX] could not launch \`$id\`:"$'\n```\n'"$OUT_TXT"$'\n```' ;;
      esac
    done < "$ROOT/queue.parsed"
  else
    echo "$(date +%T) queue fetch failed (token/branch?)"
  fi

  # finished jobs: announce once (crash -> log tail; eval/export -> result line; 75 -> paused by the budget)
  for d in "$ROOT"/jobs/*/; do
    id=$(basename "$d")
    grep -qs '^exit=' "$d/run.log" || continue
    grep -qxF "$id" "$POSTED" && continue
    code=$(sed -n 's/^exit=//p' "$d/run.log" | tail -1)
    msg="[PAA DGX] \`$id\` finished exit=$code."
    case "$code" in
      0) res=$(grep -E '^(stats|summary|eval|bundle|released) ' "$d/run.log" | tail -1 | cut -c1-3000)
         [ -n "$res" ] && msg="$msg"$'\n```\n'"$res"$'\n```' ;;
      75) grep -qxF "$id" "$PAUSED" || echo "$id" >> "$PAUSED"
          msg="[PAA DGX] \`$id\` paused for the shared GPU budget ($(grep -oE 'PAUSED .*' "$d/run.log" | tail -1)); it resumes automatically when a card is allowed." ;;
      stopped) ;;
      *) msg="$msg **Crashed**, last log lines:"$'\n```\n'"$(log_tail "$id")"$'\n```' ;;
    esac
    post "$msg"; echo "$id" >> "$POSTED"; touch "$ROOT/state_dirty"
  done

  backups
  check_token
  if [ $(( $(date +%s) - last_hb )) -ge "$HEARTBEAT" ]; then budget; status_snapshot; last_hb=$(date +%s); fi
  git -C "$TOOLS" rev-parse HEAD > "$ROOT/tools.good" 2>/dev/null   # this commit completed a loop
  sleep "$INTERVAL"
done

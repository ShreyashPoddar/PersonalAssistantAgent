#!/usr/bin/env bash
# Launch one PAA training-pipeline job on the DGX, detached in tmux. Called ONLY by poll.sh (single-launcher rule).
#
#   bash launch.sh --id ID --step gen_data|train|eval|export --ref SHA --out NAME [--after NAME] [--gpus N] [--resume] [-- ARGS...]
#
# The job clones the PAA repo at --ref (a commit on main, checked by poll.sh) from the local mirror and runs the
# whitelisted train/ script for --step. Outputs go to ~/paa/artifacts/<out>/ ; a finished job writes <out>/.done.
# GPU etiquette (the box is shared with LocalAI): only idle cards (<1 GiB used), highest index first (LocalAI's
# launcher takes the lowest), and only if PAA_LEAVE_FREE idle cards remain for LocalAI afterwards.
set -euo pipefail
shopt -s nullglob
ROOT="${PAA_HOME:-$HOME/paa}"
ART="$ROOT/artifacts"
LEAVE_FREE="${PAA_LEAVE_FREE:-2}"
ID="" STEP="" REF="" OUT="" AFTER="" GPUS="" RESUME=0 ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --id) ID=$2; shift 2 ;;
    --step) STEP=$2; shift 2 ;;
    --ref) REF=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --after) AFTER=$2; shift 2 ;;
    --gpus) GPUS=$2; shift 2 ;;
    --resume) RESUME=1; shift ;;
    --) shift; ARGS=("$@"); break ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
done
[ -n "$ID" ] && [ -n "$STEP" ] && [ -n "$REF" ] && [ -n "$OUT" ] || { echo "--id --step --ref --out are required"; exit 2; }
case "$STEP" in gen_data|train|eval) : "${GPUS:=1}" ;; export) : "${GPUS:=0}" ;; *) echo "unknown step $STEP"; exit 2 ;; esac
[ -x "$ROOT/venv/bin/python" ] || { echo "no venv at $ROOT/venv - run setup_env.sh first"; exit 1; }
grep -q '^HF_TOKEN=..' "$ROOT/secrets.env" 2>/dev/null || { echo "HF_TOKEN empty in $ROOT/secrets.env"; exit 1; }

# a job's inputs come from the job it runs after
IN=""
if [ -n "$AFTER" ]; then
  [ -f "$ART/$AFTER/.done" ] || { echo "waiting: $AFTER not done"; exit 3; }
  IN="$ART/$AFTER"
fi
case "$STEP" in
  gen_data) CMD=(python train/gen_data.py --out "$ART/$OUT") ;;
  train)    [ -n "$IN" ] || { echo "train needs after: <gen_data out>"; exit 2; }
            CMD=(python train/train_sft.py --data "$IN" --out "$ART/$OUT"); [ "$RESUME" = 1 ] && CMD+=(--resume) ;;
  eval)     [ -n "$IN" ] || { echo "eval needs after: <train out>"; exit 2; }
            data=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"])' "$IN/train_summary.json")
            CMD=(python train/eval_model.py --model "$IN/model" --data "$data" --out "$ART/$OUT") ;;
  export)   [ -n "$IN" ] || { echo "export needs after: <train out>"; exit 2; }
            CMD=(python train/export_task.py --model "$IN/model" --out "$ART/$OUT") ;;
esac
CMD+=("${ARGS[@]}")

# GPUs: idle ones not already held by our own unfinished jobs (a fresh job loads before it allocates memory)
GPU_LIST=""
if [ "$GPUS" -gt 0 ]; then
  mine=""
  for d in "$ROOT"/jobs/*/; do
    [ -f "$d/run.sh" ] && ! grep -q '^exit=' "$d/run.log" 2>/dev/null || continue
    mine="$mine $(sed -n 's/.*CUDA_VISIBLE_DEVICES=\([0-9,]*\).*/\1/p' "$d/run.sh" | head -1 | tr ',' ' ')"
  done
  idle=$(nvidia-smi --query-gpu=index,memory.used --format=csv,noheader,nounits \
         | awk -F', ' -v mine="$mine" 'BEGIN {n = split(mine, m, " "); for (i = 1; i <= n; i++) busy[m[i]] = 1}
                                       $2 < 1024 && !($1 in busy) {print $1}' | sort -rn)
  n_idle=$(printf '%s\n' "$idle" | grep -c . || true)
  [ "$n_idle" -ge $(( GPUS + LEAVE_FREE )) ] || { echo "no free gpu: $n_idle idle, need $GPUS + $LEAVE_FREE left for LocalAI"; exit 4; }
  GPU_LIST=$(printf '%s\n' "$idle" | head -"$GPUS" | paste -sd, -)
fi

JOB="$ROOT/jobs/$ID"
[ -e "$JOB/run.sh" ] && { echo "job dir $JOB exists (ids are single-use)"; exit 1; }
mkdir -p "$JOB" "$ART/$OUT"
VENV="$ROOT/venv"
if [ "$STEP" = export ]; then   # venv-litert = fresh litert-torch venv export_task.py builds itself; venv-export = old one
  for v in venv-litert venv-export; do [ -x "$ROOT/$v/bin/python" ] && { [ "$v" != venv-litert ] || [ -f "$ROOT/$v/.ok" ]; } && { VENV="$ROOT/$v"; break; }; done
fi
{
  echo '#!/usr/bin/env bash'
  echo "# $ID: $STEP @ $REF -> artifacts/$OUT  (launched $(date '+%F %T') by scripts/dgx/launch.sh)"
  echo "export HOME=$(printf %q "$HOME") HF_HOME=$(printf %q "$ROOT/hf_cache")"
  echo "set -a; . $(printf %q "$ROOT/secrets.env"); set +a"
  echo ". $(printf %q "$VENV/bin/activate")"
  echo "export CUDA_VISIBLE_DEVICES=${GPU_LIST:-} PYTHONUNBUFFERED=1 TOKENIZERS_PARALLELISM=false"
  echo "cd $(printf %q "$JOB")"
  echo "{ [ -d PAA ] || git clone -q $(printf %q "$ROOT/PAA.git") PAA; } && git -C PAA checkout -q $(printf %q "$REF") && cd PAA || { echo exit=clone | tee -a ../run.log; exit 1; }"
  printf '%q ' "${CMD[@]}"; echo "2>&1 | tee -a $(printf %q "$JOB/run.log")"
  echo 'code=${PIPESTATUS[0]}'
  echo "cp $(printf %q "$JOB/run.log") $(printf %q "$ART/$OUT/run.log") 2>/dev/null"
  echo "[ \"\$code\" = 0 ] && date '+%F %T' > $(printf %q "$ART/$OUT/.done")"
  echo "echo \"exit=\$code\" | tee -a $(printf %q "$JOB/run.log")"
} > "$JOB/run.sh"
tmux new-session -d -s "paa-$ID" "bash '$JOB/run.sh'"
echo "gpu ${GPU_LIST:-cpu}  job $ID ($STEP) -> $ART/$OUT"

#!/usr/bin/env bash
# One-time PAA lane setup on the DGX (campus). Idempotent. Everything lives under ~/paa, separate from ~/localai.
#   export HOME=/raid/workspace/$(whoami)        # this account has no real home
#   mkdir -p ~/paa && git clone https://github.com/ShreyashPoddar/PersonalAssistantAgent.git ~/paa/PAA
#   bash ~/paa/PAA/scripts/dgx/setup_env.sh
# Creates: venv/ (vLLM + transformers, GPU steps), venv-export/ (ai-edge-torch + mediapipe, CPU export step),
#          PAA.git (bare mirror jobs are cloned from), secrets.env template (chmod 600, you fill it in by hand).
set -euo pipefail
[ -d "$HOME" ] && [ -w "$HOME" ] || { echo "HOME=$HOME missing/read-only: export HOME=/raid/workspace/$(whoami)"; exit 1; }
ROOT="${PAA_HOME:-$HOME/paa}"
TOOLS="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$ROOT/artifacts" "$ROOT/jobs" "$ROOT/hf_cache"

# highest pyenv 3.1x with headers (same reasoning as LocalAI's setup_env.sh), else system python3
PY=""
for p in $(ls -d "$HOME"/.pyenv/versions/3.1[1-9]*/bin/python 2>/dev/null | sort -V -r) "$(command -v python3 || true)"; do
  [ -x "$p" ] && "$p" -c 'import sys; assert sys.version_info >= (3, 10)' 2>/dev/null && { PY=$p; break; }
done
[ -n "$PY" ] || { echo "need python >= 3.10 (run /home/faculty/utils/install_pyenv.sh)"; exit 1; }
echo "python: $PY ($("$PY" --version 2>&1))"

mkvenv() {
  [ -x "$1/bin/python" ] && return
  if ! "$PY" -m venv "$1" 2>/dev/null; then rm -rf "$1"; "$PY" -m venv --without-pip "$1"; curl -sSf https://bootstrap.pypa.io/get-pip.py | "$1/bin/python"; fi
  "$1/bin/python" -m pip install -q --upgrade pip
}
mkvenv "$ROOT/venv"
"$ROOT/venv/bin/pip" install -q -r "$TOOLS/train/requirements.txt"   # vllm brings a matching CUDA torch
mkvenv "$ROOT/venv-export"
"$ROOT/venv-export/bin/pip" install -q -r "$TOOLS/train/requirements-export.txt"
"$ROOT/venv/bin/python" -c 'import torch, vllm, transformers; print("gpu venv ok: torch", torch.__version__, "cuda", torch.cuda.is_available(), "vllm", vllm.__version__)'
"$ROOT/venv-export/bin/python" -c 'import ai_edge_torch, mediapipe; print("export venv ok")'

[ -d "$ROOT/PAA.git" ] || git clone -q --bare https://github.com/ShreyashPoddar/PersonalAssistantAgent.git "$ROOT/PAA.git"

if [ ! -f "$ROOT/secrets.env" ]; then
  printf 'HF_TOKEN=\nGITHUB_TOKEN=\nPAA_ISSUE=\n' > "$ROOT/secrets.env"
fi
chmod 600 "$ROOT/secrets.env"
echo "now fill in $ROOT/secrets.env (see scripts/dgx/README.md step 4), then install the watchdog cron line."

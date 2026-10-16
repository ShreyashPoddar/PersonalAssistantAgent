#!/usr/bin/env python
"""Fine-tuned HF model -> MediaPipe .task bundle the app can load (LocalLlm, MediaPipe LLM Inference).

    python train/export_task.py --model <train out>/model --size 1b --out <dir>        # -> <dir>/paa-tasks-1b.task

Steps: litert-torch (formerly ai-edge-torch) converts the PyTorch weights to a quantized .tflite (dynamic_int8 by default), then the
MediaPipe bundler packs it with the SentencePiece tokenizer. The bundle has NO prompt template on purpose: the
app wraps the turn itself (LocalLlm.wrapTurn), exactly like training did. The file name must start with "paa-":
that is how the app knows to send the compact prompt.

Runs on CPU (no GPU needed) but wants ~16 GB RAM for 1B. If the converter's flags changed, its --help is printed.
If the venv only has the old ai-edge-torch (whose generative API moved to litert-torch), litert-torch is pip-installed.
"""
import argparse
import importlib.util
import json
import shutil
import subprocess
import sys
from pathlib import Path


REQS = Path(__file__).with_name("requirements-export.txt")


def converter_module():
    """Module path of the Gemma 3 converter: litert_torch (current) or ai_edge_torch (before the rename)."""
    for pkg in ("litert_torch", "ai_edge_torch"):
        try:
            if importlib.util.find_spec(f"{pkg}.generative.examples.gemma3.convert_gemma3_to_tflite"):
                return f"{pkg}.generative.examples.gemma3.convert_gemma3_to_tflite"
        except ModuleNotFoundError:
            pass
    print("converter not found; installing litert-torch into this venv", flush=True)
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-r", str(REQS)], check=True)
    importlib.invalidate_caches()
    return "litert_torch.generative.examples.gemma3.convert_gemma3_to_tflite"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="HF-format dir from train_sft.py (<out>/model)")
    ap.add_argument("--size", choices=["270m", "1b"], required=True)
    ap.add_argument("--base", default=None, help="base repo for tokenizer.model (default google/gemma-3-<size>-it)")
    ap.add_argument("--quantize", default="dynamic_int8", help="litert-torch quant recipe, e.g. dynamic_int8, dynamic_int4_block32")
    ap.add_argument("--prefill", default="512", help="prefill lengths, comma-separated")
    ap.add_argument("--kv", type=int, default=1280, help="KV cache length (prompt + answer)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--tag", default="", help="suffix for the file name, e.g. v1")
    a = ap.parse_args()
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    work = out / "tflite"; work.mkdir(exist_ok=True)

    mod = converter_module()
    cmd = [sys.executable, "-m", mod,
           f"--model_size={a.size}", f"--checkpoint_path={a.model}", f"--output_path={work}",
           "--output_name_prefix=paa", f"--prefill_seq_lens={a.prefill}", f"--kv_cache_max_len={a.kv}",
           f"--quantize={a.quantize}"]
    print("converting:", " ".join(cmd), flush=True)
    if subprocess.run(cmd).returncode != 0:
        subprocess.run([sys.executable, "-m", mod, "--helpfull"])
        sys.exit("conversion failed (converter flags above)")
    tfl = max(work.glob("*.tflite"), key=lambda p: p.stat().st_mtime)

    tokm = Path(a.model) / "tokenizer.model"
    if not tokm.exists():
        from huggingface_hub import hf_hub_download
        tokm = Path(hf_hub_download(a.base or f"google/gemma-3-{a.size}-it", "tokenizer.model"))

    from mediapipe.tasks.python.genai import bundler
    name = f"paa-tasks-{a.size}{'-' + a.tag if a.tag else ''}.task"
    bundler.create_bundle(bundler.BundleConfig(
        tflite_model=str(tfl), tokenizer_model=str(tokm), start_token="<bos>", stop_tokens=["<eos>", "<end_of_turn>"],
        output_filename=str(out / name), enable_bytes_to_unicode_mapping=False))
    size_mb = (out / name).stat().st_size / 1e6
    (out / "export.json").write_text(json.dumps({"task": name, "size_mb": round(size_mb, 1), "quantize": a.quantize,
                                                 "prefill": a.prefill, "kv": a.kv, "model": a.model}, indent=1))
    shutil.rmtree(work, ignore_errors=True)    # the .task holds everything; keep backups small
    print(f"bundle {name} {size_mb:.0f} MB", flush=True)


if __name__ == "__main__":
    main()

"""LoRA fine-tune of a bigger model for the LAPTOP (hub /extract), e.g. Gemma 4 12B — run by train_sft.py --lora.

    python train/train_sft.py --lora --base google/gemma-4-12B-it --data <data dir> --out <dir> [--extra-data data-v9]

Same records and prompt text as the phone model (paa_format.record_prompt), but in the BASE model's own chat format
(tokenizer.apply_chat_template), because llama.cpp's server applies that template at inference (hub --llm-prompt
student). Trains only LoRA weights on the language model (vision/audio towers untouched), then converts the adapter
to GGUF with llama.cpp's convert_lora_to_gguf.py so the laptop runs:  llama-server -m <base q4 gguf> --lora <adapter>.

Outputs: <out>/adapter/ (PEFT), <out>/paa-lora.gguf, <out>/train_summary.json, <out>/.done.
Gemma 4 needs transformers >= 5.23: installed once into $PAA_HOME/pylib-g4 (the GPU venv keeps its own torch).
"""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = Path(os.environ.get("PAA_HOME", str(Path.home() / "paa")))
LIB = ROOT / "pylib-g4"
PINS = ["transformers==5.23.0"]                      # with its deps (no torch)
PINS_NODEPS = ["peft==0.21.3", "accelerate==1.15.0"]  # torch comes from the venv
LLAMA_CPP_TAG = "b12373"                              # same build the laptop runs


def bootstrap():
    if not (LIB / ".ok").exists():
        LIB.mkdir(parents=True, exist_ok=True)
        pip = [sys.executable, "-m", "pip", "install", "-q", "--target", str(LIB)]
        subprocess.run(pip + PINS, check=True)
        subprocess.run(pip + ["--no-deps"] + PINS_NODEPS, check=True)
        (LIB / ".ok").touch()
    sys.path.insert(0, str(LIB))


def owner_in_title(r: dict) -> bool:
    """Older rounds kept titles with the owner's name (gen_data v11 rejects them)."""
    import re
    owners = {n.lower() for n in r.get("owner_names") or []}
    return any(owners & {w.lower().removesuffix("'s") for w in re.findall(r"[A-Za-z']+", str(t.get("title", "")))}
               for t in r["target"].get("tasks") or [])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lora", action="store_true")
    ap.add_argument("--base", default="google/gemma-4-12B-it")
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--extra-data", default="")
    ap.add_argument("--epochs", type=float, default=2)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--micro", type=int, default=4)
    ap.add_argument("--max-len", type=int, default=1280)
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--then-eval", default="")   # accepted for queue compatibility; scoring happens on the laptop
    ap.add_argument("--hold", action="store_true")
    a = ap.parse_args()
    import signal   # shared GPU budget asks for the card back: stop; a --resume run continues from the last checkpoint
    signal.signal(signal.SIGUSR1, lambda *_: (print("PAUSED (shared GPU budget)", flush=True), os._exit(75)))
    bootstrap()
    sys.path.insert(0, str(HERE))
    import torch
    import transformers
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments
    from paa_format import canonical_target, record_prompt
    from gen_data import no_task_category
    print("transformers", transformers.__version__, "torch", torch.__version__, flush=True)

    data, out = Path(a.data), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    load = lambda p: [json.loads(l) for l in p.open(encoding="utf-8")]
    rows = load(data / "train.jsonl")
    for name in filter(None, a.extra_data.split(",")):
        rows += [r for r in load(data.parent / name / "train.jsonl")
                 if not (no_task_category(r.get("category", "")) and r["target"].get("tasks")) and not owner_in_title(r)]
    val_rows = load(data / "val.jsonl")

    tok = AutoTokenizer.from_pretrained(a.base)
    eot = "<turn|>" if "<turn|>" in tok.get_vocab() else (tok.eos_token or "")

    def encode(r):
        prompt = tok.apply_chat_template([{"role": "user", "content": record_prompt(r)}], tokenize=False,
                                         add_generation_prompt=True, enable_thinking=False)
        p = tok(prompt, add_special_tokens=False)["input_ids"]
        t = tok(canonical_target(r["target"]) + eot, add_special_tokens=False)["input_ids"]
        return None if len(p) + len(t) > a.max_len else (p + t, [-100] * len(p) + t)

    train = [x for x in map(encode, rows) if x]
    val = [x for x in map(encode, val_rows) if x]
    print(f"train {len(train)}  val {len(val)}  (eot {eot!r})", flush=True)

    model = AutoModelForCausalLM.from_pretrained(a.base, dtype=torch.bfloat16, attn_implementation="sdpa").to("cuda")
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.enable_input_require_grads()
    model = get_peft_model(model, LoraConfig(
        r=a.rank, lora_alpha=2 * a.rank, lora_dropout=0.05, task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        exclude_modules=r".*(vision|audio|embed_vision|embed_audio).*"))
    model.print_trainable_parameters()

    pad = tok.pad_token_id if tok.pad_token_id is not None else 0

    def collate(batch):
        n = max(len(x) for x, _ in batch)
        ids = torch.full((len(batch), n), pad); lab = torch.full((len(batch), n), -100)
        att = torch.zeros((len(batch), n), dtype=torch.long)
        for i, (x, y) in enumerate(batch):
            ids[i, :len(x)] = torch.tensor(x); lab[i, :len(y)] = torch.tensor(y); att[i, :len(x)] = 1
        return {"input_ids": ids, "labels": lab, "attention_mask": att}

    args = TrainingArguments(
        output_dir=str(out / "ckpt"), num_train_epochs=a.epochs, learning_rate=a.lr, lr_scheduler_type="cosine",
        warmup_steps=max(1, int(0.03 * a.epochs * len(train) / a.batch)), per_device_train_batch_size=a.micro, per_device_eval_batch_size=a.micro,
        gradient_accumulation_steps=max(1, a.batch // a.micro), bf16=True, logging_steps=20, eval_strategy="steps",
        eval_steps=200, save_steps=200, save_total_limit=2, report_to=[], remove_unused_columns=False,
        dataloader_num_workers=2)
    trainer = Trainer(model=model, args=args, train_dataset=train, eval_dataset=val, data_collator=collate)
    ckpts = sorted((out / "ckpt").glob("checkpoint-*"), key=lambda p: int(p.name.split("-")[1])) if a.resume else []
    res = trainer.train(resume_from_checkpoint=str(ckpts[-1]) if ckpts else None)
    model.save_pretrained(out / "adapter")
    tok.save_pretrained(out / "adapter")
    ev = trainer.evaluate()

    gguf = to_gguf(out / "adapter", a.base, out / "paa-lora.gguf")
    summary = {"base": a.base, "data": str(data.resolve()), "extra_data": a.extra_data, "train": len(train),
               "val": len(val), "train_loss": res.training_loss, "eval_loss": ev.get("eval_loss"), "rank": a.rank,
               "epochs": a.epochs, "lr": a.lr, "gguf": gguf}
    (out / "train_summary.json").write_text(json.dumps(summary, indent=1))
    print("summary", json.dumps(summary), flush=True)
    subprocess.run(["rm", "-rf", str(out / "ckpt")])   # checkpoints are big and not needed once the adapter exists
    (out / ".done").write_text("ok\n")


def to_gguf(adapter: Path, base: str, dest: Path) -> str:
    """PEFT adapter -> llama.cpp LoRA GGUF (CPU, a minute). Needs the base model's config (from the HF cache)."""
    from huggingface_hub import snapshot_download
    src = ROOT / f"llama.cpp-{LLAMA_CPP_TAG}"
    if not (src / "convert_lora_to_gguf.py").exists():
        subprocess.run(["git", "clone", "-q", "--depth", "1", "--branch", LLAMA_CPP_TAG,
                        "https://github.com/ggml-org/llama.cpp", str(src)], check=True)
    env = {**os.environ, "PYTHONPATH": f"{src / 'gguf-py'}:{LIB}:{os.environ.get('PYTHONPATH', '')}"}
    base_dir = snapshot_download(base, allow_patterns=["*.json", "*.jinja", "tokenizer*"])
    r = subprocess.run([sys.executable, str(src / "convert_lora_to_gguf.py"), str(adapter), "--base", base_dir,
                        "--outtype", "f16", "--outfile", str(dest)], env=env, capture_output=True, text=True)
    print(r.stdout[-2000:], r.stderr[-3000:], flush=True)
    return dest.name if r.returncode == 0 and dest.exists() else f"FAILED ({r.returncode})"


if __name__ == "__main__":
    main()

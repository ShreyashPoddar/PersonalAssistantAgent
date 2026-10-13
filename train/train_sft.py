#!/usr/bin/env python
"""Fine-tune a small Gemma 3 on the synthetic data so it runs on the phone's GPU without the long rules prompt.

    python train/train_sft.py --base google/gemma-3-1b-it --data <dir with train.jsonl/val.jsonl> --out <dir>
    python train/train_sft.py ... --resume            # continue from the newest checkpoint in <out>

The loss covers only the answer JSON (+ <end_of_turn>), never the prompt. Writes <out>/model (HF format, what
export_task.py converts) and <out>/train_summary.json. Gemma models are gated: accept the licence on the model's
Hugging Face page with the account whose HF_TOKEN is used.
"""
import argparse
import json
import sys
from pathlib import Path

import torch
from transformers import (AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments)

sys.path.insert(0, str(Path(__file__).parent))
from paa_format import STOP, canonical_target, record_prompt, wrap  # noqa: E402


def load(path: Path):
    return [json.loads(l) for l in path.open(encoding="utf-8")]


class Data(torch.utils.data.Dataset):
    def __init__(self, rows, tok, max_len):
        self.items = []
        for r in rows:
            p = tok(tok.bos_token + wrap(record_prompt(r)), add_special_tokens=False)["input_ids"]
            a = tok(canonical_target(r["target"]) + STOP, add_special_tokens=False)["input_ids"]
            if len(p) + len(a) > max_len:
                continue
            self.items.append((p + a, [-100] * len(p) + a))

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        return self.items[i]


def collate(pad_id):
    def f(batch):
        n = max(len(x) for x, _ in batch)
        ids = torch.full((len(batch), n), pad_id)
        lab = torch.full((len(batch), n), -100)
        att = torch.zeros((len(batch), n), dtype=torch.long)
        for i, (x, y) in enumerate(batch):
            ids[i, :len(x)] = torch.tensor(x); lab[i, :len(y)] = torch.tensor(y); att[i, :len(x)] = 1
        return {"input_ids": ids, "labels": lab, "attention_mask": att}
    return f


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="google/gemma-3-1b-it")
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=float, default=3)
    ap.add_argument("--lr", type=float, default=None, help="default 5e-5 (1B) / 1e-4 (270M)")
    ap.add_argument("--batch", type=int, default=16, help="global batch")
    ap.add_argument("--micro", type=int, default=8)
    ap.add_argument("--max-len", type=int, default=1024)
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--then-eval", default="", help="then run eval_model.py into artifacts/<name>")
    ap.add_argument("--then-train", default="", help="then train BASE,OUT[,EVAL] on the same card")
    ap.add_argument("--hold", action="store_true", help="at the end keep the card reserved until control.stop")
    a = ap.parse_args()
    # shared box: claim the card at once (data/tokenizer loading takes a while and the card looks idle meanwhile)
    hold = None
    if torch.cuda.is_available():
        free, _ = torch.cuda.mem_get_info()
        hold = torch.empty(max(0, int(free - 1.5 * 2**30)), dtype=torch.uint8, device="cuda")
    data, out = Path(a.data), Path(a.out)
    out.mkdir(parents=True, exist_ok=True)

    tok = AutoTokenizer.from_pretrained(a.base)
    # guard against train/serve skew: our hand-built turn must equal the model's own chat template
    probe = record_prompt({"message": "hi", "ctx": {}, "now": "2026-10-12T21:05", "owner_names": ["x"], "grad_year": 2028})
    templ = tok.apply_chat_template([{"role": "user", "content": probe}], tokenize=False, add_generation_prompt=True)
    assert templ == tok.bos_token + wrap(probe), f"chat template differs from paa_format.wrap:\n{templ!r}"

    train, val = Data(load(data / "train.jsonl"), tok, a.max_len), Data(load(data / "val.jsonl"), tok, a.max_len)
    print(f"train {len(train)}  val {len(val)}", flush=True)
    del hold; torch.cuda.empty_cache()   # hand the claimed memory to training
    model = AutoModelForCausalLM.from_pretrained(a.base, torch_dtype=torch.bfloat16, attn_implementation="eager")
    small = model.num_parameters() < 5e8
    args = TrainingArguments(
        output_dir=str(out / "ckpt"), num_train_epochs=a.epochs, learning_rate=a.lr or (1e-4 if small else 5e-5),
        per_device_train_batch_size=a.micro, per_device_eval_batch_size=a.micro,
        gradient_accumulation_steps=max(1, a.batch // a.micro), lr_scheduler_type="cosine", warmup_ratio=0.03,
        weight_decay=0.0, bf16=True, gradient_checkpointing=not small, logging_steps=20,
        eval_strategy="epoch", save_strategy="steps", save_steps=300, save_total_limit=1,
        report_to=[], dataloader_num_workers=2, remove_unused_columns=False)
    trainer = Trainer(model=model, args=args, train_dataset=train, eval_dataset=val, data_collator=collate(tok.pad_token_id))
    last = None
    if a.resume:
        cks = sorted((out / "ckpt").glob("checkpoint-*"), key=lambda p: int(p.name.split("-")[-1]))
        last = str(cks[-1]) if cks else None
        print(f"resuming from {last}", flush=True)
    res = trainer.train(resume_from_checkpoint=last)
    ev = trainer.evaluate()
    trainer.save_model(str(out / "model")); tok.save_pretrained(str(out / "model"))
    summary = {"base": a.base, "data": str(data.resolve()), "train": len(train), "val": len(val), "train_loss": res.training_loss,
               "eval_loss": ev.get("eval_loss"), "steps": res.global_step}
    (out / "train_summary.json").write_text(json.dumps(summary, indent=1))
    print("summary", json.dumps(summary), flush=True)
    chain(a, data, out, trainer, model)


def chain(a, data: Path, out: Path, trainer, model):
    """Keep the shared card in ONE job: queue jobs are separate processes, and between two of them the card
    looks idle for up to 3 min and gets taken. Each step still writes its own artifacts/<name>/.done, so CPU-only
    jobs that wait on them (export) start as soon as a step is finished."""
    import re
    import subprocess
    import time
    if not (a.then_eval or a.then_train or a.hold):
        return
    (out / ".done").write_text(time.strftime("%F %T"))      # model is saved: exports may start now
    del trainer, model
    torch.cuda.empty_cache()
    me = Path(__file__).parent
    sib = lambda n: out.parent / (n if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", n) else sys.exit(f"bad name {n}"))
    if a.then_eval:
        r = subprocess.run([sys.executable, str(me / "eval_model.py"), "--model", str(out / "model"),
                            "--data", str(data), "--out", str(sib(a.then_eval))])
        if r.returncode == 0:
            (sib(a.then_eval) / ".done").write_text(time.strftime("%F %T"))
    if a.then_train:
        base, o, e = (a.then_train.split(",") + ["", ""])[:3]
        cmd = [sys.executable, str(me / "train_sft.py"), "--base", base, "--data", str(data), "--out", str(sib(o))]
        cmd += ["--epochs", "4"] if "270m" in base else []
        cmd += ["--then-eval", e] if e else []
        cmd += ["--hold"] if a.hold else []
        sys.exit(subprocess.run(cmd).returncode)          # the next model holds the card from here on
    if a.hold:
        free, _ = torch.cuda.mem_get_info()
        keep = torch.empty(max(0, int(free - 1.5 * 2**30)), dtype=torch.uint8, device="cuda")
        print(f"holding the card for PAA ({keep.numel() / 2**30:.1f} GiB) until control.stop", flush=True)
        while True:
            time.sleep(600)


if __name__ == "__main__":
    main()

#!/usr/bin/env python
"""Fine-tune a small Gemma 3 on the synthetic data so it runs on the phone's GPU without the long rules prompt.

    python train/train_sft.py --base google/gemma-3-1b-it --data <dir with train.jsonl/val.jsonl> --out <dir>
    python train/train_sft.py ... --resume            # continue from the newest checkpoint in <out>

The loss covers only the answer JSON (+ <end_of_turn>), never the prompt. Writes <out>/model (HF format, what
export_task.py converts) and <out>/train_summary.json. Gemma models are gated: accept the licence on the model's
Hugging Face page with the account whose HF_TOKEN is used.

Shared GPU budget: SIGUSR1 (sent by the DGX poller when both projects together are over their card limit) makes it
save a checkpoint at the next step and exit 75; the poller relaunches it later with --resume. Nothing is lost.
"""
import argparse
import dataclasses
import json
import math
import signal
import sys
import threading
from pathlib import Path

import torch
import transformers
from transformers import (AutoModelForCausalLM, AutoTokenizer, Trainer, TrainerCallback, TrainingArguments)

PAUSED_EXIT = 75
PAUSE = threading.Event()
signal.signal(signal.SIGUSR1, lambda *_: PAUSE.set())


class PauseCallback(TrainerCallback):
    """Pause request -> save a checkpoint now and stop; resumed later from it."""
    def on_step_end(self, args, state, control, **kw):
        if PAUSE.is_set():
            control.should_save = True
            control.should_training_stop = True
        return control


def training_args(**kw) -> TrainingArguments:
    """TrainingArguments across transformers versions: names change (warmup_ratio was removed, evaluation_strategy
    became eval_strategy), so drop/rename what this installed version doesn't know instead of crashing."""
    known = {f.name for f in dataclasses.fields(TrainingArguments)}
    if "eval_strategy" in kw and "eval_strategy" not in known and "evaluation_strategy" in known:
        kw["evaluation_strategy"] = kw.pop("eval_strategy")
    dropped = sorted(k for k in kw if k not in known)
    if dropped:
        print(f"note: this transformers ({transformers.__version__}) has no {dropped}; skipped", flush=True)
    return TrainingArguments(**{k: v for k, v in kw.items() if k in known})


def dtype_kw() -> dict:
    major, minor = (int(x) for x in transformers.__version__.split(".")[:2])
    return {"dtype": torch.bfloat16} if (major, minor) >= (4, 56) else {"torch_dtype": torch.bfloat16}


def paused(step) -> None:
    print(f"PAUSED at step {step} (shared GPU budget)", flush=True)
    sys.exit(PAUSED_EXIT)

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
    if a.resume and (out / "model" / "config.json").exists() and (out / ".done").exists():
        print("model already trained; continuing the chain", flush=True)   # paused during eval / hold
        del hold
        torch.cuda.empty_cache()
        return chain(a, data, out)

    tok = AutoTokenizer.from_pretrained(a.base)
    # guard against train/serve skew: our hand-built turn must equal the model's own chat template
    probe = record_prompt({"message": "hi", "ctx": {}, "now": "2026-10-12T21:05", "owner_names": ["x"], "grad_year": 2028})
    templ = tok.apply_chat_template([{"role": "user", "content": probe}], tokenize=False, add_generation_prompt=True)
    assert templ == tok.bos_token + wrap(probe), f"chat template differs from paa_format.wrap:\n{templ!r}"

    train, val = Data(load(data / "train.jsonl"), tok, a.max_len), Data(load(data / "val.jsonl"), tok, a.max_len)
    print(f"train {len(train)}  val {len(val)}", flush=True)
    if PAUSE.is_set():
        paused(0)
    del hold
    torch.cuda.empty_cache()   # hand the claimed memory to training
    model = AutoModelForCausalLM.from_pretrained(a.base, attn_implementation="eager", **dtype_kw())
    small = model.num_parameters() < 5e8
    accum = max(1, a.batch // a.micro)
    total_steps = math.ceil(math.ceil(len(train) / (a.micro * accum)) * a.epochs)
    args = training_args(
        output_dir=str(out / "ckpt"), num_train_epochs=a.epochs, learning_rate=a.lr or (1e-4 if small else 5e-5),
        per_device_train_batch_size=a.micro, per_device_eval_batch_size=a.micro,
        gradient_accumulation_steps=accum, lr_scheduler_type="cosine", warmup_steps=max(1, int(0.03 * total_steps)),
        weight_decay=0.0, bf16=True, gradient_checkpointing=not small, logging_steps=20,
        eval_strategy="epoch", save_strategy="steps", save_steps=200, save_total_limit=1,
        report_to=[], dataloader_num_workers=2, remove_unused_columns=False)
    trainer = Trainer(model=model, args=args, train_dataset=train, eval_dataset=val,
                      data_collator=collate(tok.pad_token_id), callbacks=[PauseCallback()])
    last = None
    if a.resume:
        cks = sorted((out / "ckpt").glob("checkpoint-*"), key=lambda p: int(p.name.split("-")[-1]))
        last = str(cks[-1]) if cks else None
        print(f"resuming from {last}", flush=True)
    res = trainer.train(resume_from_checkpoint=last)
    if PAUSE.is_set():
        paused(trainer.state.global_step)
    ev = trainer.evaluate()
    trainer.save_model(str(out / "model")); tok.save_pretrained(str(out / "model"))
    summary = {"base": a.base, "data": str(data.resolve()), "train": len(train), "val": len(val), "train_loss": res.training_loss,
               "eval_loss": ev.get("eval_loss"), "steps": res.global_step}
    (out / "train_summary.json").write_text(json.dumps(summary, indent=1))
    print("summary", json.dumps(summary), flush=True)
    del trainer, model
    chain(a, data, out)


def chain(a, data: Path, out: Path):
    """Keep the shared card in ONE job: queue jobs are separate processes, and between two of them the card
    looks idle for up to 3 min and gets taken. Each step still writes its own artifacts/<name>/.done, so CPU-only
    jobs that wait on them (export) start as soon as a step is finished."""
    import re
    import subprocess
    import time
    if not (a.then_eval or a.then_train or a.hold):
        return
    (out / ".done").write_text(time.strftime("%F %T"))      # model is saved: exports may start now
    torch.cuda.empty_cache()
    me = Path(__file__).parent
    sib = lambda n: out.parent / (n if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", n) else sys.exit(f"bad name {n}"))
    if a.then_eval and not (sib(a.then_eval) / ".done").exists():
        r = subprocess.run([sys.executable, str(me / "eval_model.py"), "--model", str(out / "model"),
                            "--data", str(data), "--out", str(sib(a.then_eval))])
        if r.returncode == PAUSED_EXIT or PAUSE.is_set():
            paused("eval")
        if r.returncode == 0:
            (sib(a.then_eval) / ".done").write_text(time.strftime("%F %T"))
    if PAUSE.is_set():
        paused("between models")
    if a.then_train:
        base, o, e = (a.then_train.split(",") + ["", ""])[:3]
        cmd = [sys.executable, str(me / "train_sft.py"), "--base", base, "--data", str(data), "--out", str(sib(o))]
        cmd += ["--epochs", "4"] if "270m" in base else []
        cmd += ["--then-eval", e] if e else []
        cmd += ["--hold"] if a.hold else []
        cmd += ["--resume"] if a.resume else []            # a resumed chain resumes the next model too
        sys.exit(subprocess.run(cmd).returncode)          # the next model holds the card from here on
    if a.hold:
        free, _ = torch.cuda.mem_get_info()
        keep = torch.empty(max(0, int(free - 1.5 * 2**30)), dtype=torch.uint8, device="cuda")
        print(f"holding the card for PAA ({keep.numel() / 2**30:.1f} GiB) until control.stop", flush=True)
        while not PAUSE.wait(60):          # the budget wants the card back -> release it (the work is done)
            pass
        del keep
        print("released the card (shared GPU budget); all work for this job is done", flush=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python
"""Score a fine-tuned model (HF format) on the held-out synthetic test set and on tools/scenarios.json.

    python train/eval_model.py --model <dir>/model --data <gen dir> --out <dir>

Writes <out>/eval.json. Synthetic metrics compare with the teacher's labels; the scenario check is the offline
version of tools/eval_chats.py (single-step scenarios only; times are checked as "a when was given", since the
real clock resolution happens in the app). The final gate is still tools/eval_chats.py on the emulator with
the exported .task.
"""
import argparse
import json
import os
import signal
import sys
from datetime import datetime
from pathlib import Path

import torch
import transformers
from transformers import AutoModelForCausalLM, AutoTokenizer

# shared GPU budget: the DGX poller may ask for the card back; eval is short, so just stop (rerun later)
signal.signal(signal.SIGUSR1, lambda *_: os._exit(75))

sys.path.insert(0, str(Path(__file__).parent))
from paa_format import build_prompt, record_prompt, wrap  # noqa: E402
from gen_data import first_json, words  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent


def generate(model, tok, prompts, batch=1):
    outs = []
    tok.padding_side = "left"
    for i in range(0, len(prompts), batch):
        enc = tok([tok.bos_token + wrap(p) for p in prompts[i:i + batch]], return_tensors="pt", padding=True,
                  add_special_tokens=False).to(model.device)
        with torch.no_grad():
            g = model.generate(**enc, max_new_tokens=300, do_sample=False,
                               eos_token_id=[tok.convert_tokens_to_ids("<end_of_turn>"), tok.eos_token_id])
        outs += tok.batch_decode(g[:, enc["input_ids"].shape[1]:], skip_special_tokens=True)
    return outs


def title_f1(a, b):
    wa, wb = words(a), words(b)
    if not wa or not wb:
        return 0.0
    c = len(wa & wb)
    return 0.0 if c == 0 else 2 * c / (len(wa) + len(wb))


def score_synthetic(rows, outs):
    m = {"n": len(rows), "json_ok": 0, "has_task_acc": 0, "count_acc": 0, "title_f1": 0.0, "when_acc": 0,
         "when_n": 0, "done_acc": 0, "update_acc": 0, "false_task": 0, "missed_task": 0}
    for r, o in zip(rows, outs):
        p, g = first_json(o), r["target"]
        if not isinstance(p, dict):
            m["missed_task"] += bool(g["tasks"])
            continue
        m["json_ok"] += 1
        pt, gt = p.get("tasks") or [], g["tasks"]
        m["has_task_acc"] += bool(pt) == bool(gt)
        m["false_task"] += bool(pt) and not gt
        m["missed_task"] += bool(gt) and not pt
        m["count_acc"] += len(pt) == len(gt)
        m["done_acc"] += sorted(p.get("done") or []) == sorted(g["done"])
        m["update_acc"] += sorted(str(u.get("n")) for u in p.get("update") or []) == sorted(str(u["n"]) for u in g["update"])
        for t in gt:   # each gold task vs its best-matching predicted one
            best = max(pt, key=lambda x: title_f1(x.get("title"), t["title"]), default=None)
            m["title_f1"] += title_f1(best.get("title"), t["title"]) if best else 0.0
            m["when_n"] += 1
            if best and words(best.get("when")) == words(t.get("when")):
                m["when_acc"] += 1
    gold_tasks = sum(len(r["target"]["tasks"]) for r in rows)
    n = max(1, m["n"])
    return {"n": m["n"], "json_ok": m["json_ok"] / n, "has_task_acc": m["has_task_acc"] / n,
            "count_acc": m["count_acc"] / n, "false_task_rate": m["false_task"] / n, "missed_task_rate": m["missed_task"] / n,
            "title_f1": m["title_f1"] / max(1, gold_tasks), "when_acc": m["when_acc"] / max(1, m["when_n"]),
            "done_acc": m["done_acc"] / n, "update_acc": m["update_acc"] / n}


def scenario_cases():
    """tools/scenarios.json -> (id, prompt, expectations) for scenarios with a single 'wait'."""
    cases = []
    for s in json.loads((ROOT / "tools" / "scenarios.json").read_text(encoding="utf-8")):
        steps = s["steps"]
        if sum("wait" in st for st in steps) != 1 or s.get("out"):
            continue
        texts = [st["text"] for st in steps if "text" in st]
        who = s.get("sender") or s["chat"]
        burst = len(texts) > 1
        msg = "\n".join(f"{who}: {t}" for t in texts) if burst else texts[0]
        ctx = {"chat_name": s["chat"], "sender": s.get("sender") if s.get("group") else s["chat"],
               "is_group": bool(s.get("group")), "burst": burst}
        cases.append((s["id"], build_prompt(msg, ctx, datetime(2026, 10, 12, 18, 0), ["shreyash", "shreyas"], 2028), s["expect"]))
    return cases


def check(expect, verdict):
    tasks = (verdict or {}).get("tasks") or []
    for e in expect:
        if "no" in e and tasks:
            return False
        if e.get("contains"):
            kw = e.get("keyword", "").lower()
            hit = [t for t in tasks if kw in str(t.get("title", "")).lower()]
            if not hit:
                return False
            if any(k.startswith("due_") for k in e) and not any(t.get("when") for t in hit):
                return False
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=600)
    ap.add_argument("--batch", type=int, default=1, help="1 = no padding, as on the phone (batched left-padding garbled ~7% of Gemma 3 outputs)")
    a = ap.parse_args()
    tok = AutoTokenizer.from_pretrained(a.model)
    major, minor = (int(x) for x in transformers.__version__.split(".")[:2])
    dt = {"dtype": torch.bfloat16} if (major, minor) >= (4, 56) else {"torch_dtype": torch.bfloat16}
    model = AutoModelForCausalLM.from_pretrained(a.model, **dt).to("cuda" if torch.cuda.is_available() else "cpu").eval()
    rows = [json.loads(l) for l in (Path(a.data) / "test.jsonl").open(encoding="utf-8")][:a.limit]
    syn_outs = generate(model, tok, [record_prompt(r) for r in rows], a.batch)
    syn = score_synthetic(rows, syn_outs)
    cases = scenario_cases()
    outs = generate(model, tok, [p for _, p, _ in cases], a.batch)
    sc = {cid: check(exp, first_json(o)) for (cid, _, exp), o in zip(cases, outs)}
    res = {"model": a.model, "synthetic": syn, "scenarios_passed": f"{sum(sc.values())}/{len(sc)}", "scenarios": sc}
    Path(a.out).mkdir(parents=True, exist_ok=True)
    (Path(a.out) / "eval.json").write_text(json.dumps(res, indent=1))
    bad = [{"id": r.get("id"), "category": r.get("category"), "message": r.get("message"), "output": o, "target": r["target"]}
           for r, o in zip(rows, syn_outs) if not isinstance(first_json(o), dict)]
    with (Path(a.out) / "json_failures.jsonl").open("w", encoding="utf-8") as f:   # outputs that weren't valid JSON
        f.writelines(json.dumps(b, ensure_ascii=False) + "\n" for b in bad)
    print("eval", json.dumps(res), flush=True)


if __name__ == "__main__":
    main()

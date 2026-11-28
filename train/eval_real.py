"""Score models on the owner's PRIVATE real-chat test set (built on the laptop from a phone dump; never in git, never on
the DGX). Run on the laptop:

    python train/eval_real.py --set ../PAA-private/realset-v1.jsonl --model <hf dir> [--model <hf dir> ...] --out <dir>
    python train/eval_real.py --set ... --server http://127.0.0.1:8081 --name gemma4-12b --out <dir>   # llama.cpp server
         (--prompt rules: the teacher's rules as system prompt, for an untrained model; student: the trained prompt)

Each case: {"id", "now", "msg", "ctx", "expect": [[kw, ...], ...] (one inner list per expected task; a task matches
when its title contains every kw), "cat"}. Reports, per model: cases passed, task recall, false tasks (any task on a
no-task case, or extra tasks), titles naming the owner, per category. Writes <out>/<model name>.json with outputs
(private: keep <out> outside the repo).
"""
import argparse
import json
import sys
from collections import defaultdict
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from paa_format import build_prompt  # noqa: E402
from gen_data import first_json  # noqa: E402

OWNERS = ["shreyash", "shreyas"]
GRAD_YEAR = 2028


def score(case, verdict):
    tasks = [t for t in ((verdict or {}).get("tasks") or []) if isinstance(t, dict)]
    titles = [str(t.get("title", "")).lower() for t in tasks]
    used, found = set(), 0
    for kws in case["expect"]:
        hit = next((i for i, t in enumerate(titles) if i not in used and all(k.lower() in t for k in kws)), None)
        if hit is not None:
            used.add(hit)
            found += 1
    extra = len(tasks) - len(used)
    owner_named = sum(any(o in t.split() or f"{o}'s" in t for o in OWNERS) for t in titles)
    ok = found == len(case["expect"]) and extra == 0 and owner_named == 0
    return {"ok": ok, "found": found, "expected": len(case["expect"]), "extra": extra, "owner_named": owner_named,
            "json_ok": isinstance(verdict, dict)}


def summarize(rows):
    s = {"cases": len(rows), "passed": sum(r["ok"] for r in rows),
         "task_recall": f'{sum(r["found"] for r in rows)}/{sum(r["expected"] for r in rows)}',
         "false_tasks": sum(r["extra"] for r in rows),
         "no_task_cases_with_a_task": sum(1 for r in rows if r["expected"] == 0 and r["extra"] > 0),
         "owner_named_titles": sum(r["owner_named"] for r in rows),
         "json_failures": sum(not r["json_ok"] for r in rows)}
    by = defaultdict(lambda: [0, 0])
    for r in rows:
        by[r["cat"]][0] += r["ok"]
        by[r["cat"]][1] += 1
    s["by_category"] = {k: f"{v[0]}/{v[1]}" for k, v in sorted(by.items())}
    return s


VERDICT_SCHEMA = {"type": "object", "required": ["tasks", "reason", "done", "update"], "properties": {
    "tasks": {"type": "array", "items": {"type": "object", "required": ["title", "when", "priority", "registration", "confidence", "reason"],
              "properties": {"title": {"type": "string"}, "when": {"type": ["string", "null"]}, "priority": {"type": "integer"},
                             "registration": {"type": "boolean"}, "confidence": {"type": "number"}, "reason": {"type": "string"}}}},
    "reason": {"type": "string"}, "done": {"type": "array", "items": {"type": "integer"}},
    "update": {"type": "array", "items": {"type": "object", "required": ["n"], "properties": {
        "n": {"type": "integer"}, "when": {"type": ["string", "null"]}, "title": {"type": ["string", "null"]}}}}}}


def server_messages(prompt: str, mode: str) -> list:
    if mode == "student":
        return [{"role": "user", "content": prompt}]
    from gen_data import RULES
    names = " or ".join(n.capitalize() for n in OWNERS)
    return [{"role": "system", "content": RULES.format(names=names, year=GRAD_YEAR)}, {"role": "user", "content": prompt + "\nJSON:"}]


def ask_server(url: str, messages: list, lora: float | None = None) -> tuple:
    """One chat completion from a local llama.cpp server, output forced to the verdict JSON schema."""
    import time
    import urllib.request
    body = json.dumps({"messages": messages, "temperature": 0, "max_tokens": 500, "chat_template_kwargs": {"enable_thinking": False}, **({"lora": [{"id": 0, "scale": lora}]} if lora is not None else {}),
                       "response_format": {"type": "json_schema", "json_schema": {"name": "verdict", "schema": VERDICT_SCHEMA}}}).encode()
    t0 = time.time()
    req = urllib.request.Request(url.rstrip("/") + "/v1/chat/completions", body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        out = json.loads(r.read())["choices"][0]["message"]["content"]
    return out, time.time() - t0


def run_server(a, cases, prompts, out: Path):
    rows, secs = [], []
    for c, p in zip(cases, prompts):
        o, dt = ask_server(a.server, server_messages(p, a.prompt), a.lora)
        secs.append(dt)
        rows.append({**score(c, first_json(o)), "id": c["id"], "cat": c["cat"], "output": o, "seconds": round(dt, 2)})
    summary = summarize(rows)
    secs.sort()
    summary["seconds_median"] = round(secs[len(secs) // 2], 2)
    summary["seconds_max"] = round(secs[-1], 2)
    (out / f"{a.name}.json").write_text(json.dumps({"summary": summary, "rows": rows}, ensure_ascii=False, indent=1),
                                        encoding="utf-8")
    print(a.name, json.dumps(summary), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", required=True)
    ap.add_argument("--model", action="append")
    ap.add_argument("--server", help="a llama.cpp server URL instead of --model (laptop only: localhost)")
    ap.add_argument("--name", default="server")
    ap.add_argument("--prompt", choices=["rules", "student"], default="rules")
    ap.add_argument("--lora", type=float, help="per-request LoRA scale (server started with --lora)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    if a.server:
        cases = [json.loads(l) for l in Path(a.set).open(encoding="utf-8") if l.strip()]
        prompts = [build_prompt(c["msg"], c["ctx"], datetime.fromisoformat(c["now"]), OWNERS, GRAD_YEAR) for c in cases]
        out = Path(a.out)
        out.mkdir(parents=True, exist_ok=True)
        return run_server(a, cases, prompts, out)
    assert a.model, "--model or --server"
    import torch
    import transformers
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from eval_model import generate

    cases = [json.loads(l) for l in Path(a.set).open(encoding="utf-8") if l.strip()]
    prompts = [build_prompt(c["msg"], c["ctx"], datetime.fromisoformat(c["now"]), OWNERS, GRAD_YEAR) for c in cases]
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    major, minor = (int(x) for x in transformers.__version__.split(".")[:2])
    dtype = {"dtype": torch.bfloat16} if (major, minor) >= (4, 56) else {"torch_dtype": torch.bfloat16}
    table = {}
    for m in a.model:
        tok = AutoTokenizer.from_pretrained(m)
        model = AutoModelForCausalLM.from_pretrained(m, **dtype).to("cuda" if torch.cuda.is_available() else "cpu").eval()
        outs = generate(model, tok, prompts, 1)
        rows = []
        for c, o in zip(cases, outs):
            r = score(c, first_json(o))
            rows.append({**r, "id": c["id"], "cat": c["cat"], "output": o})
        mp = Path(m.rstrip("/"))
        name = mp.parent.name if mp.name == "model" else (mp.name or m)  # .../train-1b-v7b/model → train-1b-v7b
        summary = summarize(rows)
        table[name] = summary
        (out / f"{name}.json").write_text(json.dumps({"summary": summary, "rows": rows}, ensure_ascii=False, indent=1),
                                          encoding="utf-8")
        print(name, json.dumps(summary), flush=True)
        del model
        torch.cuda.empty_cache()
    (out / "summary.json").write_text(json.dumps(table, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()

#!/usr/bin/env python
"""Synthetic training data for PAA's on-device task extractor, written by a big open "teacher" model on the DGX.

No real chat ever goes in: the teacher invents realistic Hinglish/Hindi/English WhatsApp situations, then labels
them with the same rules the app's prompt uses (LocalLlm.chatPromptPrefix). Each label is sampled three times
(1 greedy + 2 at temperature 0.7) and kept only if all three agree — disagreement = ambiguous or teacher error.

    python train/gen_data.py --n 20000 --out $PAA_OUT            # resumable: re-run continues where it stopped
    python train/gen_data.py --n 50 --out /tmp/x --teacher Qwen/Qwen3-8B   # quick smoke test

Writes <out>/labeled.jsonl (every attempt, with keep=true/false), then train/val/test.jsonl + stats.json.
"""
import argparse
import hashlib
import json
import os
import random
import re
import signal
import sys
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from paa_format import build_prompt, canonical_target  # noqa: E402

# (category, kinds it fits, weight). kinds: received / sent / burst / call; group allowed per category
CATEGORIES = [
    ("a classmate or teacher reminds the owner of an assignment / lab record / file submission with a deadline", "received burst", 3),
    ("a friend asks the owner for a favour (send notes, bring something, share a file, recharge, book a ticket)", "received burst", 3),
    ("the owner promises to do something in their own message (kal bhej dunga, will call you by 5)", "sent burst", 3),
    ("a family member (papa, mummy, bhaiya, didi, dadi) asks the owner to do an errand", "received burst call", 3),
    ("a project group splits work and @mentions or names the owner for a part", "received burst", 2),
    ("a project group assigns work to someone else, NOT the owner", "received burst", 2),
    ("a hackathon / fest / workshop registration post, sometimes for the owner's batch, sometimes not", "received", 2),
    ("an internship or job post that names eligible batches (sometimes includes the owner's batch, sometimes not)", "received", 2),
    ("an advertisement, sale, cashback or promotional message", "received", 2),
    ("a bank / UPI / OTP / delivery / app notification style message", "received", 1),
    ("an announcement to the whole class or group with no personal action (holiday, room change, results out)", "received", 2),
    ("casual chat, jokes, memes, or a vague suggestion without commitment (call pe baat karte hai, kabhi milte hai)", "received sent burst", 3),
    ("a reply showing one of the owner's OPEN tasks is now finished (ho gaya, bhej diya, done bro, mil gaya thanks)", "received sent burst", 2),
    ("the deadline or details of one of the owner's OPEN tasks change (4 baje tak bhi chalega, ab monday ko)", "received burst", 2),
    ("a bare acceptance like 'haan kar dunga' / 'ok' with nothing asked before it", "sent", 1),
    ("the owner accepts a request that is in the earlier lines (haan kar dunga, ok will do)", "sent burst", 2),
    ("a task spread over several short lines: the work in one line, the day in another, the time in a third", "burst", 2),
    ("a phone call where the owner agrees to do something or is asked to do something by a time", "call", 2),
    ("a phone call with only small talk / catching up, nothing to do", "call", 1),
    ("someone else's own plan or reminder that does not involve the owner (main kal jaa raha hu)", "received burst", 2),
    ("a money request or promise (UPI kar dena, 500 rupay bhej dena, paise wapas kab)", "received sent burst", 2),
    ("a meeting / class / viva / interview scheduled with the owner", "received burst", 2),
    ("something already done, said in past tense (I already submitted it, bhej diya tha)", "sent received", 1),
    ("two unrelated tasks in one message (report bhejna + fees bharna)", "received sent", 1),
]
LANGS = [("Hinglish (Roman-script Hindi mixed with English, as Indian students really text)", 6),
         ("English with Indian texting style", 3), ("Hindi in Devanagari script", 1),
         ("very casual Hinglish with typos, no punctuation, short forms (kl, h, nhi, pls, bhai)", 3)]
TIME_STYLES = ["compact clock times like 140, 315, 1145 meaning 1:40, 3:15, 11:45", "kal / parso / aaj raat",
               "a weekday (monday ko, by friday)", "'X baje tak' / 'X baje se pehle'", "a date (15 oct, 20th)",
               "by EOD / tonight / in 2 hours", "no time at all"]
OWNER_DEFAULT = (["shreyash", "shreyas"], 2028)
FIRST = ["aarav", "vihaan", "ishaan", "kabir", "arjun", "rohan", "aditya", "ananya", "diya", "isha", "kavya", "meera",
         "priya", "sneha", "tanvi", "nikhil", "harsh", "yash", "pranav", "sakshi", "ritika", "neha", "aryan", "dev",
         "riddhi", "tanish", "omkar", "pooja", "simran", "varun", "karthik", "lakshmi", "arnav", "sanya", "ahaan"]

GEN_TEMPLATE = """Invent ONE realistic WhatsApp situation from the life of an Indian engineering college student, \
called {owner} (graduates {year}). It will train a to-do extractor, so make it natural and varied.

Category: {category}
Kind: {kind_desc}
Chat: {chat_desc}
Language: {lang}
Time words: {time_style}
Earlier lines in the chat before it: {n_earlier} ("Name: text", 'You' = the owner; may be 0)
Owner's open to-dos to show: {n_open} (short titles with the deadline in brackets, e.g. "Send lab record to Meera (kal)")

Use varied Indian names (not {owner}). Output ONLY this JSON, nothing else:
{{"chat_name": "...", "sender": "..." or null, "earlier": ["..."], "open_tasks": ["..."], "message": "..."}}"""

KIND_DESC = {
    "received": "one message the owner RECEIVED",
    "sent": "one message the owner SENT (sender null)",
    "burst": "2-5 consecutive new short lines, one per line as 'Name: text' ('You' = the owner), joined with \\n in message",
    "call": "a 6-20 second speech-to-text transcript of a phone call: lowercase, no punctuation, both speakers mixed "
            "with no names, a couple of recognition errors (chat_name = the caller, sender null)",
}

RULES = """You are the personal assistant of {names} (graduates {year}). Read chat messages (English, Hindi, Hinglish)
like a person would and list the real to-dos THE OWNER must do. Reply with ONLY JSON.

Rules:
- A task = a promise the owner makes ("kal bhej dunga") or a request made to the owner. In groups only if it names the owner.
- Not a task: suggestions ("call pe baat karte hai"), jokes, ads, announcements for everyone, someone else's plan, things already done.
- "haan kar dunga"/"ok will do" accepts the request above it; with nothing above it is NOT a task.
- Internship/job posts: only if open to the {year} batch or no batch named.
- Use earlier lines only to fill in details (deadline, what "it" is, who); never create a task found only there.
- title: 2-7 English words starting with a verb; name the person involved using ONLY names from the messages.
- when: ALL time words for that task joined, even from different lines ("kal tak" + "5 baje se pehle" = "kal 5 baje se pehle"); else null. 140/315/1145 are clock times.
- One task per piece of work. confidence 0-1. priority 0-3. registration true only for hackathon/event sign-ups.
- done: numbers of open tasks the messages show are already finished. update: open tasks whose deadline/details changed (never re-create them).
- reason: one short sentence.

Format:
{{"tasks": [{{"title": "...", "when": "..." or null, "priority": 1, "registration": false, "confidence": 0.9, "reason": "..."}}], "reason": "why no task, if none", "done": [], "update": [{{"n": 1, "when": "...", "title": null}}]}}"""


def pick(rng, items):
    return rng.choices([i[0] for i in items], weights=[i[-1] for i in items])[0]


def plan(idx: int, seed: int) -> dict:
    rng = random.Random(seed * 1_000_003 + idx)
    cat = rng.choices(CATEGORIES, weights=[c[2] for c in CATEGORIES])[0]
    kind = rng.choice(cat[1].split())
    group = kind != "call" and kind != "sent" and rng.random() < (0.6 if "group" in cat[0] else 0.3)
    if rng.random() < 0.3:
        names, year = OWNER_DEFAULT
    else:
        n = rng.choice(FIRST)
        names, year = ([n, n[:-1]] if rng.random() < 0.3 and len(n) > 4 else [n]), rng.choice([2026, 2027, 2028, 2029])
    open_n = rng.choice([0, 0, 1, 2, 3]) if "OPEN" not in cat[0] else rng.choice([1, 2, 3])
    now = datetime(2026, 1, 1, 0, 0) + timedelta(days=rng.randrange(365), hours=rng.randrange(7, 24), minutes=rng.randrange(60))
    return {"idx": idx, "category": cat[0], "kind": kind, "group": group, "lang": pick(rng, LANGS),
            "time_style": rng.choice(TIME_STYLES), "n_open": open_n,
            "n_earlier": 0 if kind == "call" else rng.choice([0, 0, 1, 2, 3, 4]),   # the app sends calls without chat lines
            "names": names, "year": year, "now": now.isoformat(timespec="minutes")}


def gen_prompt(p: dict) -> str:
    chat_desc = "a group chat (give it a name; sender = the person who wrote it)" if p["group"] else "a one-to-one chat (chat_name = that person)"
    return GEN_TEMPLATE.format(owner=p["names"][0].capitalize(), year=p["year"], category=p["category"],
                               kind_desc=KIND_DESC[p["kind"]], chat_desc=chat_desc, lang=p["lang"],
                               time_style=p["time_style"], n_earlier=p["n_earlier"], n_open=p["n_open"])


def first_json(text: str):
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
    start = text.find("{")
    while start >= 0:
        depth = 0
        for i in range(start, len(text)):
            depth += {"{": 1, "}": -1}.get(text[i], 0)
            if depth == 0:
                try:
                    return json.loads(text[start:i + 1])
                except json.JSONDecodeError:
                    break
        start = text.find("{", start + 1)
    return None


def to_record(p: dict, s: dict):
    """Teacher's scenario JSON -> a dataset record (ctx as LocalLlm.ChatContext), or None if malformed."""
    if not isinstance(s, dict) or not isinstance(s.get("message"), str) or not s["message"].strip():
        return None
    as_list = lambda v: [str(x) for x in v][:8] if isinstance(v, list) else []
    kind = p["kind"]
    ctx = {"outgoing": kind == "sent", "chat_name": (s.get("chat_name") or None), "is_group": p["group"],
           "sender": None if kind in ("sent", "call") else (s.get("sender") or None),
           "recent": as_list(s.get("earlier")), "open_tasks": as_list(s.get("open_tasks"))[:5],
           "learned": [], "is_call": kind == "call", "burst": kind == "burst"}
    return {"id": f"syn-{p['idx']}", "now": p["now"], "owner_names": p["names"], "grad_year": p["year"],
            "category": p["category"], "kind": kind, "ctx": ctx, "message": s["message"].strip()}


def words(t: str) -> set:
    return set(re.findall(r"[a-z0-9]+", (t or "").lower()))


def agree(a: dict, b: dict) -> bool:
    ta, tb = a.get("tasks") or [], b.get("tasks") or []
    if len(ta) != len(tb) or sorted(a.get("done") or []) != sorted(b.get("done") or []):
        return False
    if sorted(u.get("n") for u in a.get("update") or []) != sorted(u.get("n") for u in b.get("update") or []):
        return False
    for x in ta:
        wx = words(x.get("title"))
        if not any(len(wx & words(y.get("title"))) / max(1, len(wx | words(y.get("title")))) >= 0.34
                   and (x.get("when") is None) == (y.get("when") is None) for y in tb):
            return False
    return True


def sane(label: dict, prompt: str, n_open: int) -> str:
    """'' if the label is usable, else why not."""
    for t in label.get("tasks") or []:
        title = str(t.get("title") or "")
        if not 2 <= len(title.split()) <= 9:
            return "title length"
        # names in a title must come from the input (the rule small models break most)
        for w in re.findall(r"\b[A-Z][a-z]{2,}\b", title)[1:]:
            if w.lower() not in prompt.lower() and w.lower() not in {"the", "and", "for", "lab", "ppt", "pdf"}:
                return f"invented name {w}"
    for n in (label.get("done") or []) + [u.get("n") for u in label.get("update") or []]:
        if not isinstance(n, int) or not 1 <= n <= n_open:
            return "bad open-task number"
    return ""


def hold_gpu():
    """Shared box: between launch.sh picking an idle card and vLLM loading (minutes), the card still looks idle
    and someone else can take it. Grab all but 1.5 GiB of it right away; released just before vLLM starts."""
    import torch
    if not torch.cuda.is_available():
        return None
    free, _ = torch.cuda.mem_get_info()
    keep = int(free - 1.5 * 2**30)
    print(f"claimed {max(keep, 0) / 2**30:.1f} GiB of the card until the teacher loads", flush=True)
    return torch.empty(keep, dtype=torch.uint8, device="cuda") if keep > 0 else None


def main():
    # shared GPU budget: the DGX poller may ask for the card back -> stop now; a re-run continues from the
    # finished chunks in labeled.jsonl (only the chunk in progress is redone)
    signal.signal(signal.SIGUSR1, lambda *_: (print('PAUSED (shared GPU budget)', flush=True), os._exit(75)))
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=20000)
    ap.add_argument("--out", required=True)
    ap.add_argument("--teacher", default="Qwen/Qwen3-32B-AWQ")
    ap.add_argument("--tp", type=int, default=1, help="GPUs for the teacher (tensor parallel)")
    ap.add_argument("--chunk", type=int, default=1024)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--no-eager", dest="eager", action="store_false",
                    help="allow CUDA graphs / torch.compile (faster, but more ways to fail at start-up)")
    a = ap.parse_args()
    hold = hold_gpu()   # first thing: claim the card before the slow imports, so nobody else takes it meanwhile
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    labeled = out / "labeled.jsonl"
    done = set()
    if labeled.exists():
        for line in labeled.open(encoding="utf-8"):
            try:
                done.add(json.loads(line)["idx"])
            except Exception:
                pass
    todo = [i for i in range(a.n) if i not in done]
    print(f"{len(done)} done, {len(todo)} to go, teacher {a.teacher}", flush=True)

    if todo:
        # engine in this process: a start-up failure then ends the log with its real cause (the queue posts
        # only the last lines), instead of a generic "Engine core initialization failed" from a child process
        os.environ.setdefault("VLLM_ENABLE_V1_MULTIPROCESSING", "0")
        import torch
        from vllm import LLM, SamplingParams
        del hold; torch.cuda.empty_cache()   # hand the claimed memory straight to vLLM
        free, total = torch.cuda.mem_get_info()
        print(f"gpu memory: {free / 2**30:.1f} GiB free of {total / 2**30:.1f} GiB", flush=True)
        # vLLM's fraction is of the WHOLE card and must fit in what is free now (a shared box: others may hold some)
        util = min(0.88, free / total - 0.04)
        llm = LLM(model=a.teacher, tensor_parallel_size=a.tp, max_model_len=4096, gpu_memory_utilization=util,
                  enforce_eager=a.eager)
        kw = {"chat_template_kwargs": {"enable_thinking": False}}   # Qwen3: no long thinking; others ignore it
        gen_sp = SamplingParams(temperature=0.95, top_p=0.95, max_tokens=700, seed=a.seed)
        greedy = SamplingParams(temperature=0, max_tokens=500)
        sampled = SamplingParams(temperature=0.7, top_p=0.95, max_tokens=500, n=2, seed=a.seed)
        chat = lambda convs, sp: llm.chat(convs, sp, use_tqdm=False, **kw)
        for c0 in range(0, len(todo), a.chunk):
            plans = [plan(i, a.seed) for i in todo[c0:c0 + a.chunk]]
            outs = chat([[{"role": "user", "content": gen_prompt(p)}] for p in plans], gen_sp)
            recs = [to_record(p, first_json(o.outputs[0].text)) for p, o in zip(plans, outs)]
            ok = [(p, r) for p, r in zip(plans, recs) if r]
            convs = [[{"role": "system", "content": RULES.format(names=" or ".join(n.capitalize() for n in r["owner_names"]), year=r["grad_year"])},
                      {"role": "user", "content": build_prompt(r["message"], r["ctx"], datetime.fromisoformat(r["now"]), r["owner_names"], r["grad_year"]) + "\nJSON:"}]
                     for _, r in ok]
            g_out, s_out = chat(convs, greedy), chat(convs, sampled)
            with labeled.open("a", encoding="utf-8") as f:
                for p, r in zip(plans, recs):
                    if not r:
                        f.write(json.dumps({"idx": p["idx"], "keep": False, "why": "bad scenario"}) + "\n")
                for (p, r), conv, g, s in zip(ok, convs, g_out, s_out):
                    gold = first_json(g.outputs[0].text)
                    alts = [first_json(o.text) for o in s.outputs]
                    why = ("unparsable" if not isinstance(gold, dict) or not all(isinstance(x, dict) for x in alts) else
                           "teacher disagrees" if not all(agree(gold, x) for x in alts) else
                           sane(gold, conv[1]["content"], len(r["ctx"]["open_tasks"])))
                    row = {"idx": p["idx"], "keep": not why, "why": why, **r}
                    if not why:
                        row["target"] = json.loads(canonical_target(gold))
                    f.write(json.dumps(row, ensure_ascii=False) + "\n")
            print(f"chunk {c0 // a.chunk + 1}: {c0 + len(plans)}/{len(todo)}", flush=True)

    # split by a hash of the id, so re-runs and resumes always put a record in the same split
    rows = [json.loads(l) for l in labeled.open(encoding="utf-8")]
    kept = [r for r in rows if r.get("keep")]
    splits = {"train": [], "val": [], "test": []}
    for r in kept:
        h = int(hashlib.sha1(r["id"].encode()).hexdigest(), 16) % 100
        splits["val" if h < 3 else "test" if h < 6 else "train"].append(r)
    for k, v in splits.items():
        with (out / f"{k}.jsonl").open("w", encoding="utf-8") as f:
            f.writelines(json.dumps(r, ensure_ascii=False) + "\n" for r in v)
    why = {}
    for r in rows:
        if not r.get("keep"):
            why[r.get("why", "?")] = why.get(r.get("why", "?"), 0) + 1
    stats = {"attempted": len(rows), "kept": len(kept), "with_tasks": sum(bool(r["target"]["tasks"]) for r in kept),
             "rejected": why, **{k: len(v) for k, v in splits.items()}}
    (out / "stats.json").write_text(json.dumps(stats, indent=1))
    print("stats", json.dumps(stats), flush=True)


if __name__ == "__main__":
    main()

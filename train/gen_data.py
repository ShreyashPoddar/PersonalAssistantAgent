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
    ("a classmate or teacher reminds the owner of an assignment / lab record / file submission with a deadline", "received burst", 4),
    ("a friend asks the owner for a favour (send notes, bring something, share a file, recharge, book a ticket)", "received burst", 5),
    ("the owner promises to do something in their own message (kal bhej dunga, will call you by 5)", "sent burst", 3),
    ("a family member (papa, mummy, bhaiya, didi, dadi) asks the owner to do an errand", "received burst call", 3),
    ("a project group splits work and @mentions or names the owner for a part", "received burst", 2),
    ("a project group assigns work to someone else, NOT the owner", "received burst", 2),
    ("a hackathon / fest / workshop registration post, sometimes for the owner's batch, sometimes not", "received", 2),
    ("an internship or job post that names eligible batches (sometimes includes the owner's batch, sometimes not)", "received", 3),
    ("a job-updates channel (chat_name like 'X - Jobs & Internships Updates') posts an opening in its usual format "
     "(Company name / Role / Batch Eligible / Apply Link) that INCLUDES the owner's batch: a task 'Apply for <role> at "
     "<company>' even though it is posted to everyone", "received", 4),
    ("an internship / job / placement post that @mentions the owner by name but is ONLY for a different batch year than the owner's (not a task)", "received", 2),
    ("someone in a random or unrelated group @mentions the owner with a hackathon / event registration link ('@name register for this'), not from the owner's team (not a task)", "received", 3),
    ("an advertisement, sale, cashback or promotional message", "received", 3),
    ("a bank / UPI / OTP / delivery / app notification style message", "received", 3),
    ("a company / travel / shopping / delivery account asks the owner to confirm a booking, rate a trip, give feedback, "
     "view a receipt, renew a contract or be available for a delivery (not a task: chat_name is the company)", "received", 4),
    ("an announcement to the whole class or group with no personal action (holiday, room change, results out)", "received", 2),
    ("casual chat, jokes, memes, or a vague suggestion without commitment (call pe baat karte hai, kabhi milte hai)", "received sent burst", 3),
    ("a reply showing one of the owner's OPEN tasks is now finished (ho gaya, bhej diya, done bro, mil gaya thanks)", "received sent burst", 2),
    ("the deadline or details of one of the owner's OPEN tasks change (4 baje tak bhi chalega, ab monday ko)", "received burst", 2),
    ("a bare acceptance like 'haan kar dunga' / 'ok' with nothing asked before it", "sent", 1),
    ("the owner accepts a request that is in the earlier lines (haan kar dunga, ok will do)", "sent burst", 2),
    ("a task spread over several short lines: the work in one line, the day in another, the time in a third", "burst", 6),
    ("a phone call where the owner agrees to do something or is asked to do something by a time", "call", 2),
    ("a phone call with only small talk / catching up, nothing to do", "call", 1),
    ("someone else's own plan or reminder that does not involve the owner (main kal jaa raha hu)", "received burst", 2),
    ("a money request or promise (UPI kar dena, 500 rupay bhej dena, paise wapas kab)", "received sent burst", 2),
    ("a meeting / class / viva / interview scheduled with the owner", "received burst", 2),
    ("something already done, said in past tense (I already submitted it, bhej diya tha)", "sent received", 1),
    ("two unrelated tasks in one message (report bhejna + fees bharna)", "received sent", 3),
    ("the owner promises two different things in one message, often joined by 'and' / 'aur' / 'phir', each with its own time (I'll call you by 140 and send the ppt at 315)", "sent", 3),
    ("someone asks the owner for two or three separate things in one message (notes bhej de aur kal ka lab bhi le aana)", "received burst", 2),
    ("a hackathon / event registration link with an @mention to the owner in a random, unrelated or large group (not a task)", "received", 3),
    ("someone @mentions the owner in a group asking them to confirm a plan, time or detail in a few words ('@name 10 final na ??', 'kal pakka?', 'venue confirm kar') (a task: confirm it with that person)", "received", 3),
    ("the owner's short reply ('Hn bhai', 'haan', 'done', 'yes final') answering one of the owner's OPEN 'confirm …' to-dos, so it is finished", "sent", 3),
    # v6: categories from a month of real-use faults (docs/build/TASK_PIPELINE_V2.md)
    ("a parent, sibling or friend gives advice, care or wishes, often in Hindi (so jao, health ka dhyan rakho, khana kha "
     "lena, padh lo, tension mat lo, all the best, take care) - not a task", "received burst", 4),
    ("a request that uses the owner's own name or nickname in the message ('<owner> bhai notes bhej de', '@<owner> apni "
     "ppt bhejo', '<owner> tu kal form bhar dena'): a task for the owner, titled with the person who asked, never the "
     "owner's name", "received burst", 7),
    ("money in a flat / trip group: someone tells the owner to pay or add a split (a task) OR asks others to pay the owner "
     "back (not the owner's task)", "received burst", 3),
    ("someone asks the owner to add or put a split in the expense app ('daal dena split', 'split daal do', 'cook ka split "
     "add kar') or to pay a shared bill - a task", "received burst", 3),
    ("someone asks the owner to pay for them or do something for them, sending a QR, link or details first ('UPI nahi "
     "chal raha, mai QR bhejti hu, tum kar do', 'link bhej raha hu fill kar dena') - one task", "burst", 4),
    ("someone asks the owner to send information they need ('send me the details', 'subdomain ka naam bata', 'apna reg "
     "no bhej') - a task", "received burst", 3),
    ("a request that arrives in pieces: first only the need or problem ('UPI se payment nahi ho raha', 'form ka link aaya "
     "hai'), then the ask or the push ('tu kar de please', 'jaldi kar expire ho jayega') - one task", "burst", 7),
    ("the owner thinks aloud or asks a question about their own work ('case study aaj submit karu ya kal?', 'kya karna "
     "hai isme') - not a task", "sent burst", 2),
    ("someone sends a file, photo, link or receipt, or says they already sent something, with no ask - not a task", "received burst", 2),
    ("a short, informal Hinglish request with no deadline ('topic bhej de', 'report bhejo', 'certificate send krna', "
     "'kindly give me a report', 'send me the details') - a task even without a time", "received burst", 4),
    ("someone promises the OWNER they will do something themselves ('will draft it on Monday', 'main check karke "
     "batati hu', 'I'll send it tonight', 'kal tak de dunga') - not the owner's task", "received burst", 4),
    ("someone asks the owner whether they should do something of their own ('should I upload it?', 'kya mai bhi "
     "apply karu?') - not the owner's task", "received", 2),
    ("a question to the owner that only asks for information or opinion ('kahan busy ho?', 'pehle kuch karna padta hai "
     "kya?', 'game ke liye aa raha?') - not a task", "received burst", 3),
]
LANGS = [("Hinglish (Roman-script Hindi mixed with English, as Indian students really text)", 6),
         ("English with Indian texting style", 3), ("Hindi in Devanagari script", 3),
         ("Hindi in Roman script with almost no English words, the way parents and relatives text (beta, kar lena, "
          "bhej dena, aa jana)", 4),
         ("very casual Hinglish with typos, no punctuation, short forms (kl, h, nhi, pls, bhai, krde, bhejde)", 4)]
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
- Not a task: someone ELSE promising to do something themselves. "main/mai … kar dunga / bhej deta hu / batati hu",
  "I'll draft it", "kal tak de dunga" in a message the owner RECEIVED is the sender's own promise, not the owner's
  (only lines from 'You' or a SENT message are the owner's promises). Also not a task: someone asking the owner whether
  THEY should do something ("should I upload it?", "kya mai bhi apply karu?").
- Not a task: advice, care or wishes ("so jao", "health ka dhyan rakho", "khana kha lena", "all the best"); questions that
  only ask for information; files or receipts someone sent; anything from a company, shop, bank, travel or delivery
  account (bookings, ratings, feedback, OTPs, receipts, offers).
- The owner's name or nickname inside a message means it is addressed to the owner. Never put the owner's name in a
  title: name the person who asked ("Send ppt to Riya", not "Send ppt to <owner's name>").
- "haan kar dunga"/"ok will do" accepts the request above it; with nothing above it is NOT a task.
- An @mention asking the owner to confirm something ("10 final na ??") is a task: "Confirm … with <name>". The owner's
  short answer to it later ("Hn bhai", "haan final") marks that open task done.
- Internship/job posts: a task ("Apply for <role> at <company>") when open to the {year} batch or no batch is named —
  even when posted to everyone or by a job-updates channel (that is not an announcement); not a task when only other
  batches are eligible, even when they @mention the owner.
- Hackathon/event registration links forwarded in a random or unrelated group are not tasks, even with an @mention;
  only the owner's own team/organisers asking them to register, or the owner saying they will register, is a task.
- Use earlier lines only to fill in details (deadline, what "it" is, who); never create a task found only there —
  except when the new lines complete or push a request from the earlier lines ("tu kar de", "jaldi kar", "expire ho
  jayega"): then that request is a task.
- title: 2-7 English words starting with a verb; name the person involved using ONLY names from the messages.
- when: ALL time words for that task joined, even from different lines ("kal tak" + "5 baje se pehle" = "kal 5 baje se pehle"); else null. 140/315/1145 are clock times.
- One task per piece of work: two actions joined by "and"/"aur"/"phir" are TWO tasks (each with its own time), never
  one merged title. confidence 0-1. priority 0-3. registration true only for hackathon/event sign-ups.
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
    text_or_none = lambda v: str(v) if v not in (None, "", [], {}) else None
    ctx = {"outgoing": kind == "sent", "chat_name": text_or_none(s.get("chat_name")), "is_group": p["group"],
           "sender": None if kind in ("sent", "call") else text_or_none(s.get("sender")),
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


# capitalised words a title may use without them being names (data-v2 rejected ~100 good labels over these)
TITLE_WORDS = {"the", "and", "for", "lab", "ppt", "pdf", "monday", "tuesday", "wednesday", "thursday", "friday",
               "saturday", "sunday", "january", "february", "march", "april", "may", "june", "july", "august",
               "september", "october", "november", "december", "python", "java", "internship", "hackathon", "project",
               "record", "student", "experimental", "independence", "diwali", "holi", "upi", "excel", "word"}


def no_task_category(cat: str) -> bool:
    """Categories written to have NO task for the owner. The teacher's only check is agreeing with itself, and on
    data-v9 it consistently labelled 288/385 "someone promises the OWNER" scenarios as owner tasks; drop those."""
    return bool(re.search(r"[-(] ?not (a|the owner's) task\b", cat)) and not re.search(r"\bOR\b|[-(] ?a task\b", cat)


def sane(label: dict, prompt: str, n_open: int) -> str:
    """'' if the label is usable, else why not."""
    owner_line = next((l for l in prompt.splitlines() if l.startswith("Owner: ")), "")
    owners = {w.lower() for w in re.findall(r"[A-Za-z]{3,}", owner_line.split(", batch")[0][len("Owner: "):]) if w.lower() != "or"}
    for t in label.get("tasks") or []:
        title = str(t.get("title") or "")
        if not 2 <= len(title.split()) <= 9:
            return "title length"
        # the owner's own name never goes in a title (v9/v10 on the real set: 2-5 such titles)
        if owners & {w.lower().removesuffix("'s") for w in re.findall(r"[A-Za-z']+", title)}:
            return "owner name in title"
        # names in a title must come from the input (the rule small models break most)
        for w in re.findall(r"\b[A-Z][a-z]{2,}\b", title)[1:]:
            if w.lower() not in prompt.lower() and w.lower() not in TITLE_WORDS:
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
    ap.add_argument("--shard", type=int, default=0, help=argparse.SUPPRESS)    # set by fan_out for its workers
    ap.add_argument("--shards", type=int, default=0, help=argparse.SUPPRESS)
    a = ap.parse_args()
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    devs = [d for d in os.environ.get("CUDA_VISIBLE_DEVICES", "").split(",") if d.strip()]
    if not a.shards and len(devs) >= 2 * a.tp:   # a job given several cards: one teacher per card, half the work each
        return fan_out(a, out, devs)
    hold = hold_gpu()   # first thing: claim the card before the slow imports, so nobody else takes it meanwhile
    labeled = out / (f"labeled-s{a.shard}.jsonl" if a.shards else "labeled.jsonl")
    done = done_ids(out)
    todo = [i for i in range(a.n) if i not in done and (not a.shards or i % a.shards == a.shard)]
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
        def chat(convs, sp):
            """llm.chat that can't take the whole job down: contents forced to str; a failing batch is retried one
            conversation at a time and the bad ones come back as None (data-v7 crashed on a vLLM tokenizer TypeError)."""
            convs = [[{"role": m["role"], "content": str(m["content"])} for m in c] for c in convs]
            try:
                return llm.chat(convs, sp, use_tqdm=False, **kw)
            except (TypeError, ValueError) as e:
                print(f"batch failed ({type(e).__name__}: {e}); retrying one by one", flush=True)
                outs = []
                for c in convs:
                    try:
                        outs.append(llm.chat([c], sp, use_tqdm=False, **kw)[0])
                    except (TypeError, ValueError) as e1:
                        print(f"dropped one conversation ({type(e1).__name__}): {repr(c)[:300]}", flush=True)
                        outs.append(None)
                return outs

        for c0 in range(0, len(todo), a.chunk):
            plans = [plan(i, a.seed) for i in todo[c0:c0 + a.chunk]]
            outs = chat([[{"role": "user", "content": gen_prompt(p)}] for p in plans], gen_sp)
            recs = [to_record(p, first_json(o.outputs[0].text)) if o is not None else None for p, o in zip(plans, outs)]
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
                    if g is None or s is None:
                        f.write(json.dumps({"idx": p["idx"], "keep": False, "why": "teacher call failed"}) + "\n")
                        continue
                    gold = first_json(g.outputs[0].text)
                    alts = [first_json(o.text) for o in s.outputs]
                    why = ("unparsable" if not isinstance(gold, dict) or not all(isinstance(x, dict) for x in alts) else
                           "teacher disagrees" if not all(agree(gold, x) for x in alts) else
                           "category says no task" if no_task_category(p["category"]) and gold.get("tasks") else
                           sane(gold, conv[1]["content"], len(r["ctx"]["open_tasks"])))
                    row = {"idx": p["idx"], "keep": not why, "why": why, **r}
                    if not why:
                        row["target"] = json.loads(canonical_target(gold))
                    f.write(json.dumps(row, ensure_ascii=False) + "\n")
            print(f"chunk {c0 // a.chunk + 1}: {c0 + len(plans)}/{len(todo)}" + (f" (shard {a.shard})" if a.shards else ""),
                  flush=True)
    if not a.shards:
        finish(out)


def labeled_files(out: Path) -> list:
    return sorted(out.glob("labeled*.jsonl"))   # labeled.jsonl, or labeled-s0.jsonl, labeled-s1.jsonl, ... (fan_out)


def done_ids(out: Path) -> set:
    done = set()
    for f in labeled_files(out):
        for line in f.open(encoding="utf-8"):
            try:
                done.add(json.loads(line)["idx"])
            except Exception:
                pass
    return done


def fan_out(a, out: Path, devs: list) -> None:
    """One worker per card (or per --tp cards), each on every m-th scenario into its own labeled-s<i>.jsonl; then the
    usual split over all of them. Roughly m times faster. A pause (SIGUSR1 reaches every worker: same cwd) or a crash
    ends the job; a re-run continues each shard from its file."""
    import subprocess
    m = len(devs) // a.tp
    procs = []
    for i in range(m):
        env = {**os.environ, "CUDA_VISIBLE_DEVICES": ",".join(devs[i * a.tp:(i + 1) * a.tp])}
        procs.append(subprocess.Popen([sys.executable, __file__, *sys.argv[1:], "--shard", str(i), "--shards", str(m)],
                                      env=env))
    print(f"fan-out: {m} workers on cards {','.join(devs)}", flush=True)
    codes = [p.wait() for p in procs]
    print(f"workers exited {codes}", flush=True)
    if 75 in codes:
        os._exit(75)
    if any(codes):
        sys.exit(next(c for c in codes if c))
    finish(out)


def finish(out: Path) -> None:
    # split by a hash of the id, so re-runs and resumes always put a record in the same split
    rows = [json.loads(l) for f in labeled_files(out) for l in f.open(encoding="utf-8")]
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

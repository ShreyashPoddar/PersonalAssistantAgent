#!/usr/bin/env python3
"""One GPU budget for BOTH projects on the DGX (LocalAI + PAA, same account), applied by PAA's poller every poll.

The rules live in ONE place: LocalAI's queue.yaml `limits:` (branch dgx-queue of ShreyashPoddar/AIModel), editable
from anywhere on GitHub. LocalAI's poller writes them to ~/localai/queue.parsed.limits every poll; PAA reads that
file. If it is missing, DEFAULT_LIMITS (the same rules) apply:
    day   07:00-19:00 IST: at most 2 cards in total
    night 19:00-07:00 IST: at most 4 cards in total, always 1 card left idle for others, but never fewer than 2 ours

PAA's share inside that total (owner's rule, 2026-10-16): PAA is GUARANTEED 1 card day and night when it needs one,
and may use a 2nd at night if PAA needs it and the total allows (`limits.paa` / `limits.night.paa` = {min, max} override
these defaults). "Needs" = running or queued GPU work; a finished job that only holds a card counts toward the
guaranteed 1, never toward the 2nd. PAA publishes its use and demand in ~/paa/demand.json every poll.

LocalAI's gpu_policy.py honours the guarantee once it reads demand.json (it then reports `paa_reserved` in its
sched.json): it shrinks its own cap by PAA's share and pauses its newest run to give PAA its card. Until then PAA is
the yielding tenant (old behaviour): it launches only into what LocalAI leaves and pauses its own jobs when the total
is over. PAA never touches LocalAI's runs itself.

    python paa_policy.py <paa_root> [<limits.json>]
prints:  MODE <name> / ALLOW <n> / OURS <n> / PAA <n> / IDLE <n> / PREEMPT <job id>... / ERROR <msg>...
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, time, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

DEFAULT_LIMITS = {"max_gpus": 2,
                  "night": {"start": "19:00", "end": "07:00", "keep_free": 1, "max_gpus": 4, "tz": "Asia/Kolkata"}}
PAA_DAY, PAA_NIGHT = {"min": 1, "max": 1}, {"min": 1, "max": 2}     # PAA's share (owner's rule)
LOCALAI_LIMITS = Path(os.environ.get("HOME", "/")) / "localai" / "queue.parsed.limits"
LOCALAI_SCHED = Path(os.environ.get("HOME", "/")) / "localai" / "sched.json"
LOCALAI_RUNS = Path(os.environ.get("HOME", "/")) / "localai" / "runs"
DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
ALL = 99


@dataclass
class Job:
    id: str
    cards: list[int]
    started: float
    holding: bool       # its work is done; it only keeps the card -> paused first, and not resumed
    pausing: bool = False   # already asked to pause, still saving: its card frees up within minutes


# ---- the rules (same semantics as LocalAI's gpu_policy.py, so one `limits:` means the same thing to both) ----
def _t(x: str) -> time:
    return time(0, 0) if str(x).startswith("24") else time.fromisoformat(str(x))


def _in(now: datetime, start: str, end: str) -> bool:
    s, e, t = _t(start), _t(end), now.time()
    if str(end).startswith("24"):
        return t >= s
    return (t >= s or t < e) if s > e else (s <= t < e)


def in_window(now: datetime, w: dict) -> bool:
    start, end = w.get("start", "00:00"), w.get("end", "24:00")
    if not _in(now, start, end):
        return False
    days = w.get("days")
    if not days:
        return True
    s, e = _t(start), _t(end)
    day = (now - timedelta(days=1)) if s > e and not str(end).startswith("24") and now.time() < e else now
    return DAYS[day.weekday()] in [str(d).lower()[:3] for d in days]


def _n(v, default: int) -> int:
    if v is None:
        return default
    return ALL if str(v).lower() == "all" else int(v)


def _share(d: dict | None, default: dict) -> tuple[int, int]:
    d = d if isinstance(d, dict) else {}
    lo, hi = _n(d.get("min"), default["min"]), _n(d.get("max"), default["max"])
    return lo, max(lo, hi)


def active_rule(now: datetime, limits: dict) -> dict:
    base = _n(limits.get("max_gpus"), 2)
    floor = _n(limits.get("min_gpus"), base)
    hold = bool(limits.get("hold"))
    for i, w in enumerate(limits.get("windows") or []):
        if in_window(now, w):
            cap = _n(w.get("max_gpus"), base)
            pmin, pmax = _share(w.get("paa"), PAA_NIGHT)
            return {"mode": str(w.get("name") or f"window{i + 1}"), "cap": cap, "keep_free": _n(w.get("keep_free"), 0),
                    "floor": min(_n(w.get("min_gpus"), floor), cap), "hold": hold, "paa_min": pmin, "paa_max": pmax}
    night = limits.get("night") or {}
    if night and in_window(now, {"start": "21:00", "end": "07:00", **night}):
        cap = _n(night.get("max_gpus"), ALL)
        pmin, pmax = _share(night.get("paa"), PAA_NIGHT)
        return {"mode": "night", "cap": cap, "keep_free": _n(night.get("keep_free"), 1),
                "floor": min(_n(night.get("min_gpus"), floor), cap), "hold": hold, "paa_min": pmin, "paa_max": pmax}
    pmin, pmax = _share(limits.get("paa"), PAA_DAY)
    return {"mode": "day", "cap": base, "keep_free": _n(limits.get("keep_free"), 0), "floor": min(floor, base),
            "hold": hold, "paa_min": pmin, "paa_max": pmax}


def paa_reserved(rule: dict, using: int, wanting: int) -> int:
    """Cards inside the total that belong to PAA now: what it uses, plus its guaranteed share if it has work for it.
    Same formula in LocalAI's gpu_policy.py, so both agree on the split."""
    pmin, pmax = rule.get("paa_min", 1), rule.get("paa_max", 1)
    return min(pmax, max(using, min(pmin, using + wanting)))


def decide(rule: dict, mem: dict[int, float], ours: set[int], paa: list[Job], wanting: int = 0,
           localai_honours: bool = False) -> tuple[int, list[str]]:
    """-> (cards PAA may launch now, PAA job ids to pause). `ours` = every card this account uses (both projects);
    `wanting` = PAA GPU jobs ready to start (queued with inputs done, or paused); `localai_honours` = LocalAI's
    policy reserves PAA's guaranteed share (it reports paa_reserved)."""
    cap, keep_free, floor = rule["cap"], rule["keep_free"], rule["floor"]
    pmin, pmax = rule.get("paa_min", 1), rule.get("paa_max", 1)
    used = len(ours)
    idle = sum(1 for i, m in mem.items() if m < 1024 and i not in ours)
    pausing = sum(len(j.cards) for j in paa if j.pausing)
    active = [j for j in paa if not j.pausing]
    using = sum(len(j.cards) for j in active)
    order = sorted(active, key=lambda j: (not j.holding, -j.started))   # holding first, then newest
    preempt: list[str] = []

    if not localai_honours:   # LocalAI doesn't know about PAA yet: PAA only takes what is left over
        to_floor = max(0, floor - used)
        if keep_free:   # up to the floor any idle card; beyond it only cards above keep_free
            allow = min(idle, to_floor + max(0, idle - to_floor - keep_free), max(0, cap - used))
        else:
            allow = min(idle, max(0, cap - used))
        allow = min(allow, max(0, pmax - using))
        excess = used - pausing - cap
        if excess > 0:
            for j in order:
                if excess <= 0:
                    break
                preempt.append(j.id)
                excess -= max(1, len(j.cards))
        elif keep_free and idle < keep_free and used - pausing > floor and order and not pausing:
            preempt.append(order[0].id)
        return (0 if rule.get("hold") else allow), preempt

    # LocalAI reserves PAA's share: PAA takes its guaranteed cards even when the total is full (LocalAI pauses its
    # newest run to make room), extra cards only when the total has room; never the card(s) kept idle for others.
    room = max(cap - used, pmin - using)
    allow = max(0, min(max(0, idle - keep_free), pmax - using, room))
    if rule.get("hold"):
        allow = 0
    holding = [j for j in order if j.holding]
    over_min = using - pmin
    for j in holding:                               # a held card above the guaranteed one is not "need": give it back
        if over_min <= 0:
            break
        preempt.append(j.id)
        over_min -= max(1, len(j.cards))
    excess = used - pausing - cap - sum(max(1, len(j.cards)) for j in active if j.id in preempt)
    give = using - pmin - sum(max(1, len(j.cards)) for j in active if j.id in preempt)
    for j in order:                                 # over the total: PAA gives back only what is above its share
        if excess <= 0 or give <= 0:
            break
        if j.id in preempt:
            continue
        preempt.append(j.id)
        excess -= max(1, len(j.cards))
        give -= max(1, len(j.cards))
    return allow, preempt


# ---- what is on the box right now ----
def _gpu_table() -> tuple[dict[int, float], dict[str, int]]:
    out = subprocess.run(["nvidia-smi", "--query-gpu=index,uuid,memory.used", "--format=csv,noheader,nounits"],
                         capture_output=True, text=True, timeout=30).stdout
    mem, by_uuid = {}, {}
    for ln in out.strip().splitlines():
        p = [x.strip() for x in ln.split(",")]
        if len(p) == 3:
            mem[int(p[0])] = float(p[2]); by_uuid[p[1]] = int(p[0])
    return mem, by_uuid


def _account_cards(by_uuid: dict[str, int]) -> set[int]:
    """Cards with any compute process owned by this account (LocalAI runs, autoeval, PAA jobs, holds)."""
    out = subprocess.run(["nvidia-smi", "--query-compute-apps=gpu_uuid,pid", "--format=csv,noheader"],
                         capture_output=True, text=True, timeout=30).stdout
    me, cards = os.getuid(), set()
    for ln in out.strip().splitlines():
        p = [x.strip() for x in ln.split(",")]
        if len(p) == 2 and p[1].isdigit():
            try:
                if os.stat(f"/proc/{p[1]}").st_uid == me and p[0] in by_uuid:
                    cards.add(by_uuid[p[0]])
            except FileNotFoundError:
                pass
    return cards


def _cards_from(run_sh: Path) -> list[int]:
    m = re.search(r"CUDA_VISIBLE_DEVICES=([\d,]+)", run_sh.read_text(errors="replace"))
    return [int(x) for x in m.group(1).split(",") if x] if m else []


def _running(d: Path) -> bool:
    log = d / "run.log"
    return (d / "run.sh").exists() and not (log.exists() and re.search(r"^exit=", log.read_text(errors="replace"), re.M))


def paa_jobs(root: Path) -> list[Job]:
    jobs = []
    for d in sorted((root / "jobs").glob("*/")):
        if not _running(d):
            continue
        cards = _cards_from(d / "run.sh")
        if not cards:
            continue                                # CPU job (export): not part of the GPU budget
        log = (d / "run.log").read_text(errors="replace") if (d / "run.log").exists() else ""
        jobs.append(Job(d.name, cards, (d / "run.sh").stat().st_mtime, "holding the card for PAA" in log,
                        (d / ".preempting").exists()))
    return jobs


def localai_cards() -> set[int]:
    """LocalAI runs that are starting up (cloning) hold a card before they allocate memory: count them too."""
    out: set[int] = set()
    for d in sorted(LOCALAI_RUNS.glob("*/")) if LOCALAI_RUNS.exists() else []:
        if _running(d):
            out.update(_cards_from(d / "run.sh"))
    return out


def load_limits(path: Path | None) -> tuple[dict, str]:
    p = path or LOCALAI_LIMITS
    try:
        lim = json.loads(p.read_text() or "{}")
        return (lim if isinstance(lim, dict) and lim else DEFAULT_LIMITS), "localai" if lim else "default"
    except (OSError, ValueError):
        return DEFAULT_LIMITS, "default"


def localai_honours_share() -> bool:
    """LocalAI's policy reports paa_reserved in its sched.json once it reserves PAA's share (and is alive)."""
    try:
        s = json.loads(LOCALAI_SCHED.read_text())
        import time as _t
        return "paa_reserved" in s and _t.time() - float(s.get("t", 0)) < 1800
    except (OSError, ValueError):
        return False


def main():
    root = Path(sys.argv[1])
    wanting = int(os.environ.get("PAA_WANTING", "0") or 0)
    limits, src = load_limits(Path(sys.argv[2]) if len(sys.argv) > 2 else None)
    tz = limits.get("tz") or (limits.get("night") or {}).get("tz") or "Asia/Kolkata"
    try:
        now = datetime.now(ZoneInfo(tz))
    except Exception:
        now = datetime.now(ZoneInfo("Asia/Kolkata"))
    try:
        mem, by_uuid = _gpu_table()
        jobs = paa_jobs(root)
        ours = _account_cards(by_uuid) | localai_cards() | {c for j in jobs for c in j.cards}
        rule = active_rule(now, limits)
        honours = localai_honours_share()
        allow, preempt = decide(rule, mem, ours, jobs, wanting, honours)
    except Exception as e:   # unsure what the box looks like: launch nothing, pause nothing
        print("MODE error"); print("ALLOW 0"); print(f"ERROR {type(e).__name__}: {e}")
        return
    idle = sum(1 for i, m in mem.items() if m < 1024 and i not in ours)
    using = sum(len(j.cards) for j in jobs if not j.pausing)
    # what LocalAI reads to reserve PAA's share (same formula on both sides)
    (root / "demand.json").write_text(json.dumps({"using": using, "wanting": wanting,
                                                  "reserved": paa_reserved(rule, using, wanting), "t": int(now.timestamp())}))
    print(f"MODE {rule['mode']} (cap {rule['cap']}, keep {rule['keep_free']} idle, PAA {rule['paa_min']}-{rule['paa_max']}"
          f"{'' if honours else ' not yet reserved by LocalAI'}, rules: {src})")
    print(f"ALLOW {allow}")
    print(f"OURS {len(ours)}")
    print(f"PAA {sum(len(j.cards) for j in jobs)}")
    print(f"IDLE {idle}")
    for j in preempt:
        print(f"PREEMPT {j}")
    (root / "sched.json").write_text(json.dumps({"mode": rule["mode"], "rule": rule, "allow": allow, "ours": len(ours),
                                                 "paa": sum(len(j.cards) for j in jobs), "idle": idle, "wanting": wanting,
                                                 "localai_reserves_paa": honours,
                                                 "rules_from": src, "t": int(now.timestamp())}))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""One GPU budget for BOTH projects on the DGX (LocalAI + PAA, same account), applied by PAA's poller every poll.

The rules live in ONE place: LocalAI's queue.yaml `limits:` (branch dgx-queue of ShreyashPoddar/AIModel), editable
from anywhere on GitHub. LocalAI's poller writes them to ~/localai/queue.parsed.limits every poll; PAA reads that
file. If it is missing, DEFAULT_LIMITS (the same rules) apply:
    day   07:00-19:00 IST: at most 2 cards in total
    night 19:00-07:00 IST: at most 4 cards in total, always 1 card left idle for others, but never fewer than 2 ours

LocalAI's gpu_policy.py counts only LocalAI runs (it sees PAA's cards as another user's). So PAA is the yielding
tenant: it counts EVERY card this account uses (both projects, incl. autoeval), launches only into what is left,
and pauses its own jobs (finished-and-holding first, then newest) when the total goes over. It never touches LocalAI.

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
LOCALAI_LIMITS = Path(os.environ.get("HOME", "/")) / "localai" / "queue.parsed.limits"
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


def active_rule(now: datetime, limits: dict) -> dict:
    base = _n(limits.get("max_gpus"), 2)
    floor = _n(limits.get("min_gpus"), base)
    for i, w in enumerate(limits.get("windows") or []):
        if in_window(now, w):
            cap = _n(w.get("max_gpus"), base)
            return {"mode": str(w.get("name") or f"window{i + 1}"), "cap": cap, "keep_free": _n(w.get("keep_free"), 0),
                    "floor": min(_n(w.get("min_gpus"), floor), cap), "hold": bool(limits.get("hold"))}
    night = limits.get("night") or {}
    if night and in_window(now, {"start": "21:00", "end": "07:00", **night}):
        cap = _n(night.get("max_gpus"), ALL)
        return {"mode": "night", "cap": cap, "keep_free": _n(night.get("keep_free"), 1),
                "floor": min(_n(night.get("min_gpus"), floor), cap), "hold": bool(limits.get("hold"))}
    return {"mode": "day", "cap": base, "keep_free": _n(limits.get("keep_free"), 0), "floor": min(floor, base),
            "hold": bool(limits.get("hold"))}


def decide(rule: dict, mem: dict[int, float], ours: set[int], paa: list[Job]) -> tuple[int, list[str]]:
    """-> (cards PAA may launch now, PAA job ids to pause). `ours` = every card this account uses (both projects)."""
    cap, keep_free, floor = rule["cap"], rule["keep_free"], rule["floor"]
    used = len(ours)
    idle = sum(1 for i, m in mem.items() if m < 1024 and i not in ours)
    to_floor = max(0, floor - used)
    if keep_free:   # up to the floor any idle card; beyond it only cards above keep_free
        allow = min(idle, to_floor + max(0, idle - to_floor - keep_free), max(0, cap - used))
    else:
        allow = min(idle, max(0, cap - used))
    if rule.get("hold"):
        allow = 0
    pausing = sum(len(j.cards) for j in paa if j.pausing)
    order = sorted((j for j in paa if not j.pausing), key=lambda j: (not j.holding, -j.started))   # holding, newest
    preempt: list[str] = []
    excess = used - pausing - cap
    if excess > 0:                                   # over the total: PAA gives cards back
        for j in order:
            if excess <= 0:
                break
            preempt.append(j.id)
            excess -= max(1, len(j.cards))
    elif keep_free and idle < keep_free and used - pausing > floor and order and not pausing:
        preempt.append(order[0].id)                  # someone took the last free card: give one back
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


def main():
    root = Path(sys.argv[1])
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
        allow, preempt = decide(rule, mem, ours, jobs)
    except Exception as e:   # unsure what the box looks like: launch nothing, pause nothing
        print("MODE error"); print("ALLOW 0"); print(f"ERROR {type(e).__name__}: {e}")
        return
    idle = sum(1 for i, m in mem.items() if m < 1024 and i not in ours)
    print(f"MODE {rule['mode']} (cap {rule['cap']}, keep {rule['keep_free']} idle, rules: {src})")
    print(f"ALLOW {allow}")
    print(f"OURS {len(ours)}")
    print(f"PAA {sum(len(j.cards) for j in jobs)}")
    print(f"IDLE {idle}")
    for j in preempt:
        print(f"PREEMPT {j}")
    (root / "sched.json").write_text(json.dumps({"mode": rule["mode"], "rule": rule, "allow": allow, "ours": len(ours),
                                                 "paa": sum(len(j.cards) for j in jobs), "idle": idle,
                                                 "rules_from": src, "t": int(now.timestamp())}))


if __name__ == "__main__":
    main()

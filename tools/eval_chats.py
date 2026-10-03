"""Golden-conversation eval for PAA's chat pipeline.

Sends the messages in tools/scenarios.json to a debug build through the DEBUG_INJECT broadcast,
waits for PAA's verdicts in its detection log, and checks them against the expectations.

    python tools/eval_chats.py --serial emulator-5554 [--only burst_then_change]

Each run uses fresh chat names (suffix = run id) so earlier runs never interfere.
"""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import time

ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb")
HERE = os.path.dirname(os.path.abspath(__file__))
DUE = re.compile(r"(?:due|→) \w{3} (\d{1,2}) (\w{3}) (\d{1,2}):(\d{2}) (AM|PM)")
MONTHS = {m: i for i, m in enumerate(["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"], 1)}


def adb(serial, *args):
    return subprocess.run([ADB, "-s", serial, *args], stdin=subprocess.DEVNULL, capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def log_entries(serial):
    # The log is encrypted on the phone; debug builds write a plaintext copy on request
    adb(serial, "shell", "am", "broadcast", "-a", "com.paa.assistant.DEBUG_INJECT", "-p", "com.paa.assistant",
        "--ez", "dumplog", "true")
    time.sleep(1)
    raw = adb(serial, "shell", "run-as", "com.paa.assistant", "cat", "files/detection_log_debug.json")
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return []


def inject(serial, chat, step, group, sender):
    q = lambda s: "'" + s.replace("'", "") + "'"
    args = ["shell", "am", "broadcast", "-a", "com.paa.assistant.DEBUG_INJECT", "-p", "com.paa.assistant",
            "--es", "text", q(step["text"]), "--es", "chat", q(chat)]
    if group:
        args += ["--ez", "group", "true"]
    if sender and not step.get("out"):
        args += ["--es", "sender", q(sender)]
    if step.get("out"):
        args += ["--ez", "out", "true"]
    adb(serial, *args)


def is_result(e):
    return not any(k in e["r"] for k in ("⏳", "🧠"))


def wait_results(serial, chat, since_ms, timeout=420):
    """Waits until the chat has a final verdict newer than since_ms, then 5 s more for siblings."""
    end = time.time() + timeout
    while time.time() < end:
        found = [e for e in log_entries(serial) if chat in e["s"] and e["t"] > since_ms and is_result(e)]
        if found:
            time.sleep(5)
            return [e for e in log_entries(serial) if chat in e["s"] and e["t"] > since_ms and is_result(e)]
        time.sleep(5)
    return []


def run_chat(serial, chat, steps, group, sender):
    results, since = [], int(time.time() * 1000)
    for step in steps:
        if step.get("wait"):
            results += wait_results(serial, chat, since)
            since = int(time.time() * 1000)
        else:
            inject(serial, chat, step, group, sender)
            time.sleep(3)
    return results


def due_of(text):
    m = DUE.search(text)
    if not m:
        return None
    day, mon, h, mi, ampm = int(m[1]), MONTHS[m[2]], int(m[3]) % 12, int(m[4]), m[5]
    if ampm == "PM":
        h += 12
    year = dt.date.today().year + (1 if mon < dt.date.today().month - 6 else 0)
    return dt.datetime(year, mon, day, h, mi)


def check(expects, results):
    """Each expectation must be met by a distinct result line. Returns a list of failure strings."""
    fails, used = [], set()
    for ex in expects:
        if "no" in ex:
            bad = [e["r"] for e in results if ex["no"] in e["r"]]
            if bad:
                fails.append(f"unexpected '{ex['no']}': {bad[0][:90]}")
            continue
        ok = False
        for i, e in enumerate(results):
            r = e["r"]
            if i in used or ex["contains"] not in r:
                continue
            if "keyword" in ex and ex["keyword"].lower() not in r.lower():
                continue
            due = due_of(r)
            if due is None:
                if any(k in ex for k in ("due_day_offset", "due_hour", "due_minute")):
                    continue
                used.add(i)
                ok = True
                break
            if "due_day_offset" in ex and (due.date() - dt.date.today()).days != ex["due_day_offset"]:
                continue
            if "due_hour" in ex and due.hour != ex["due_hour"]:
                continue
            if "due_minute" in ex and due.minute != ex["due_minute"]:
                continue
            used.add(i)
            ok = True
            break
        if not ok:
            fails.append(f"missing {ex} in {[e['r'][:80] for e in results] or 'no results'}")
    return fails


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--only")
    a = ap.parse_args()
    scenarios = json.load(open(os.path.join(HERE, "scenarios.json"), encoding="utf-8"))
    if a.only:
        scenarios = [s for s in scenarios if s["id"] == a.only]
    run_id = dt.datetime.now().strftime("%H%M")
    adb(a.serial, "shell", "am", "broadcast", "-a", "com.paa.assistant.DEBUG_INJECT", "-p", "com.paa.assistant",
        "--ez", "reset", "true")
    time.sleep(3)
    rows = []
    for s in scenarios:
        t0 = time.time()
        chat = f"{s['chat']} E{run_id}"
        results = run_chat(a.serial, chat, s["steps"], s.get("group", False), s.get("sender"))
        fails = check(s["expect"], results)
        if "also_chat" in s:
            other = f"{s['also_chat']['chat']} E{run_id}"
            more = run_chat(a.serial, other, s["also_chat"]["steps"], False, None)
            fails += check(s["expect_also"], more)
        rows.append((s["id"], not fails, int(time.time() - t0), fails))
        print(f"{'PASS' if not fails else 'FAIL'}  {s['id']}  ({rows[-1][2]}s)", flush=True)
        for f in fails:
            print("      " + f, flush=True)
    passed = sum(1 for r in rows if r[1])
    print(f"\n{passed}/{len(rows)} passed")
    sys.exit(0 if passed == len(rows) else 1)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
    main()

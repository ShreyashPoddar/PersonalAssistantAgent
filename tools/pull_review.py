"""Pull a backup from the phone and write a review of what PAA did since the last review (laptop only).

    python tools/pull_review.py --serial <ip:port> [--private ../PAA-private]

Writes <private>/backup-<date>.json (full dump: tasks, chat lines, log) and <private>/review-<date>.txt: every real
task created since the last review with its source message and the AI's reason, and the model's "no task"
decisions from people's chats — the material for labelling new private test cases (build_realset.py). Test-tagged
chats (" E1234") are left out. Nothing here goes into git or to the DGX; the plaintext copy on the phone is deleted.
"""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb")
TAG = re.compile(r" E\d{4}\b")


def adb(serial, *args, binary=False):
    r = subprocess.run([ADB, "-s", serial, *args], stdin=subprocess.DEVNULL, capture_output=True)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--private", default=str(Path(__file__).resolve().parent.parent.parent / "PAA-private"))
    a = ap.parse_args()
    out = Path(a.private)
    out.mkdir(parents=True, exist_ok=True)
    adb(a.serial, "shell", "am", "broadcast", "-a", "com.paa.assistant.DEBUG_INJECT", "-p", "com.paa.assistant", "--ez", "dumpall", "true")
    time.sleep(4)
    raw = adb(a.serial, "exec-out", "run-as", "com.paa.assistant", "cat", "files/dumpall_debug.json", binary=True)
    adb(a.serial, "shell", "run-as", "com.paa.assistant", "rm", "files/dumpall_debug.json")
    d = json.loads(raw.decode("utf-8"))
    today = dt.date.today().isoformat()
    (out / f"backup-{today}.json").write_bytes(raw)

    state = out / "review_state.json"
    last_id = json.loads(state.read_text())["last_task_id"] if state.exists() else 0
    since_ms = json.loads(state.read_text()).get("at", 0) if state.exists() else 0
    f = lambda x: dt.datetime.fromtimestamp(int(x) / 1000).strftime("%a %m-%d %H:%M") if x not in (None, "null") else "-"
    lines = [f"Review {today} — tasks after id {last_id}", ""]
    tasks = [t for t in d["tasks"] if int(t["id"]) > last_id and not TAG.search(t["sourceApp"])]
    for t in tasks:
        lines += [f"#{t['id']} [{t['status'][0]}] created {f(t['createdAt'])} due {f(t['dueTimestamp'])} {t['sourceApp']}",
                  f"   title:   {t['title']}",
                  f"   message: {(t['sourceMessage'] or '').replace(chr(10), ' | ')[:300]}",
                  f"   reason:  {(t['aiReason'] or '')[:160]}", ""]
    lines += ["", "Model said 'no task' (people's chats):", ""]
    for e in reversed(d["log"]):
        if e["t"] <= since_ms or TAG.search(e["s"]) or e["s"] == "system":
            continue
        if e["r"].startswith("no task"):
            lines.append(f"{dt.datetime.fromtimestamp(e['t'] / 1000):%m-%d %H:%M} {e['s'][:30]} | {e['m'][:90]} => {e['r'][:80]}")
    (out / f"review-{today}.txt").write_text("\n".join(lines), encoding="utf-8")
    max_id = max([int(t["id"]) for t in d["tasks"]] + [last_id])
    state.write_text(json.dumps({"last_task_id": max_id, "at": int(time.time() * 1000)}))
    print(f"{len(tasks)} new tasks; review → {out / f'review-{today}.txt'}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]
    main()

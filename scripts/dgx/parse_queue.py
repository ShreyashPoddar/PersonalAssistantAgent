#!/usr/bin/env python3
"""queue.yaml -> validated job lines for poll.sh, plus NEW control actions on stdout.

    parse_queue.py <queue.yaml> <control_seen.json> <jobs_out>

Job line fields (separated by \\x1f so empty fields survive bash `read`): id step ref out after gpus resume args.
Anything that doesn't validate is reported as a BAD action instead of run: ids/names are [A-Za-z0-9_-], steps are
whitelisted, args are plain --flag/value tokens (no shell, no paths outside the job's own artifacts).
"""
import json
import os
import re
import shlex
import sys

import yaml

STEPS = {"gen_data", "train", "eval", "export"}
NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$")
REF = re.compile(r"^[0-9a-f]{7,40}$")
TOKEN = re.compile(r"^[A-Za-z0-9_.:=,+/-]+$")
RESERVED = ("--out", "--data", "--model")   # set by launch.sh from out/after; a job can't point elsewhere


def job_line(j):
    if not isinstance(j, dict):
        return None, "not a mapping"
    jid, step, ref = str(j.get("id", "")), str(j.get("step", "")), str(j.get("ref", ""))
    out, after = str(j.get("out") or jid), str(j.get("after") or "")
    if not NAME.match(jid):
        return None, f"bad id {jid!r}"
    if step not in STEPS:
        return None, f"{jid}: step must be one of {sorted(STEPS)}"
    if not REF.match(ref):
        return None, f"{jid}: ref must be a commit SHA"
    if not NAME.match(out) or (after and not NAME.match(after)):
        return None, f"{jid}: bad out/after name"
    gpus = j.get("gpus", "")
    if gpus != "" and gpus not in (0, 1, 2):
        return None, f"{jid}: gpus must be 0, 1 or 2"
    try:
        args = shlex.split(str(j.get("args") or ""))
    except ValueError as e:
        return None, f"{jid}: args: {e}"
    for t in args:
        if not TOKEN.match(t) or ".." in t or t.startswith("/") or t.split("=")[0] in RESERVED:
            return None, f"{jid}: arg {t!r} not allowed"
    if step == "train" and not after:
        return None, f"{jid}: train needs after: <gen_data out>"
    fields = [jid, step, ref, out, after, str(gpus), "1" if j.get("resume") else "", " ".join(args)]
    return "\x1f".join(fields), None


def main():
    qf, seenf, jobsf = sys.argv[1:4]
    d = yaml.safe_load(open(qf, encoding="utf-8").read()) or {}
    actions = []
    with open(jobsf, "w", encoding="utf-8") as f:
        for j in d.get("jobs") or []:
            line, err = job_line(j)
            if line:
                f.write(line + "\n")
            else:
                actions.append("BAD\t" + err.replace("\t", " ").replace("\n", " "))
    c = d.get("control") or {}
    as_list = lambda v: [str(x) for x in (v if isinstance(v, list) else [v] if v else [])]
    cur = {"status": str(c.get("status", "") or ""), "logs": as_list(c.get("logs")), "stop": as_list(c.get("stop"))}
    if os.path.exists(seenf):
        seen = json.load(open(seenf))
        if cur["status"] and cur["status"] != seen.get("status"):
            actions.append("STATUS")
        actions += ["LOGS\t" + n for n in cur["logs"] if n not in seen.get("logs", []) and NAME.match(n)]
        actions += ["STOP\t" + n for n in cur["stop"] if n not in seen.get("stop", []) and NAME.match(n)]
    json.dump(cur, open(seenf, "w"))   # first run: adopt current values without acting on them
    print("\n".join(actions))


if __name__ == "__main__":
    main()

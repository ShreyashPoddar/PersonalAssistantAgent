#!/usr/bin/env python
"""Drive the DGX GPU queue from anywhere (laptop at home, Claude) — it only edits queue.yaml on branch gpu-queue
and reads the control issue, through the `gh` CLI. The DGX poller (scripts/dgx/poll.sh) does the rest.

    python tools/gpu.py init                          # one time: create branch gpu-queue with the template
    python tools/gpu.py pipeline v1 [--n 20000]       # queue data -> train 1B + 270M -> eval + export (6 jobs)
    python tools/gpu.py add train --id train-1b-v2 --after data-v1 --args "--base google/gemma-3-1b-it --lr 3e-5"
    python tools/gpu.py status | logs <id> | stop <id>
    python tools/gpu.py watch [-n 10]                 # latest posts from the DGX on the control issue
    python tools/gpu.py fetch <out> [--to models/]    # download a finished artifact (e.g. the .task) from HF

Env: PAA_CONTROL_SLUG (default ShreyashPoddar/PersonalAssistantAgent), PAA_ISSUE (control issue number),
     PAA_HF_REPO (default ShreyashPoddar/paa-artifacts). Job refs default to the current tip of main on GitHub.
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
from pathlib import Path

SLUG = os.environ.get("PAA_CONTROL_SLUG", "ShreyashPoddar/PersonalAssistantAgent")
CODE = os.environ.get("PAA_CODE_SLUG", SLUG)
BRANCH = "gpu-queue"
ROOT = Path(__file__).resolve().parent.parent


def gh(*args, data=None):
    r = subprocess.run(["gh", "api", *args], input=json.dumps(data) if data else None,
                       capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        sys.exit(f"gh api {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
    return json.loads(r.stdout) if r.stdout.strip() else None


def main_sha(slug=CODE):
    return gh(f"repos/{slug}/commits/main")["sha"][:12]


def read_queue():
    f = gh(f"repos/{SLUG}/contents/queue.yaml?ref={BRANCH}")
    return base64.b64decode(f["content"]).decode("utf-8"), f["sha"]


def write_queue(text, sha, msg):
    gh("-X", "PUT", f"repos/{SLUG}/contents/queue.yaml", "--input", "-",
       data={"message": msg, "content": base64.b64encode(text.encode()).decode(), "sha": sha, "branch": BRANCH})
    print(f"queue.yaml updated: {msg}")


def add_jobs(text, lines):
    """Insert job lines at the end of the jobs: block (just before `control:`), keeping comments."""
    out, done = [], False
    for line in text.splitlines():
        if line.startswith("control:") and not done:
            out += lines; done = True
        out.append(line)
    if not done:
        out += lines
    return "\n".join(out) + "\n"


def job_line(jid, step, ref, after=None, args=None, out=None, gpus=None, resume=False):
    parts = [f"id: {jid}", f"step: {step}", f"ref: {ref}"]
    parts += [f"out: {out}"] if out else []
    parts += [f"after: {after}"] if after else []
    parts += [f"gpus: {gpus}"] if gpus is not None else []
    parts += ["resume: true"] if resume else []
    parts += [f'args: "{args}"'] if args else []
    return "  - {" + ", ".join(parts) + "}"


def control(text, key, value):
    import yaml
    c = (yaml.safe_load(text) or {}).get("control") or {}
    c = {"status": c.get("status", 0), "logs": list(c.get("logs") or []), "stop": list(c.get("stop") or [])}
    if key == "status":
        c["status"] = int(c["status"] or 0) + 1
    elif value not in c[key]:
        c[key].append(value)
    line = f"control: {{status: {c['status']}, logs: [{', '.join(c['logs'])}], stop: [{', '.join(c['stop'])}]}}"
    return re.sub(r"(?m)^control:.*$", line, text)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("init")
    p = sub.add_parser("pipeline"); p.add_argument("tag"); p.add_argument("--n", type=int, default=20000)
    p.add_argument("--ref")
    p = sub.add_parser("add"); p.add_argument("step", choices=["gen_data", "train", "eval", "export"])
    p.add_argument("--id", required=True); p.add_argument("--after"); p.add_argument("--args"); p.add_argument("--out")
    p.add_argument("--gpus", type=int); p.add_argument("--resume", action="store_true"); p.add_argument("--ref")
    sub.add_parser("status")
    p = sub.add_parser("logs"); p.add_argument("id")
    p = sub.add_parser("stop"); p.add_argument("id")
    p = sub.add_parser("watch"); p.add_argument("-n", type=int, default=10)
    p = sub.add_parser("fetch"); p.add_argument("out"); p.add_argument("--to", default="models")
    a = ap.parse_args()

    if a.cmd == "init":
        sha = gh(f"repos/{SLUG}/git/ref/heads/main")["object"]["sha"]
        gh("-X", "POST", f"repos/{SLUG}/git/refs", "--input", "-", data={"ref": f"refs/heads/{BRANCH}", "sha": sha})
        text = (ROOT / "scripts" / "dgx" / "queue.yaml").read_text(encoding="utf-8")
        gh("-X", "PUT", f"repos/{SLUG}/contents/queue.yaml", "--input", "-",
           data={"message": "gpu-queue: empty queue", "content": base64.b64encode(text.encode()).decode(), "branch": BRANCH})
        print(f"created branch {BRANCH} with queue.yaml (never merge or delete this branch)")
        return
    if a.cmd == "watch":
        issue = os.environ.get("PAA_ISSUE") or sys.exit("set PAA_ISSUE to the control issue number")
        for c in gh(f"repos/{SLUG}/issues/{issue}/comments?per_page=100")[-a.n:]:
            print(f"--- {c['created_at']}\n{c['body']}\n")
        return
    if a.cmd == "fetch":
        from huggingface_hub import snapshot_download
        repo = os.environ.get("PAA_HF_REPO", "ShreyashPoddar/paa-artifacts")
        d = snapshot_download(repo, repo_type="dataset", allow_patterns=[f"{a.out}/*.task", f"{a.out}/*.json"], local_dir=a.to)
        print("downloaded to", Path(d) / a.out)
        return

    text, sha = read_queue()
    if a.cmd == "pipeline":
        ref, t = a.ref or main_sha(), a.tag
        lines = [job_line(f"data-{t}", "gen_data", ref, args=f"--n {a.n}"),
                 job_line(f"train-1b-{t}", "train", ref, after=f"data-{t}", args="--base google/gemma-3-1b-it"),
                 job_line(f"train-270m-{t}", "train", ref, after=f"data-{t}", args="--base google/gemma-3-270m-it --epochs 4"),
                 job_line(f"eval-1b-{t}", "eval", ref, after=f"train-1b-{t}"),
                 job_line(f"eval-270m-{t}", "eval", ref, after=f"train-270m-{t}"),
                 job_line(f"export-1b-{t}", "export", ref, after=f"train-1b-{t}", args=f"--size 1b --tag {t}"),
                 job_line(f"export-270m-{t}", "export", ref, after=f"train-270m-{t}", args=f"--size 270m --tag {t}")]
        write_queue(add_jobs(text, lines), sha, f"queue pipeline {t} @ {ref}")
    elif a.cmd == "add":
        line = job_line(a.id, a.step, a.ref or main_sha(), a.after, a.args, a.out, a.gpus, a.resume)
        write_queue(add_jobs(text, [line]), sha, f"queue {a.id}")
    elif a.cmd == "status":
        write_queue(control(text, "status", None), sha, "control: status")
    else:
        write_queue(control(text, a.cmd, a.id), sha, f"control: {a.cmd} {a.id}")
    print("the DGX reads it within ~3 min; replies appear on the control issue (python tools/gpu.py watch)")


if __name__ == "__main__":
    main()

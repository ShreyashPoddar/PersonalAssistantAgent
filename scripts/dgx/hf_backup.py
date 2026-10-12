#!/usr/bin/env python
"""Off-box backups for the PAA lane, so a wiped DGX workspace loses nothing and work can move to any GPU.

    python hf_backup.py done <art_root> <name>       # finished artifacts/<name> -> <repo>/<name>/ (then marks it)
    python hf_backup.py partial <art_root> <name>    # small files of a running job (data .jsonl, logs, json)
    python hf_backup.py state <root>                 # queue_state / queue_posted -> <repo>/state/
    python hf_backup.py restore <art_root> <name>    # <repo>/<name>/ -> artifacts/<name>/
    python hf_backup.py restore-state <root>         # after a reinstall, BEFORE starting the poller

One private HF dataset repo (env PAA_HF_REPO, default ShreyashPoddar/paa-artifacts) — a personal account, NOT the
LocalAIModel org (its free storage is nearly full). Training checkpoints (ckpt/: optimizer state, GBs) are never
uploaded: a finished job keeps model/ and the .task, an unfinished one only its small files. History is squashed
after each upload so storage doesn't pile up. Needs HF_TOKEN.
"""
import os
import sys
from pathlib import Path

from huggingface_hub import HfApi, snapshot_download

REPO = os.environ.get("PAA_HF_REPO", "ShreyashPoddar/paa-artifacts")
STATE_FILES = ("queue_state", "queue_posted")
SKIP = ["ckpt/**", "tflite/**", ".backup_at", ".backed_up"]


def _api():
    api = HfApi(token=os.environ["HF_TOKEN"])
    api.create_repo(REPO, repo_type="dataset", private=True, exist_ok=True)
    return api


def done(art: str, name: str):
    d = Path(art) / name
    api = _api()
    api.upload_folder(folder_path=str(d), path_in_repo=name, repo_id=REPO, repo_type="dataset",
                      ignore_patterns=SKIP, commit_message=f"{name} (finished)")
    api.super_squash_history(repo_id=REPO, repo_type="dataset")
    (d / ".backed_up").touch()
    print(f"backed up {name} (finished) -> {REPO}")


def partial(art: str, name: str):
    d = Path(art) / name
    api = _api()
    api.upload_folder(folder_path=str(d), path_in_repo=name, repo_id=REPO, repo_type="dataset",
                      allow_patterns=["*.jsonl", "*.json", "*.log"], commit_message=f"{name} (in progress)")
    api.super_squash_history(repo_id=REPO, repo_type="dataset")
    print(f"backed up {name} (in progress) -> {REPO}")


def state(root: str):
    api = _api()
    for f in STATE_FILES:
        p = Path(root) / f
        if p.exists():
            api.upload_file(path_or_fileobj=str(p), path_in_repo=f"state/{f}", repo_id=REPO, repo_type="dataset",
                            commit_message=f"state/{f}")
    print(f"backed up state -> {REPO}")


def restore(art: str, name: str):
    snapshot_download(REPO, repo_type="dataset", allow_patterns=[f"{name}/**"], local_dir=art, token=os.environ["HF_TOKEN"])
    d = Path(art) / name
    if not d.exists():
        sys.exit(f"no backup of {name}")
    if (d / ".done").exists():
        (d / ".backed_up").touch()          # it came from the backup: no need to upload it again
    print(f"restored {name}")


def restore_state(root: str):
    d = snapshot_download(REPO, repo_type="dataset", allow_patterns=["state/*"], token=os.environ["HF_TOKEN"])
    for f in STATE_FILES:
        src = Path(d) / "state" / f
        if src.exists():
            (Path(root) / f).write_bytes(src.read_bytes())
            print(f"restored {f}")


if __name__ == "__main__":
    cmd, *args = sys.argv[1:]
    {"done": done, "partial": partial, "state": state, "restore": restore, "restore-state": restore_state}[cmd](*args)

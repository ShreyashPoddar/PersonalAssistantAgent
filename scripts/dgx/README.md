# PAA GPU lane on the SRM DGX — control it from home

The same setup as LocalAI's DGX lane, run as a **separate lane**: its own folder (`~/paa`), its own poller (tmux
`paa-queue`), its own cron line, queue branch, backups and venvs. LocalAI's queue, venv and files are never touched.

```
laptop / phone ──edit queue.yaml (branch gpu-queue)──▶ GitHub ◀──polls every 3 min (outbound only)── DGX poller
                ◀──status, logs, results on the control issue────┘                                    │
                                                                         jobs: gen_data → train → eval → export
                                                     backups ──▶ private HF dataset ShreyashPoddar/paa-artifacts
```

- **Only whitelisted steps run** (`gen_data`, `train`, `eval`, `export` = the scripts in `train/`), only from a
  commit on `main`, with plain `--flag value` args (`parse_queue.py` rejects anything else). No shell from GitHub.
- **Polite on a shared box:** idle GPUs only (<1 GiB used), highest index first (LocalAI takes the lowest), at most
  `PAA_MAX_GPU_JOBS=1` PAA GPU job at a time, and only while `PAA_LEAVE_FREE=2` idle cards stay free for LocalAI.
- **Backups:** finished outputs (data, model, `.task`, eval) and the queue state go to Hugging Face; a running
  data job's `.jsonl` is backed up every 30 min. A wiped box or a different GPU can continue from there.
- Changing these scripts needs campus SSH (no remote code updates of the tools — same rule as LocalAI).

## One-time install (on campus, ~20 min)

0. **Tell Person A first.** The DGX account and its crontab are shared; this adds a second cron line, tmux
   sessions named `paa-*`, and can use 1 idle A100 when at least 3 are idle.
1. Open the SSH connection the usual way (LocalAI `HANDOFF.md`, "Campus-only"), then on the DGX:
   ```bash
   export HOME=/raid/workspace/$(whoami)
   mkdir -p ~/paa && git clone https://github.com/ShreyashPoddar/PersonalAssistantAgent.git ~/paa/PAA
   bash ~/paa/PAA/scripts/dgx/setup_env.sh
   ```
   It ends with `gpu venv ok …` and `export venv ok`. (vLLM is large; the first install takes a while.)
2. On your laptop, **Hugging Face**: open https://huggingface.co/google/gemma-3-1b-it and
   https://huggingface.co/google/gemma-3-270m-it while logged in as ShreyashPoddar and click **Acknowledge licence**
   on both (training downloads them). Use your existing HF write token (it never expires).
3. On your laptop, **GitHub token** for this repo: GitHub → Settings → Developer settings → Fine-grained tokens →
   Generate: Repository access *Only select repositories* → `PersonalAssistantAgent`; Permissions → Contents
   **Read-only**, Issues **Read and write**; Expiration *No expiration*. Copy it.
4. On your laptop: create the control issue and the queue branch:
   ```bash
   gh issue create --repo ShreyashPoddar/PersonalAssistantAgent --title "DGX GPU queue" --body "PAA DGX poller posts here."
   python tools/gpu.py init
   ```
   Note the issue number. (The repo is public, so these posts are public too: they show job names, GPU memory use
   and losses — no chat data, no tokens. For a private log, make a private repo, run both commands against it and set
   `PAA_CONTROL_SLUG` on the DGX and laptop.)
5. Back on the DGX, fill in the secrets **with an editor, not by pasting into the shell history**:
   `nano ~/paa/secrets.env` →
   ```
   HF_TOKEN=hf_...
   GITHUB_TOKEN=github_pat_...
   PAA_ISSUE=<issue number>
   ```
   Check there are no spaces: `grep -c ' ' ~/paa/secrets.env` must print `0`.
6. Start it and keep it alive (cron restarts it after crashes and reboots):
   ```bash
   (crontab -l 2>/dev/null; echo "*/10 * * * * HOME=/raid/workspace/$USER bash /raid/workspace/$USER/paa/PAA/scripts/dgx/watchdog.sh"; \
    echo "@reboot HOME=/raid/workspace/$USER bash /raid/workspace/$USER/paa/PAA/scripts/dgx/watchdog.sh") | crontab -
   bash ~/paa/PAA/scripts/dgx/watchdog.sh
   crontab -l        # must show BOTH the LocalAI lines and the two new paa lines
   ```
   Within a minute the issue gets "[PAA DGX] poller started".
7. Test the control path from the laptop: `python tools/gpu.py status`, then within ~3 min
   `PAA_ISSUE=<n> python tools/gpu.py watch -n 2` shows a status post.

## From home

```bash
python tools/gpu.py pipeline v1                 # data (20k) → train 1B + 270M → eval both → export both .task
PAA_ISSUE=<n> python tools/gpu.py watch          # progress / results / crash tails
python tools/gpu.py logs train-1b-v1             # ask for a log tail
python tools/gpu.py stop train-1b-v1
python tools/gpu.py add train --id train-1b-v1b --after data-v1 --args "--base google/gemma-3-1b-it --lr 3e-5"
python tools/gpu.py fetch export-1b-v1           # download the finished .task (pip install huggingface_hub; HF login)
```
Push code to `main` first: jobs run the commit in `ref` (default: the tip of `main` when you queue them).

**Resume after a crash / stop:** queue a new id with the same `out` and `resume: true`, e.g.
`python tools/gpu.py add gen_data --id data-v1-r --out data-v1 --resume --args "--n 20000"`.
`gen_data` continues from its last finished chunk; `train` from its newest checkpoint (if the box still has it).

**Box wiped:** on campus redo install steps 1 and 5, then **before** step 6 (else old job ids run again):
```bash
HF_TOKEN=$(grep '^HF_TOKEN=' ~/paa/secrets.env | cut -d= -f2-) ~/paa/venv/bin/python ~/paa/PAA/scripts/dgx/hf_backup.py restore-state ~/paa
``` Finished artifacts come
back automatically when a later job needs them.

**Silence on the issue for >6 h** = poller and watchdog are both down → campus trip.

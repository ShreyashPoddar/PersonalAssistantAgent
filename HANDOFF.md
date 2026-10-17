# HANDOFF — PAA (Personal Assistant Agent): Android app + on-device model + DGX training lane
Last updated 2026-10-18 ~00:10 IST. Older history: `git log -p HANDOFF.md`. Repo: https://github.com/ShreyashPoddar/PersonalAssistantAgent (PUBLIC), branch `main`.

## 1. Goal (owner's words, condensed)
Automate the owner's whole phone use with an intelligent assistant ("PAA") on their iQOO Z7 Pro (I2301, Android 15,
8 GB, Dimensity 7200): read WhatsApp/other apps and schedule real tasks (Hinglish, deadlines spread over messages);
message/email/call on command; order from Zomato/Swiggy/Blinkit/Zepto/Amazon/Flipkart/Myntra (stopping at the
owner's fingerprint + UPI PIN); alarms + wake-up calls; open any app instantly; SOS; compare buses/trains/flights;
understand all expenses (Axis) and who owes whom; answer anything from the web. Fast, accurate, no phone hangs,
**full Android security, banking apps keep working, chat/bank data never leaves the owner's devices**.

## 2. Where everything is (read these, in this order, when you start)
| File | What |
|---|---|
| `docs/PAA_SYSTEM_PLAN.md` | **Master plan** (v2): platform decision (stock locked Android 15 + agent app), architecture, capabilities, security, phases, risks |
| `docs/build/README.md` → `STATUS.md` → `P0`…`P5` | **Build guide** (task cards detailed enough for Haiku), rules, workflow, commands; `STATUS.md` = checklist (nothing ticked yet) |
| `docs/build/PARALLELISM.md` | GPU sharing with LocalAI, parallel build agents (lanes, worktrees), DB version registry (v6…v11 reserved) |
| `docs/MODEL_PLAN.md` | On-device model plan (gate, 270M/1B task model, efficiency ideas, decision rule) |
| `docs/research/platform-research-2026-10-16.pdf` | Owner's web research (bootloader, banking apps, AI calls, MCP ordering…) the plan is built on |
| `train/` | Model pipeline: `gen_data.py` (vLLM teacher → synthetic Hinglish data) → `train_sft.py` → `eval_model.py` → `export_task.py` (MediaPipe `.task`); `paa_format.py` = prompt shared with the app |
| `scripts/dgx/` | DGX GPU lane (poller, launcher, budget policy, watchdog, HF backups) + `README.md` |
| `tools/gpu.py` | Drive the DGX queue from anywhere (`pipeline`, `add`, `status`, `logs`, `stop`, `watch`, `fetch`) |
| `PAA_EXECUTION_PLAN.md`, `PAA_MASTER_ARCHITECTURE_PLAN.md` | Older app plans (bug/speed tasks B*/S*/V*/C*/A*, mostly done) |

## 3. Current state

### 3a. DGX model training — RUNNING ON ITS OWN, controlled via GitHub
- DGX = SRM DGX A100 `srmist28@172.16.0.32`, 8×A100-40GB, account **shared with the LocalAI project** (owner = Person B
  there). `HOME=/raid/workspace/srmist28` (no real home). PAA lives in `~/paa` (tools checkout `~/paa/PAA` on main,
  mirror `~/paa/PAA.git`, venvs `venv` + `venv-export`, `artifacts/`, `jobs/`, `secrets.env` with HF_TOKEN /
  GITHUB_TOKEN (same token as LocalAI) / `PAA_ISSUE=1` / `PAA_LEAVE_FREE=0`).
- **Control = GitHub only:** queue file `queue.yaml` on branch **`gpu-queue`** (never merge/delete it); the poller
  (tmux `paa-queue`, polls every 3 min) posts everything to **issue #1**
  (https://github.com/ShreyashPoddar/PersonalAssistantAgent/issues/1 — **122+ comments: always read page 2**, e.g.
  `gh api "repos/ShreyashPoddar/PersonalAssistantAgent/issues/1/comments?per_page=100&page=2"`).
- Installed tools = `f6827de` (+ self-update will pull `9fb451e`). Features: **self-update** from main (syntax-checked,
  bad commits refused), watchdog cron (`*/10` + `@reboot`) restarts a hung poller (no loop 30 min) and rolls back
  crash-looping tools to `~/paa/tools.good`; HF backups to private dataset `ShreyashPoddar/paa-artifacts`.
- **GPU budget = ONE budget for BOTH projects** (owner's rule): day 07:00–19:00 IST **2 cards total**; night 19:00–07:00
  **up to 4 total, 1 always idle, min 2**. Rules live in LocalAI's `queue.yaml` `limits:` (branch `dgx-queue` of
  `ShreyashPoddar/AIModel`); PAA reads `~/localai/queue.parsed.limits` (defaults built in). **PAA's share: guaranteed
  1 card day and night when it has GPU work; a 2nd at night if needed and room** (`scripts/dgx/paa_policy.py`, 17 tests
  in `test_paa_policy.py`). PAA publishes `~/paa/demand.json`; budget-paused PAA jobs (SIGUSR1 → checkpoint → exit 75)
  resume automatically first.
- AIModel PR #104 **merged** 2026-10-16 (LocalAI reserves PAA's share).
- **Data:** `data-v1` done: 6,018 examples (train 5,672 / val 180 / test 166). 20k were generated but ~11k lost to a
  since-fixed restore bug -> `data-v2` (20k, same generator) queued.
- **v1 results (eval on data-v1 test, 166 ex + 12 scenarios):** 1B: 10/12 scenarios, has_task 0.90, count 0.86, false 4.8%,
  missed 5.4%, title F1 0.76, when 0.74 (val loss rose in epoch 3: slight overfit). 270M: 10/12, has_task 0.90, count 0.87,
  false 4.8%, missed 4.8%, title F1 0.71, when 0.70. Both fail `internship_other_batch`, `hackathon_link_random_group`.
  Bundles: `paa-tasks-1b-v1.task` 1025 MB, `paa-tasks-270m-v1.task` 284 MB, downloaded to `models/export-*-v1/` (laptop
  is now `hf auth login`-ed). Next: install on phone, `tools/eval_chats.py`, time/RAM.
- Export toolchain: `ai_edge_torch` was renamed `litert_torch`; `export_task.py` builds `~/paa/venv-litert` once
  (litert-torch + tensorflow-cpu + mediapipe 0.10.x; mediapipe 1.x has no genai bundler). Works since `eaedaf8`.
- Policy fixes 2026-10-17: a held card goes to PAA's next ready job (`0899ab3`); a hold is ended directly (its SIGUSR1
  never arrived -> PAA briefly used 5 cards), stale pauses re-asked after 10 min (`b8931f6`). Keep files LF (Python
  write_text on Windows writes CRLF — fix with `sed -i 's/\r$//'`).
- **v2 done 2026-10-17 (data-v2 13,918 kept of 20k; test n=436):** 1B: has_task .93, count .92, false 3.7%, missed 3.2%,
  title F1 .78, when .72, 10/12. 270M: json .998, has_task .90, count .88, false 4.1%, missed 6.0%, title .74, when .64,
  10/12 (270M gained little from more data). Same 2 scenarios fail -> v3 needs targeted generation for them; also the
  "invented name" filter wrongly rejects words like Friday/October/Python (~100 ex). Bundles in `models/export-*-v2/`.
  Queue empty; `chain-270m-v2` holds gpu 7 (owner decides on release). AIModel PR #111 (PAA jobs in monitor) merged.
- **Phone tests 2026-10-17 (adb 192.168.1.3:35399, re-paired; app was freshly installed, owner granted notification +
  accessibility):** eval_chats on phone: 270M v1 11/15 (~0.75 GB, ~5 s/msg); 1B v2 int8 made the phone lag (1.5-1.8 GB,
  stopped); **1B v2 4-bit (`export-1b-v2-q4`, 574 MB) 12/15, peak ~1.1 GB, ~7 s/msg -> installed now.** Fails: two tasks
  in one message get merged/dropped (hinglish_two_tasks, own_promise) -> weight that category up in v4 data;
  done_reply_closes ("10 final na ??"). GPU: fails even for Google's official .task ("GPU accelerator could not be
  loaded" — accelerator .so missing from the APK); GPU only speeds prefill, not decode -> parked.
  Export defaults now 4-bit, prefill 32/128/512, KV 768 (`2eb30b8`); v3 exports use it. data-v3 (25k, seed 3) running.
- **v3 (2026-10-17, data-v3 18,090 kept of 25k, test n=557; UNPADDED eval):** 1B v3: json 1.0, has_task .94, count .93,
  false 2.2%, missed 3.6%, title F1 .82, when .75, 11/12 (only hackathon_link_random_group fails) — best so far.
  270M v3: has_task .92, count .90, false 3.8%, missed 4.1%, title .79, when .69, 11/12. 4-bit bundles in
  `models/export-*-v3/` (1B 574 MB, 270M 160 MB). **Eval bug found:** batched left-padded generation garbled the first
  token of ~7% of Gemma 3 outputs (json_ok 0.93) -> `eval_model.py` now runs batch 1 by default. v1/v2 numbers above
  came from the batched eval. GPU: PAA holds no card (queue empty), LocalAI has it.
- **Next:** phone eval of 1B v3 4-bit (ask owner if phone is free; replaces v2-q4 if >12/15); v4 data = more
  "two tasks in one message" + hackathon-link-in-random-group; then P1 app work (`docs/build/STATUS.md`).
- The owner wants: keep cards we hold until the whole model is done; **tell the owner before releasing an idle held card**;
  decide routine things yourself and inform (don't ask).

### 3b. Android app — code done, phone verification pending
- Build+tests: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew -q assembleDebug testDebugUnitTest` — green at HEAD.
- Done earlier (see git log): B1–B22, S0–S6, V1/V2 voice lock, C1/C2, A1 WhatsApp send, A2a PAA calls you, A3 hackathon
  autofill, A4.2/4.3/4.5, M6. Eval `python tools/eval_chats.py --serial emulator-5554`: 15/15 at the bug-fix build;
  last run 10/10 passed (5 not run) — `tools/eval_head.txt`.
- Committed 2026-10-16 but **not verified on the phone**: wake listener restarts after mic errors; accessibility re-reads
  reopened (incl. locked) chats, trailing scan, own messages from tick bubbles, WhatsApp 2.26.38 send id
  `conversation_entry_action_button`; DetectionLog 200 entries; compact prompt for `paa-*.task` models
  (`LocalLlm.buildCompactPrompt`, parity test `CompactPromptTest` vs `train/golden_prompt.txt`).
- Phone facts: "Oyee PA" toggle was OFF on the phone (`wake_listener_enabled=false`) — owner must turn it on.

### 3c. The big plan — nothing of P0–P5 built yet
Next build work starts at `docs/build/STATUS.md` P0 (phone tests) and P1 (action framework / Guard / confirm / audit).

## 4. Decisions (and why) — don't relitigate
- **Platform:** stay on stock, **locked** Android 15 on the iQOO + PAA as a privileged agent app. No custom OS: iQOO
  bootloader isn't legitimately unlockable, unlocked = weaker security + banking apps break, a fork can't keep up with
  patches. Pixel + GrapheneOS-based build is the only "own OS" path, only if stock hits real walls (and banking tests pass).
- **Banking:** PAA **never** detects bank apps to hide from them or race their launch (that's circumventing a security
  control — a safety boundary; an earlier draft was cut off for it). Instead: owner-controlled "screen reading off"
  tile/switch/voice ("banking mode"), or banking in a separate Android profile; money data from bank SMS + statements.
  UPI PIN always typed by the owner; payments need fingerprint (BiometricPrompt + Keystore CryptoObject).
- **AI on SIM calls:** impossible on-phone (uplink needs system permission). Home hub (laptop/Pi) as Bluetooth
  hands-free bridge on the owner's number; cloud number (Exotel/Plivo) when away; AI always discloses itself.
- **Models:** Gemma 3 1B + 270M fine-tuned by distillation (Qwen3-32B-AWQ teacher), int8, phone GPU, compact prompt
  (~150 tokens vs ~750). Synthetic data only — real chats never leave the phone. LocalAI's own architecture (OLMoE MoE)
  is not used on the phone (7B weights in RAM, no MediaPipe support).
- **DGX:** GitHub-only control (no VPN allowed); PAA lane separate from LocalAI's; only whitelisted steps
  (`gen_data|train|eval|export`) from commits on main; one-card-holding "chain" jobs because the card gets taken between jobs.
- Gemma 3n E2B (3.1 GB, CPU) stays the fallback model until the fine-tuned one passes eval 15/15 on the phone.
- Voice commands: regex → Gemini (10 s) → local model offline. Few-shot names leaked into tasks → placeholder words filter.

## 5. How to operate
- **Queue / check DGX from anywhere:** `python tools/gpu.py status` (then read issue #1 page 2 after ~3 min),
  `logs <id>`, `stop <id>`, `add train --id … --after data-v1 --out … --args "…"`, `pipeline <tag> [--parallel] [--no-data] [--no-hold]`.
  Job ids are single-use; re-queue a crashed job under a new id (`-v1c`). `ref` must be a commit on `main` (push first).
- **Change DGX scripts:** edit `scripts/dgx/`, run `cd scripts/dgx && python test_paa_policy.py`, `bash -n *.sh`, push
  → installed within ~3 min (watch issue #1 for "tools updated"). Simulated DGX for risky changes: WSL scripts in the
  session scratchpad were used (`setup.sh`/`run.sh`: fake nvidia-smi/curl/training) — recreate if needed.
- **Campus is needed only for:** secrets/token changes, a wiped workspace. SSH: in PowerShell
  `wsl -d Ubuntu-24.04 -u root -- ssh -M -S /root/.ssh/dgx.sock -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=60 -N srmist28@172.16.0.32`
  (owner types password), then a shell via `... ssh -t -S /root/.ssh/dgx.sock srmist28@172.16.0.32`. **Claude may NOT run
  commands on the DGX over SSH (blocked as remote shell writes)** — give the owner exact commands to paste.
- **Phone:** `adb connect 192.168.1.3:<port>` (port changes — ask the owner), always `-s <serial>`,
  `export PATH="$LOCALAPPDATA/Android/Sdk/platform-tools:$PATH" MSYS_NO_PATHCONV=1`. Install `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Python on the laptop: `PYTHONIOENCODING=utf-8` for scripts printing non-ASCII; write multi-line Python edits as files
  (bash heredocs with backslashes break).

## 6. Open items / next steps (in order)
1. (Optional, owner) add `paa: {min: 1, max: 1}` and
   `night.paa: {min: 1, max: 2}` to LocalAI `limits:`.
2. **Watch training:** when `chain-1b-v1b`/`chain-270m-v1b` launch/finish, report eval results (issue #1). Exports
   produce `.task` files → `python tools/gpu.py fetch export-1b-v1` → install on phone (`docs/build/P5_intelligence.md` T1),
   run `tools/eval_chats.py`, measure time/RAM. If a job crashes, read the tail, fix in `train/`, push, re-queue new id.
   When everything is done and a card is only held, **tell the owner before releasing it**.
3. **Phone:** owner gives wireless-debugging port → verify the 2026-10-16 app fixes (wake listener, accessibility,
   Ahana's locked chat, sent messages) and start `docs/build/P0_setup_and_tests.md`.
4. **Build:** P1 per `docs/build/STATUS.md` (several agents in parallel per `PARALLELISM.md` only if the owner asks).
5. Later: v2 data (20k+, harder cases, shorter answer format), learned gate, hub (P4).

## 7. Owner preferences
- Brief replies, result first; no recap; ask before spawning subagents (token use); suggest a fresh conversation for unrelated tasks.
- "Don't ask me, inform me" for routine decisions; ask only for money, accounts, destructive actions, owner-only steps.
- Chat/call/bank content never leaves the owner's devices; no TTS except in-call earpiece / opt-in PAA-calls-you; alarms ring 07–19.
- Names shreyash/shreyas, batch 2028, hackathon group hackstreet_Boys. Contact Ahana (locked chat on WhatsApp).
- Wants speed, parallelism, real testing, GPUs kept busy within the shared budget; public repo is fine.

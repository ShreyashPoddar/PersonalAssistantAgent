# HANDOFF — PAA (Personal Assistant Agent)
Updated 2026-10-18 ~17:00 IST · repo https://github.com/ShreyashPoddar/PersonalAssistantAgent (PUBLIC), branch `main`, HEAD = latest commit (clean). Older history: `git log -p HANDOFF.md`.

## Goal
- Owner's words (condensed): automate the whole phone (iQOO Z7 Pro I2301, Android 15, 8 GB): read WhatsApp/other apps
  and schedule real tasks (Hinglish, deadlines spread over messages); message/email/call on command; order food/shopping
  (stop at fingerprint + UPI PIN); alarms/wake-up calls; open apps; SOS; compare travel; expenses (Axis) + who owes whom;
  web answers. Fast, accurate, **no phone hangs**, full Android security, banking apps keep working, **chat/bank data
  never leaves the owner's devices**.
- Done when: `docs/build/STATUS.md` P0–P5 all ticked; on-device model passes `tools/eval_chats.py` 15/15 on the phone.
- Read first: `docs/PAA_SYSTEM_PLAN.md` (master plan) → `docs/build/README.md` → `docs/build/STATUS.md` → `P0`…`P5`;
  `docs/MODEL_PLAN.md`; `docs/build/PARALLELISM.md`.

## Latest (2026-11-19, read first)
- Real-use review (18 Oct–18 Nov): 56 real tasks = 22 right, 12 wrong title, 22 junk; misses = requests in pieces.
  Design: `docs/build/TASK_PIPELINE_V2.md`. Step 1 shipped + on phone (`493c60b`, `ef787d7`): SenderKinds (business
  skip), ChatTaskChecks (advice/empty/grounding/owner-name/OTP mask), 30-min carry-over, group follow-ups, no rule
  fallback (retry), no invented deadlines. Live phone check passed 7/7 after the empty-title fix.
- **Incident:** a test script sent debug `reset` to the real phone → wiped tasks/history/feedback. Tasks+history+log
  restored from dump (verified exact); ✓/✗ feedback lost. `reset` now refused on non-emulators; eval_chats cleans only
  its own `E<run>` chats. Memory: never wipe the real phone.
- Owner approved + I deleted 21 junk tasks (backup `PAA-private/before-cleanup.json`). Phone: 28 pending, 7 done.
- Private real test set: `C:/Users/shrey/Desktop/PAA-private/realset-v1.jsonl` (57 cases, laptop only, never DGX/git),
  builder `build_realset.py`, scorer `train/eval_real.py`, runner `PAA-private/run_eval_real.sh` (WSL root, CPU —
  LocalAI gateway holds the laptop GPU). **v4 = 31/57**, recall 17/34, 11 false tasks, 5 owner-named titles,
  pieces 0/3. (t78 label relaxed after the run → v4 likely 32/57; rerun with v6.)
- v6 (gen_data: company accounts, Hindi advice, owner's name, money splits, requests in pieces, more Hindi) queued
  ref `73979db`: data-v6 launched 2026-11-19 03:41Z gpu3 → chain-1b-v6 (no hold) → export-1b-v6.
- Phone: 10.63.128.3:<port> on owner's hotspot (pair again if refused; pairing expires ~7 days).

## 2026-11-26 (overnight, owner asleep)
- Phase 1 code complete: T7 banking guide (doc + dialog), T8 app launcher, T9 SOS (not run — drill sends a real SMS),
  T10 TextSanitizer, T11 NodeFilter, T12 Safety card + Lockdown tile, T13 dev-options reminder (mutable). Plus
  all-alarms-off switch, bare "aaj/today/eod" deadline. STATUS.md marks what still needs a phone check.
- Phase 2 code: BankSmsParser/Categorizer/Ledger/MoneyQuery (pure, synthetic tests), DB v7 (transactions,
  merchant_rules, ledger; migration 6→7 verified on emulator), MoneyIntake (UPI notifications + bank SMS receiver +
  on-demand backfill), Money card, voice money questions + "X owes me 500", SMS + email skills, MoneyPrivacyTest.
- **v7b is the phone model** (installed 2026-11-26): private set 46/57 (v6 40, v4 32); clean phone eval 14/15
  (fail: own_promise read as a confirmation — passed in the previous run), peak PSS 1.07 GB, min free 2.6 GB.
  Private set now 64 cases (7 from 19–26 Nov; new failure type "someone else's promise" → in gen_data for v9). v6 kept on phone as local_model_v6.task.bak, v4 as local_model_v4.task.bak.
  v8 queued (`21d28f6`, more plain-request weight).
- Fixed tonight: eval_chats cleanup line had never landed (blocked command on 2026-11-19) → test tasks piled up on the
  phone (18 removed, all test-tagged); SOS without location permission would have died on Android 14+; Telegram chats
  were all classed as businesses; no-deadline tasks weren't deduped or shown in the morning summary; UPI-app wording.
- (old note) Phone tests collide with leftover test tasks from earlier eval runs (titles like "Send English essay to Zoya") —
  clean tagged test tasks (sourceApp with " E" + 4 digits) after evals; eval_chats only removes its own run's tag.
- adb: background keepalive script in scratchpad (reconnects every 30 s); phone 192.168.1.5:<port>.
- Later overnight: P4-T1 hub skeleton (hub/server.py + HubClient/HubCard), P3-T5 travel compare (read-only), phone
  eval grown to 30 scenarios (tools/scenarios.json; eval_chats `business` flag + `sleep` steps), tools/pull_review.py
  (backup + review since last), DetectionLog 800 entries, ChatMemory (tested), monkey 13k events on emulator: no
  crash/ANR. v8 + v9 queued on DGX (v9 = v8 + someone-else's-promise categories). Owner to-do: docs/build/OWNER_CHECKS.md.
- **v7b on the 30-scenario phone eval: 29/30** (only someone_elses_promise fails → v9). Run it with the mdns serial
  `adb-10BDB80A07000G0-I8njvu._adb-tls-connect._tcp` — the IP:port changes when wireless debugging restarts
  (it did at 04:13 mid-run); keepalive2.sh in scratchpad uses that name.
- Private set (64 cases): **v7b 51/64**, v6 46/64. v7b weak: someone else's promise 0/2, requests 12/18, pieces 1/3.
  Next: when export-1b-v8 / -v9 finish → `bash ../PAA-private/score_models.sh v8 v9` (downloads + scores vs v7b);
  ship the best to the phone only if it also holds 29/30 on the phone eval and ≤ 1.2 GB.
- **v8 on the private set: 41/64 — not shipped** (synthetic was better: .952 has_task, 11/12). Lost all 5 eligible job
  posts ("announcement for 2027 and 2028 batches, not a task"), 5 titles name the owner, +5 false tasks. v8 data
  labels look fine (job posts incl. 2028 → tasks; same split as v7b), so likely training variance / category mix.
  Queued `chain-1b-v9mix` (v9 + v7b + v8 train sets via new `--extra-data`, 2 epochs) after data-v9, next to plain
  v9. Score both: `bash ../PAA-private/score_models.sh v9 v9mix` (if it exits 1 with no lines, run the wsl line
  without the grep to see the error; a plain rerun worked on 2026-11-26).
- **v9 on the private set: 53/64 — best so far** (v7b 51, v9mix 48, v8 41). requests 15/18 (v7b 12), pieces 2/3,
  job posts 5/5; but 2 titles name the owner (v7b 0) and someone else's promise still 0/2. Mixing older data hurt.
  Next: phone 30-scenario eval of export-1b-v9 (needs ≥ 29/30, ≤ 1.2 GB) → swap in, v7b kept as .bak. Phone was off
  wireless debugging on 2026-11-27 — needs a fresh IP:port + pairing code from the owner.
- **v9 on the phone: 27/30 → not shipped**, v7b restored (v9 kept as local_model_v9.task.bak; peak PSS 1.12 GB).
  Fails: someone_elses_promise, group_followup ("Do something"), + 1 cut from the log. Backup before: PAA-private/
  backup-2026-11-27.json; test leftovers checked (0 tagged tasks, chat lines removed).
- Root cause of "someone else's promise": the teacher's only check is self-agreement, and in no-task categories it
  consistently labels tasks (data-v9: 288/385 promises, 537/539 random-group hackathon @mentions, 172/645 company
  accounts). gen_data v10 drops those ("category says no task") + explicit rule. **v10 queued** (data → chain → export).
- **v10 private set: 49/64** — promise 2/2 (fixed), 0 owner-named titles, 3 false tasks (v9 6), but recall 26/39
  (v9 34): requests 11/18, pieces 0/3. DGX scenarios 21/25 (someone_elses_promise passes). Not phone-tested.
  Queued `chain-1b-v10mix` = v10 + data-v9 with the same filter applied in train_sft (15707 → 13979 rows), 2 epochs.
  Score: `bash ../PAA-private/score_models.sh v10mix`; candidate needs > 53/64 or v9's recall without its faults.
- **Laptop model path (2026-11-28).** Owner: run a bigger model on the laptop GPU (RTX 5070 Ti, 12 GB, 32 GB RAM).
  llama.cpp b12373 (CUDA 13.5) + google/gemma-4-12B-it-qat-q4_0-gguf in %USERPROFILE%\paa-llm (outside the repo).
  Start: `powershell -File hub\start_laptop.ps1 [-Lora <paa-lora.gguf>]` (server on 127.0.0.1:8081 only, then hub).
  MUST run with --jinja --reasoning-budget 0 + enable_thinking false: with thinking on, 47/64 answers were empty.
  **Gemma 4 12B untrained, teacher rules as system prompt: 51/64** (= v7b), promise 2/2, owner-named 7/8, 0 titles
  with the owner's name, but job posts 1/5, 8 false tasks; median 2.6 s/message (max 22 s).
  Phone: LocalLlm.extractTasks tries the paired+enabled hub first (POST /extract), falls back to the phone model,
  skips the hub 2 min after a failure. Not yet installed on the phone / not yet paired.
  DGX: `lora-12b-v10` queued (train_sft --lora → train_lora.py: Gemma 4 12B LoRA r16 on data-v10 + cleaned v9, 2 ep,
  own chat format, adapter → paa-lora.gguf). Score with the hub's student mode:
  `eval_real.py --server ... --prompt student` after starting llama-server with --lora.
  WhatsApp Desktop (WebView2) exposes its chat list and open chat through UI Automation (~400 elements); classes
  are obfuscated CSS, so a reader must use roles/structure. Reader not built yet: opening a chat marks it read.
- **PAA screen reading (accessibility) was found OFF** on 2026-11-26 — left for the owner (may be deliberate).

## 2026-11-22
- **v6 is the phone model** (installed 2026-11-21; v4 kept as `models/local_model_v4.task.bak` on the phone):
  private set 40/57 vs v4 32/57; phone eval 14/15 (fail: own_promise merged "call + send ppt"), peak PSS 0.92 GB,
  min free 1.7 GB. App build with the Hindi-title grounding fix installed. Phone backup `PAA-private/backup-2026-11-21.json`.
- data-v7 crashed (vLLM tokenizer TypeError, traceback through transformers 5 files) → gen_data hardened (`aeea5f4`);
  re-queued as data-v7b → chain-1b-v7b → export-1b-v7b; old chain/export-1b-v7 cancelled. If v7b crashes the same way,
  the DGX venv likely moved to transformers 5: pin `transformers<5` in train/requirements.txt and ask the owner to run
  scripts/dgx/setup_env.sh on campus.

## Next step
0. **v6 done 2026-11-19; private set: v6 40/57 vs v4 32/57** (recall 23/36 vs 19/36, false 3 vs 9, owner-named 2 vs 5,
   business 9/9; worse: eligible job posts 0/5, vague 0/1). Weights + `paa-tasks-1b-v6.task` in `models/hf/`. Remaining:
   push the .task to the phone, run tools/eval_chats.py on the phone (watch PSS), install if it holds up.
   v6 misses: all eligible job posts ("announcement"), splits, pay-for-me pieces, send-me-info → **v7 queued** ref
   `e36c5d5` (data-v7 → chain-1b-v7 → export-1b-v7). Score v7 vs v6 with run_eval_real.sh (output file is named after
   the model dir, `model.json`: rename per model).
1. (done) When export-1b-v6 is done: download `train-1b-v6/model` from HF dataset ShreyashPoddar/paa-artifacts to
   `models/hf/`, run `run_eval_real.sh --model .../train-1b-v4/model --model .../train-1b-v6/model`; ship v6 only if
   it beats v4 there and on tools/eval_chats.py (phone, ≤1.2 GB PSS).
2. Pipeline step 2 (design §3): real 60-min window with NEW markers + evidence ids (v7 ops format).
3. Open: "aaj" in a follow-up didn't become a deadline; call transcripts still weak; P1-T7 onward.

## Done
- **On-device model:** 4 rounds trained on the DGX (Gemma 3 1B + 270M, distilled from Qwen3-32B-AWQ, synthetic data only).
  - **Installed on phone: `paa-tasks-1b-v4.task` (4-bit, 574 MB) — phone eval 14/15**, peak app PSS ~1.13 GB, phone
    MemAvailable ≥2.27 GB, 2–11 s/message (CPU). Verified with `tools/eval_chats.py --serial <ip:port>` 2026-10-18.
  - Phone history: 270M v1 11/15 (0.75 GB); 1B v2 int8 lagged phone (1.5–1.8 GB, stopped); 1B v2 4-bit 12/15; 1B v3 4-bit 12/15.
  - DGX eval (unpadded, stricter): 1B v4 11/12 scenarios, has_task .91, false 5.0%, missed 3.9%, when .69 (harder test
    set); 1B v3 11/12, has_task .94, false 2.2%, missed 3.6%, title F1 .82, when .75.
  - Bundles on laptop: `models/export-{1b,270m}-v{1,2,3,4}/`, `models/export-1b-v2-q4/`, Google's
    `models/official/Gemma3-1B-IT_seq128_q8_ekv1280.task` (GPU test only). Laptop is `hf auth login`-ed.
  - Data: data-v1 6,018 · v2 13,918 · v3 18,090 · v4 17,218 kept (v4 = more two-task messages).
- **Export toolchain fixed:** `ai_edge_torch` → `litert_torch`; `train/export_task.py` builds `~/paa/venv-litert` once
  (litert-torch ≥0.9 + tensorflow-cpu + mediapipe 0.10.x, marker `.ok`); defaults 4-bit `dynamic_int4_block32`, prefill
  32/128/512 (one flag each), KV 768.
- **Eval fixed:** batched left-padded generation garbled ~7% of Gemma 3 outputs (stray "দিবে" first token) →
  `train/eval_model.py` batch 1 default; writes `json_failures.jsonl`; scenarios need one task per expected item; outgoing scenarios marked.
- **DGX policy/poller fixes (all pushed, self-updated on DGX):** held card goes to PAA's next ready job (`0899ab3`);
  holds ended directly + stale pauses re-asked (`b8931f6`); `control.stop` false "no running job" (grep -q + SIGPIPE
  under `set -o pipefail`) fixed (`8fafd81`). 20 tests: `cd scripts/dgx && python test_paa_policy.py`.
- **App:** 1–6 o'clock without am/pm = PM (`LocalTaskParser.kt` ~line 447–455, test `ChatExtractionTest`); built,
  unit tests green, installed on phone. App was freshly installed 2026-10-17; owner granted notification + accessibility.
- **LocalAI monitor:** AIModel PR #111 (PAA jobs in `live_status.py`) merged; PR #104 (PAA's GPU share) merged.
- **GPU:** queue empty, PAA holds 0 cards (`chain-270m-v4` stopped 2026-10-18 ~14:55 IST).

- **P1 started (2026-10-18):** T1 Action types, T2 Guard + DB v6 (contact_rules, audit_log; migration checked on
  emulator and on the phone), T4 Executor + Activity card / Contact rules dialog — done. T3 (fingerprint confirm) and
  T5 (send/alarm/call/auto-reply via Executor) code + tests done, installed on phone; **need the owner's phone checks**:
  T3 = PlaceOrder("test","1 item",100) through ConfirmBroker → fingerprint (temporary debug button, remove after);
  T5 = "Oyee PA, message <test contact> test" → first time asks fingerprint → sends → shows in Activity card.
  Behaviour change: first message to any contact now needs a fingerprint; auto-reply contacts live in contact_rules.
- **v5 data round queued** (ref `9980338`): data-v5 (25k, seed 5; new categories: @mention confirm-questions
  "10 final na ??" as tasks + owner's "Hn bhai" closing them; hackathon-link-in-random-group weight up), then
  chain-1b-v5 + export-1b-v5. chain-270m-v5 stopped (270M not used on phone). Phone-test export-1b-v5 vs v4, keep better.

## Decisions
- Stock **locked** Android 15 + privileged agent app; no custom OS (iQOO bootloader not legitimately unlockable, banking breaks).
- Banking: never detect/hide from bank apps; owner-controlled "banking mode" switch or separate profile; UPI PIN by owner; fingerprint for payments.
- AI on SIM calls impossible on-phone → home hub (Bluetooth HFP) / cloud number later (P4).
- Models: only **4-bit** exports on the phone (int8 1B lagged it). 1B v4 is the phone model; 270M is fallback.
- GPU backend parked: fails even for Google's official .task — log `[gpu_registry.cc:131] GPU accelerator could not be
  loaded and registered.` (LiteRT GPU accelerator .so not in APK; MediaPipe `tasks-genai` 0.10.35). GPU only speeds
  prefill (decode 24 vs 33 tok/s CPU per Google's S24 table), our load is decode-bound.
- Eval at batch 1 (matches phone). Batched eval numbers for v1/v2 were inflated/garbled — don't compare across.
- DGX control via GitHub only (`gpu-queue` branch `queue.yaml` + issue #1); Claude may not SSH-run commands on the DGX.
- Rejected: pip-installing litert-torch into old `venv-export` (torch too old → `ImportError: cannot import name 'ScalingType' from 'torch.nn.functional'`); mediapipe 1.x (no `mediapipe.tasks.python.genai`).
- Rejected: vocab pruning / QAT retrain for now (bigger jobs; 4-bit already fits ~1.1 GB).

## Files
- `train/gen_data.py` — categories (two-task weights, hackathon/internship cases), teacher RULES, `TITLE_WORDS` allowlist.
- `train/train_sft.py` — SFT + chain (`--then-eval`, `--hold`); hold releases on SIGUSR1.
- `train/eval_model.py` — synthetic + scenario eval, `--batch` (default 1), `json_failures.jsonl`.
- `train/export_task.py` — `ensure_toolchain()` venv-litert, 4-bit defaults.
- `train/requirements-export.txt` — litert-torch, tensorflow-cpu, mediapipe <0.11.
- `scripts/dgx/poll.sh` — poller; `stop_job` ~line 78, `pause_job` (holders killed directly).
- `scripts/dgx/paa_policy.py` + `test_paa_policy.py` — shared GPU budget, `_hand_over`.
- `scripts/dgx/launch.sh` — export uses `venv-litert` if `.ok`, else `venv-export`.
- `tools/gpu.py` — queue control; `tools/eval_chats.py` — phone/emulator golden eval (15 scenarios in `tools/scenarios.json`).
- `app/src/main/java/com/paa/assistant/core/ai/LocalLlm.kt` — model at `/sdcard/Android/data/com.paa.assistant/files/models/local_model.task`; compact prompt if `local_model.task.paa` exists; GPU try ~line 511.
- `app/src/main/java/com/paa/assistant/services/ChatMessageProcessor.kt:261` — "skipped: no task words" pre-filter.
- `app/src/main/java/com/paa/assistant/core/router/LocalTaskParser.kt` — CRLF file; keep CRLF when editing.

## Open issues
- **PAA accessibility service was OFF on the phone** after the 2026-10-18 install (only Grammarly's enabled) — owner
  must re-enable (Settings → Accessibility → PAA chat reading); unknown if the reinstall or something earlier turned it off.
- Phone scenario `done_reply_closes` fails: `@Shreyash Poddar 10 final na ??` → model `no task (AI): No task found.`;
  then own reply `Hn bhai` → `skipped: no task words` (no open task, `ChatMessageProcessor.kt:261`). Expected 📌 then ✅ marked done.
- DGX scenario `hackathon_link_random_group` still fails for 1B v3/v4 on DGX eval (passes on phone because the app handles hackathon links itself).
- 1B v4 DGX false-task rate 5.0% (v3 2.2%) — watch for false tasks in real use.
- Not verified on phone (committed 2026-10-16): wake listener restart after mic errors, accessibility re-read of reopened/locked chats (Ahana), own messages from tick bubbles, WhatsApp 2.26.38 send id `conversation_entry_action_button`. "Oyee PA" toggle was OFF.
- Gemma 3n fallback model is not on the phone (fresh install); only `local_model.task` = 1B v4.
- Windows edits: Python `write_text` → CRLF, `read_text`+`write_bytes` can flip CRLF files to LF; check `git diff --stat` before committing.

## User preferences
- Brief replies, result first, no recap; ask before spawning subagents; suggest fresh conversation for unrelated tasks.
- Model work: "don't ask me, keep making" — queue data/train/eval/export rounds yourself, just report.
- GPU: keep using a held card if there's PAA work; **if no use, give it to LocalAI**. Shared budget day 2 cards / night ≤4 (1 idle).
- Phone: no hangs above all; only 4-bit models; watch memory during phone evals (stop >1.5 GB PSS or <1.2 GB free);
  owner said phone may be used for tests while they sleep. Phone IP/port change (seen 192.168.1.3, 192.168.1.4,
  10.9.233.34 on campus) — ask for IP:port; re-pair with `adb pair` if refused.
- Chat/call/bank content never leaves devices; alarms ring 07–19. Names shreyash/shreyas, batch 2028, hackathon group hackstreet_Boys.

## Next step
- P1-T6 (screen-reading switch) onward from `docs/build/P1_foundation.md`; build with
  `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew -q assembleDebug testDebugUnitTest`.
- When v5 finishes (`python tools/gpu.py status` / `watch`): fetch export-1b-v5, phone-eval with
  `tools/eval_chats.py --serial <ip:port>` (watch memory), keep the better of v4/v5. Give the GPU back when done.
- Ask the owner for the T3/T5 phone checks and to re-enable accessibility.
- **2026-11-28 laptop path live:** Tailscale installed on the laptop (100.105.190.108); hub binds ONLY to the
  Tailscale IP by default (`--network lan` for the old way); openssl found from Git for Windows. New app build
  installed on the phone (data kept: 43 tasks, 2100 chat lines; backup PAA-private/backup-2026-11-28.json).
  Owner: run hub\start_laptop.ps1, paste the pairing payload into PAA → Hub, switch it on.
  The laptop froze once with WSL CPU scoring + llama-server + a Gradle build at the same time: don't run CPU
  scoring while the model server and a build are running (stop with `wsl --shutdown`, `gradlew --stop`).
- **2026-11-28 end-to-end over Tailscale works:** phone 100.98.246.52 → hub 100.105.190.108:8765 (POST /pair,
  GET /health, GET /messages every 30 s, POST /extract). Tagged test (E9001 phone model, E9002 laptop Gemma 4 12B)
  gave "Send lab record to Kunal · due Sun 29 Nov 9:00 AM" from both; second deduped. Test data removed (43 tasks).
  Phone Tailscale must be ON (it was off at first: no VPN network, ping failed).
- **v10mix private set: 48/64** (v9 53, v7b 51, v10 49): job posts 5/5 but 5 owner-named titles, 9 false tasks,
  requests 11/18. Not shipped. The 1B rounds swing 41–53 on 64 cases (noise ±3); next effort goes to the laptop 12B.
  Scoring now runs on the laptop GPU next to llama-server: PAA-private/run_eval_real_gpu.sh (nice 19, no freeze).
- **Trained Gemma 4 12B (lora-12b-v10) on the private set: 53/64** (= v9 best), false tasks 4 (lowest), job posts
  5/5, promise 2/2, owner-named titles 2, requests 12/18, pieces 1/3; 1.7 s median, 4.8 s max (student prompt).
  Train loss .170, eval .145. Adapter: %USERPROFILE%\paa-llm\lora\train-12b-v10\paa-lora.gguf (131 MB).
  start_laptop.ps1 now uses the newest adapter by default (-NoLora for the plain model) and writes
  ~/.paa-hub/llm_mode, which the hub reads per request. Per-request test: `eval_real --server ... --prompt student --lora 1`
  with llama-server --lora X --lora-init-without-apply (global scale 0 keeps live traffic unchanged).
- **2026-11-28 live:** laptop runs trained Gemma 4 12B (train-12b-v10) via hub\start_laptop.ps1 (fixed: the
  chat-template JSON is passed via LLAMA_ARG_CHAT_TEMPLATE_KWARGS; Start-Process split it and llama-server exited).
  Hub /extract verified: 2.2 s, student mode.
- **Why titles named the owner:** gen_data's invented-name check passes the owner's name (it is in the prompt);
  data-v9 had 389/15707 such rows. gen_data v11 rejects them; train_lora drops them from --extra-data.
- **Queued:** data-v11 (25k, seed 11, 2 cards = fan-out, more weight on requests in pieces / owner named) →
  lora-12b-v11b (Gemma 4 12B LoRA, data-v11 + cleaned data-v10). (lora-12b-v11 stopped: queued on the old ref.)
  Next: download train-12b-v11/paa-lora.gguf into %USERPROFILE%\paa-llm\lora\, score with
  llama-server --lora-init-without-apply + `eval_real --prompt student --lora 1`; ship if > 53/64.
- **2026-11-28 hourly runner:** scheduled task `paa-hourly` (Claude desktop app, :17–:18 every hour, Auto mode;
  prompt in %USERPROFILE%\.claude\scheduled-tasks\paa-hourly\SKILL.md). Each run: DGX status/crashes → keep a round
  queued → score + ship finished models (12B adapter: newest paa-lora.gguf under paa-llm\lora is what the launcher
  serves; rejected ones go to paa-llm\lora-rejected\) → laptop hub health/restart → build backlog → HANDOFF entry.
  Runs only while the Claude app is open (missed runs fire on next launch).
- 2026-11-28 17:19 IST CLAIMED (hourly): fix failing hub test test_extract_forwards_prompt
- 2026-11-28 17:20 IST CLAIMED (hourly): wa_reader robustness (typing/deleted previews, chats scrolled into view)
- 2026-11-28 17:25 IST hourly: DGX data-v11 4096/12500 per shard (2 cards), lora-12b-v11b queued after it; nothing to score yet. Laptop hub ok (train-12b-v10 at scale 1). Fixed hub test that read the laptop's real ~/.paa-hub/llm_mode; wa_reader now ignores typing…/deleted previews, doesn't re-report a returning preview, and treats chats scrolled into view as old (17 hub tests pass; running hub picks it up on its next restart). Next: score lora-12b-v11b when done, then queue data-v12/lora-12b-v12.
- 18:18 IST CLAIMED (hourly): restart hub to load wa_reader fixes; STATUS.md open item
- 2026-11-28 18:30 IST hourly: DGX data-v11 8192/12500 per shard (2 cards, ~1 h left), lora-12b-v11b queued after it; nothing to score. Built P3-T1 payment hand-off (PaymentSkill/PaymentHandoff: runs only after Guard's fingerprint verdict, opens a known installed UPI app or stays in the merchant app, never touches the PIN; 8 tests, all app tests pass). Restarted the laptop hub to load the wa_reader fixes (model v10 at scale 1, health ok); found the launcher left the old hub running → it now stops old hub processes first. Next: score lora-12b-v11b, queue data-v12/lora-12b-v12; P3-T1 merchant-confirmation read.
- 2026-11-28 18:56 IST CLAIMED (interactive): DGX reserve jobs — always hold PAA's 2 cards (owner)

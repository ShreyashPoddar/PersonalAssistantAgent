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

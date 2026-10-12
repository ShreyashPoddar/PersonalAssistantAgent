# HANDOFF — PAA (Personal Assistant Agent, Android, Kotlin/Compose)
(Older detailed history: `git log -p HANDOFF.md`.)

## Goal
- Personal Android assistant on owner's phone (iQOO Z7 Pro I2301, Android 15, 8 GB, Dimensity 7200): read WhatsApp/Telegram chats on-device, auto-schedule real tasks (Hinglish, deadlines spread over messages), voice assistant "Oyee PA" (owner says "Oyee P-A" letters), call features, automations.
- Current work: executing `PAA_EXECUTION_PLAN.md` (bugs B1–B22, speed S0–S7, voice lock V1–V2, cloud C1–C3, automation A1–A4, UX M6). Done = built, unit-tested, eval passes, verified on phone.

## Done (all committed; `git log --oneline`)
- Build+tests: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew -q assembleDebug testDebugUnitTest` — all pass at HEAD.
- Eval harness `python tools/eval_chats.py --serial emulator-5554` (15 scenarios `tools/scenarios.json`; resets via DEBUG_INJECT `--ez reset true`, reads encrypted log via `--ez dumplog true`). Results: baseline 12/15 (`tools/eval_baseline.txt`), bug-fix build 15/15 (`tools/eval_fixes.txt`), S2 compact prompt 14/15 (`tools/eval_s2.txt`; failure "call pe baat karte hai" → "Call Aman", fixed by re-adding that example — NOT re-evaluated). Prompt tokens ~1200 → ~750.
- Plan tasks done (code+unit tests, emulator-level): B1 Recurrence.kt, B2 fitPrompt budget, B3 async cancellable inference, B4, B5, B6 popup follow-up + Gemini history, B7 reschedule_task tool, B8 removed QuickScheduleReceiver, B9 RepositoryEntryPoint, B10 getNextTasks/getDueBetween, B11 persisted lastSeen + own replies outgoing (verified: WhatsApp incoming 1:1 has sender, selfDisplayName "You"), B12 reboot notification, B13 encrypted detection_log.enc, B14 chat_history.pending (DB v5), B17, B18, B20 keep rules (assembleRelease builds), B21 local_model.task rename, B22, S0 stats on 🧠 card, S1 TaskSignal, S2, S3 cloneSession prefix cache, S4, S5, S6 Android on-device hi-IN ASR for recordings, V1/V2 voice lock (VoicePrint.kt, assets/model-spk), C1 ClaudeClient, C2 PrivacyBoundaryTest, A1 WhatsApp send, A2a PAA calls you, A3 hackathon RegisterActivity, A4.2 FollowUpWorker, A4.3 auto-reply during calls, A4.5 local morning plan, M6 About-me settings.
- Phone-verified earlier: chat reading works; Gemma 3n CPU 40–90 s/msg; post-call recording pipeline (old Vosk path).

## Decisions
- Gemma 3n E2B on CPU only for models >1.5 GB (GPU copy → LOW_MEMORY kill). Gemma 3 1B rejected (bad quality).
- Live other-side call audio impossible on iQOO (DOWNLINK/CALL blocked, no call captions, recorder writes after hang-up). Rejected: root, cloud conference bot, Phone Link, speaker hack. Built: own-voice/speaker live + post-call recording.
- Voice commands: regex → Gemini (10 s timeout) → Gemma only offline.
- Few-shot names leaked into tasks → placeholder pattern + PROMPT_EXAMPLE_WORDS filter on titles only.
- Hackathons: assisted autofill, never auto-submit. A2b (AI calls to friends) not built: paid, needs approval.
- B16 skipped: PSS ~3.5 GB with model; Hindi model ~80 MB.

## Files
- `PAA_EXECUTION_PLAN.md` — task specs.
- `app/src/main/java/com/paa/assistant/services/ChatMessageProcessor.kt` — pipeline, bursts (BURST_MS 20 s), groundedWhen, mergeSameWork, AFFIRMATIVE, TaskSignal gate.
- `.../core/ai/LocalLlm.kt` — ask() async + prefix cache, fitPrompt, chatPromptPrefix/buildChatPrompt (companion).
- `.../services/WakeListenerService.kt` — EN/HI wake grammars, voice-lock verify, in-call analysis.
- `.../services/CallRecordingProcessor.kt` — recordings; transcribeWithAndroid + Vosk fallback.
- `.../core/messaging/WhatsAppSender.kt`, `ContactResolver.kt`; `.../services/PaaCallService.kt`, `PaaCallActivity.kt`; `.../core/hackathon/*`; `.../workers/FollowUpWorker.kt`; `.../core/profile/UserProfile.kt` (prefs-backed).
- `app/src/debug/.../DebugInjectReceiver.kt` — extras `text/chat/sender/group/out`, `reset`, `dumplog`.

## Open issues
- Nothing from 2026-10-03 evening installed on phone yet.
- Unverified on phone: WhatsApp ids `com.whatsapp:id/entry`, `com.whatsapp:id/send` (A1 auto-tap); voice-lock THRESHOLD 0.55 (PC: same voice 0.67–0.89, other 0.24–0.41; needs ~2.5 s speech after wake); PaaCall self-managed call; RegisterActivity autofill; S6 hi-IN availability; release smoke test (separate signing would wipe emulator model).
- S3 prefix-cache speed effect unmeasured.
- Not started: S7 (openWakeWord/Porcupine), A2b, A4.1 Classroom, A4.4 SMS, A4.6–A4.8, B19/C3 (owner must restrict Gemini key / proxy).

## User preferences
- Chat/call content never leaves phone; no TTS (except in-call earpiece and opt-in PAA-calls-you); alarms 07–19; names shreyash/shreyas, batch 2028, hackathon group hackstreet_Boys.
- Wants speed and real testing; terse replies; ask before subagents.
- Phone: `adb connect 192.168.1.3:<port>` (port changes — ask). Always `-s <serial>`; `export MSYS_NO_PATHCONV=1`.

## 2026-10-04 → 10-12 (uncommitted, built + unit-tested, installed on phone, NOT phone-verified)
- Wake listener: mic read error now restarts (was silently deaf after PAA call); resume waits for MODE_NORMAL. `wake_listener_enabled` was false on phone (user must toggle on).
- Accessibility: reopening a chat re-reads messages since last open (locked chats e.g. Ahana), trailing scan after throttle, own messages read from tick bubbles, WhatsApp 2.26.38 send button = `conversation_entry_action_button` (no `id/send`). DetectionLog MAX 40→200.
- Own model: `train/` (gen_data → train_sft → eval_model → export_task, see `train/README.md`), compact prompt `LocalLlm.buildCompactPrompt` used when `local_model.task.paa` marker exists (import of `paa-*.task`); `CompactPromptTest` = parity with `train/golden_prompt.txt`.
- DGX lane `scripts/dgx/` (separate from LocalAI: ~/paa, tmux paa-queue, branch gpu-queue, HF `ShreyashPoddar/paa-artifacts`), laptop control `tools/gpu.py`. Not installed — needs campus visit (`scripts/dgx/README.md`), Person A told, Gemma licences accepted, new GitHub PAT, control issue.
- Eval at that HEAD: 10/10 run passed, 5 not run (background limit) — `tools/eval_head.txt`.

## Next step
- User: phone-test wake listener + accessibility fixes; decide commit/push (jobs need code on `main`); campus install of DGX lane; then `python tools/gpu.py pipeline v1`.

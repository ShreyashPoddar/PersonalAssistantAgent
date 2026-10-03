# HANDOFF — PAA (Personal Assistant Agent, Android, Kotlin/Compose)

## Goal
- Personal Android assistant for user's own phone (iQOO Z7 Pro, model I2301, Android 15, 8 GB RAM, Dimensity 7200).
- Read WhatsApp/Telegram chats **on-device only** (never Gemini/Tavily), extract real tasks (incl. Hinglish, compact times "by 140"/"1145", multiple per message), auto-schedule with proper alarms; voice assistant; "Oyee PA" wake word incl. during calls.
- Done when: on the phone, local AI (Gemma 3n E2B) installs + 🔬 Test local AI shows ✅, chat messages appear in "Recent chat checks" and get scheduled, "Oyee PA" works.

## Done (verified by unit tests: 8 suites, all pass via `./gradlew testDebugUnitTest`; APK builds)
- Build: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDebug testDebugUnitTest` (system JDK 25 too new for Gradle 8.9). APK: `app/build/outputs/apk/debug/app-debug.apk` (~156 MB; latest 13:38).
- Gemini (model `gemini-3.6-flash`, key in `.env`) works; Google Search grounding returns 429 on this key → fallback to Tavily (`TAVILY_API_KEY` in `.env`, verified live) → ungrounded.
- Privacy: `core/privacy/PrivacyGuard.kt` (sourceApp prefix "WhatsApp" = local-only, excluded from cloud prompts); SQLCipher DB (`data/db/DatabaseEncryption.kt`, Keystore-wrapped passphrase, sqlcipher-android 4.12.0 — 4.19 needs compileSdk 37); allowBackup=false; receivers not exported; lockscreen-private notifications; no message text in logs.
- Local LLM: MediaPipe tasks-genai 0.10.35 (`core/ai/LocalLlm.kt`), model at `getExternalFilesDir("models")/gemma3-1b.task` (fixed filename regardless of model). maxTokens 2048, 45 s timeout, idle release 60 s. Prompt returns JSON tasks with title/when/priority/registration/confidence/reason; `parseVerdict` in companion (tested).
- Model import (`LocalLlm.importModel`, returns null or reason string): accepts `.task`, Kaggle `.tar.gz` (Apache commons-compress 1.28.0), `.zip` wrapper; checks ≥3.3 GB free; deletes invalid prior install.
- **Verified on emulator 13:39**: importing Kaggle `.tar.gz` now succeeds → `gemma3-1b.task` 3,136,226,711 bytes. Not yet verified: model loading / inference (Test local AI).
- Chat pipeline `services/ChatMessageProcessor.kt`: AI-first; incoming skipped if no AI; own sent msgs use rules (`core/router/CommitmentExtractor.kt`); confidence ≥0.75 auto-schedule, ≥0.4 ask (✓/✗ notification `services/TaskSuggestions.kt`), else ignore; per-chat history (8 msgs) as context; feedback table `task_feedback` (DB v3 migration 2→3) used as few-shot examples (✓/✗/Undo/Done).
- Rules: hackathon/registration only from personal chats or group `hackstreet_Boys`; other groups only if user named (`shreyash|shreyas`); internship posts for non-2028 batches dropped; Gmail not read; call/system notifications filtered.
- Dedupe by meaning (`ChatTaskScheduler.isSameTask`, keyword Jaccard); default deadline EOD 23:59; group request default +60 min; "shortly" = 20 min.
- Reminders `core/reminders/ReminderScheduler.kt` + `ReminderNotifier.kt`: early + due alarms (setAlarmClock), insistent alarm sound, v2 channels; ring window 7:00–19:00 (`UserProfile.ALARM_START_HOUR/END_HOUR`), outside → silent, early alerts pulled to 18:30, overnight deadlines ring at 7:00 ("Overdue"); alarms rescheduled on app start.
- Parser: Hinglish normalizer (`core/router/HinglishNormalizer.kt`), compact times + "next upcoming" am/pm, location reminders + `SavedPlaces` ("save this location as home/flat/college"), plan/list intents (`LocalCommandExecutor.kt`), Gemma fallback before Gemini for voice.
- Widget: live Room Flow, scrollable LazyColumn of all pending tasks, true count, done cancels alarm.
- Speech: Vosk 0.3.75 + bundled `app/src/main/assets/model-en-in` (vosk-model-small-en-in-0.4); used when Android on-device recognizer unavailable. Replies text-only (no TTS) in overlay; overlay shows over lock screen.
- "Oyee PA": `services/WakeListenerService.kt` (FGS microphone, continuous Vosk, 2-min in-memory transcript, wake regex `WAKE`, in-call → process transcript as `ChatApp.CALL` + earpiece TTS "Done, you will be reminded"; outside call → opens overlay). Toggle on 🧠 card.
- Diagnostics: `services/DetectionLog.kt` persisted to `filesDir/detection_log.json`; logs "⏳ checking…", service connect/disconnect, wake events; 🔬 Test local AI button (`LocalLlm.selfTest`).

## Decisions
- Gemma 3n E2B (`gemma-3n-E2B-it-int4.task`, Kaggle LiteRT variant) chosen over Llama 3.2 3B / Nemotron 4B / Phi-4-mini / Qwen (those `.litertlm` need LiteRT-LM SDK; Nemotron English-only). HF gated request still PENDING → used Kaggle.
- Model not bundled in APK (3 GB, install limits); imported via in-app picker.
- Root for call audio rejected (vivo bootloader locked, breaks UPI/banking). Live Caption route rejected: user's phone captions videos but NOT calls (iQOO blocks).
- Call audio: only user's own voice (and speakerphone) via accessibility-uid mic exemption; post-call recording option (iQOO auto-record → transcribe) offered, NOT built.
- Rule-based extraction for incoming removed (caused junk tasks from promos/call notifications).
- Hand-written tar reader replaced by commons-compress (not actually broken; see bug below).

## Files
- `app/src/main/java/com/paa/assistant/core/ai/LocalLlm.kt` — model import/validate (`isZip` accepts PK at byte 0 or 4), selfTest, prompts.
- `.../services/ChatMessageProcessor.kt`, `ChatTaskScheduler.kt`, `NotificationListener.kt`, `ChatTaskAccessibilityService.kt`, `WakeListenerService.kt`, `TaskSuggestions.kt`, `DetectionLog.kt`.
- `.../core/profile/UserProfile.kt` — names, grad year 2028, hackathon groups, alarm window, defaults.
- `.../ui/main/MainScreen.kt` — 🧠 card (install, Oyee PA switch, permission ✅/❌, Test local AI, cleanup, Recent chat checks).
- `app/build.gradle.kts` — deps (mediapipe, sqlcipher, vosk+jna aar, commons-compress), abiFilters arm64-v8a/x86_64, JUnit + org.json test deps.
- Tests: `app/src/test/java/com/paa/assistant/**` (LocalTaskParserTest, HinglishTest, ChatExtractionTest, VerdictParsingTest, RingWindowTest, WakePhraseTest, TarPaxTest, RealModelCheck).
- Models on PC: `C:\Users\shrey\Desktop\PAA\models\gemma-3n-E2B-it-int4.task` (sha256 a7f544cf…4200), `Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task`; archive `C:\Users\shrey\Downloads\gemma-3n-tflite-gemma-3n-e2b-it-int4-v1.tar.gz` (2,460,502,690 B).
- Emulator config changed: `~/.android/avd/Pixel_10_Pro_XL.avd/config.ini` (ramSize 8192, dataPartition 16G; backup `config.ini.bak-paa`).

## Open issues
- **Root cause of phone install failures**: real `.task` files start with `00 00 00 00 50 4b 03 04`; old `isZip` required PK at byte 0 → rejected good models ("doesn't contain a complete model"). Fixed in 13:38 build; not yet on phone.
- Earlier phone error (first build, tar.gz copied raw): `IllegalStateException: Failed to initialize engine: %sUNKNOWN: Unable to open zip archive ... zip_utils.cc:133`.
- **Emulator 13:43: Test local AI ✅** ("Local AI works (3.14 GB model, 25s). Reply: OK", CPU/XNNPack). Phone still unverified ( phone has ~1.9 GB free RAM — may OOM → fallback Gemma 3 1B).
- On phone: "Recent chat checks" was empty, nothing scheduled, Oyee PA stuck "initializing" — all likely because local AI invalid; re-test after new build.
- Phone USB: adb install hangs (no "Install via USB" prompt on iQOO); MTP folder listing very slow → Explorer looks crashed. Workaround: send APK via WhatsApp to self as Document.
- Emulator: Bluetooth "keeps stopping" dialog (harmless); emulator window was off-screen (moved via SetWindowPos).
- Oyee PA wake regex untested on real speech; in-call mic access on iQOO unverified.
- Unanswered: alias "pg/room" → flat instead of home? Build post-call recording processing (option 3)?

## User preferences
- Chat data processed only on-device; never send to Gemini/Tavily.
- Replies never read aloud (popup text only); exception: in-call earpiece "Done, you will be reminded".
- Alarms ring only 7 AM–7 PM.
- Default deadline EOD; group requests +1 h; names "shreyash"/"shreyas"; batch 2028; hackathon group `hackstreet_Boys`.
- Don't schedule promos/system notifications or mail registrations; analyze if really a task.
- Wants things tested (prefers emulator testing before phone); dislikes slowness.
- Global: terse replies, ask before spawning subagents.

## Speed (14:05 build)
- User: phone took ~2 min. Now GPU (OpenCL) backend first, CPU fallback; GPU tried only if libOpenCL.so exists; native GPU crash remembered via prefs `local_llm/gpu_attempt_pending|gpu_broken` → CPU forever. Manifest `uses-native-library libOpenCL.so`. Idle release 5 min, inference timeout 120 s. Test shows backend + load/reply split. Emulator: CPU, load 6s, reply 12s.

## Oyee PA fix (14:20 build)
- Cause: small Vosk model mishears "oyee pa" ("oh gee paw", "i thought") → regex never matched (tested on PC with python vosk + SAPI TTS, scratch scripts).
- Now: own AudioRecord loop feeding 2 Vosk recognizers: wake grammar (`WAKE_GRAMMAR`, `isWake` with word confidences) always; free-text only while in a call (transcript). 5 s wake cooldown. Old `WAKE` regex removed. Known false trigger: "okay pass…". Not tested with real voice / on phone.

## Call audio (other person's voice) — user insists on earpiece/earphone
- Added `services/CallAudioProbeService.kt` + 🧠 button "📞 Test call audio": waits ≤3 min for a call, measures loudness of DOWNLINK/CALL/COMMUNICATION/RECOGNITION/MIC (5 s each, no audio kept), logs verdict. Emulator: all blocked/silent (expected). If phone shows ✅ for DOWNLINK/CALL → wire that source into WakeListenerService in-call transcript. If ❌ → only options: speakerphone or post-call recording (iQOO recorder).

## Task scheduling fixes (14:40 build)
- Debug-only `app/src/debug/.../DebugInjectReceiver.kt`: `adb shell am broadcast -a com.paa.assistant.DEBUG_INJECT -p com.paa.assistant --es text "'...'" --es chat "'Name'" [--ez group true] [--ez out true]` (DUMP-protected).
- Bug 1: engine default session sampled (temp 0.8) → malformed JSON (`{"title": "Call me"}, "when"…`) → whole message dropped. Now `ask()` uses LlmInferenceSession topK 1, temp 0; `parseLenient` repairs stray brace / cut-off output (tests added).
- Bug 2: bare "haan kar dunga" → model copied few-shot task "Submit DBMS assignment". Prompt rule added + `PROMPT_EXAMPLE_WORDS` filter in ChatMessageProcessor.
- Verified on emulator via injection: Hinglish 2 tasks, ad ignored, group @mention, 1145, ₹500 by 4; alarms registered. NotificationListener → processor path itself not tested on emulator (no WhatsApp).

## Phone session 2026-10-02 ~15:00 (adb over Wi-Fi: `adb connect 192.168.1.3:40107`, port changes per session)
- Phone: notifications + accessibility ✅, WhatsApp messages DO reach PAA. GPU (OpenCL present) works: real chat message 5 s (was 67 s on CPU). `gpu_broken` pref had been set by a reinstall mid-load → cleared; beware reinstalling during inference.
- Owner's "oyee pa" decodes as "[unk] [unk] pa" or "oy(0.5) … pa" → isWake relaxed (oy/oye/oi ≥0.3; final "pa" ≥0.95 after [unk] in ≤4-word result). Fixed double listener start (`starting` flag). Awaiting user re-test.

- 15:05 "not reading chats" root cause: PAA killed LOW_MEMORY (rss 7.6 GB) — Gemma 3n on GPU too heavy for 8 GB phone. Now GPU only for models <1.5 GB; 3n on CPU (~40–75 s/message, background). Correction: earlier "5 s on GPU" claim was a measurement bug (it was 60 s).
- Gemma 3 1B tried on phone (17 s/msg, no crash) but quality unusable (scheduled ad, missed tasks) → reverted. Phone models dir now: `gemma3-1b.task` (= 3n, active), `gemma3-1b-small.task` (1B, unused, 0.55 GB, can delete).
- DebugInjectReceiver used goAsync → ANR kills during tests; fixed. Inference timeout now excludes mutex wait (queued msgs were timing out).
- Verified on phone 15:28: ₹500 by 4 ✅, "call pe baat karte hai" no task ✅, lab record by 9 AM ✅, Papa 2 tasks ✅, ad ignored ✅; no crashes. Possible speedup: shorter prompt (prefill ~1300 tokens dominates CPU time).

## 18:00–19:30 additions (built + unit-tested, NOT yet on phone — phone adb dropped)
- Call audio test on phone: DOWNLINK/CALL blocked, RECOGNITION -28 dB → iQOO blocks other side. Only speakerphone / post-call recording possible.
- Auto-complete: ChatMessageProcessor passes this chat's open tasks (`taskDao.getPendingChatTasks` filtered by sourceApp "(chat)", " in chat)", " to chat)") as `ChatContext.openTasks`; prompt asks for `"done": [n]`; matched tasks → COMPLETED, alarm cancelled, `ReminderNotifier.showAutoCompleted` with "↩ Not done" (`ACTION_REOPEN` → PENDING + reschedule). Trivial/group skips bypassed when chat has open tasks. Untested end-to-end (GOA "10 final na??" → "Hn bhai").
- Voice reschedule: new `LocalIntent.RESCHEDULE_TASK` (change/move/shift/make it… or "no no"/"for that" + time) → `TaskRepository.rescheduleTask(hint, due)` (hint or `TaskDao.getLastTouchedPending`). Always local. Tests `RescheduleTest`. User's phone has bogus tasks "Checking the guitar propository remind me" / "No no for that remind me" from before.

- Locked WhatsApp chats: notifications hide text. Accessibility `scanOpenChat` used to skip everything visible on first open → fixed: per-chat `chat_last_open` prefs; on opening, incoming bubbles newer than last open (≤12 h, last 10) are processed. Untested on phone.

## Calls (2026-10-02 evening)
- Tested: iQOO recorder (auto-record ON, `/sdcard/Recordings/Record/Call/<Name> YYYY-MM-DD HH-MM-SS.m4a`, AAC 48k mono) writes the file only AFTER hang-up → no live other-side audio possible. User rejected cloud-conference / Phone Link / speaker-hack alternatives; accepted: live own-voice+speaker features + post-call processing.
- English Vosk garbles Hindi calls; bundled `assets/model-hi` (vosk-model-small-hi-0.22 + uuid file, APK now ~200 MB). `VoskEngine.loadHindiModel()`.
- `services/CallRecordingProcessor.kt`: MediaStore ContentObserver (registered from NotificationListener.onListenerConnected) → new `%Record/Call%` audio after prefs `call_recordings/last_added` → MediaCodec decode → resample 16k → Vosk hi → `pieces()` (600 chars) → processor (ChatApp.CALL, chatName "call with X"), notification "📞 N tasks from your call with X". Needs READ_MEDIA_AUDIO (🧠 card "Music and audio" row). Transcribing 5-min call took ~6 min on phone; first AI run timed out (fixed: 600-char pieces, call timeout 240 s).
- WakeListenerService in call: free decoder = Hindi model; `analyzeDuringCall` dry-runs `processor.process(dryRun=)` on each new stretch → `prepared`; "Oyee PA" → `commandOf` (Hindi/English words) START listen mode (no window limit) / STOP → schedule since listen start / QUESTION → TTS next 3 tasks / default SCHEDULE last 2 min; instant commit of prepared + tail analysis; earpiece TTS replies (QUEUE_ADD). Untested live.
- Processor: `Prepared`, `commit()`, `process(msg, dryRun)`. Group msgs from others not mentioning owner skip AI again (auto-complete only from owner's replies in groups).
- Next feature requested after testing: automated messages / calls to friends.

## 20:40–21:15
- Owner says the wake word as "Oyee P-A" (letters). Both grammars updated (`WAKE_GRAMMAR` "oye p a"…, `WAKE_GRAMMAR_HI` "ओये पीए"…; Hindi decoder runs alongside English). First successful wake 20:56. Voice commands: Gemini first (10 s timeout, `GeminiResponse.failed`) → Gemma only offline (Gemma was slow + queued behind call jobs).
- Reschedule: bare "to 10" times, "… task" suffix stripped, fuzzy title match in `TaskRepository.rescheduleTask`. Intent logged with Log.i.
- Copy filter extended to owner's learned ✓/✗ examples (Alo call produced fake "Check GitHub repository").
- Call recordings: 4-way parallel transcription (`slicesAtPauses`); `last_added` saved only after processing. Dadaji 7.5-min call: transcription 3m23s (phone heavily loaded), then AI ~2 min per 600-char piece → AI is the bottleneck; Vosk-small-hi transcripts are noisy ("nonsensical" per AI). Ideas: Google on-device SODA hi-IN for recordings (SODA en-IN present on phone), or Gemini for call transcripts (needs user OK — privacy).
- Phone memory: 92 MB free, 4 GB swap during call (Gemma 3n + 2 Vosk models + 3 decoders).
- Owner's sent messages now check ALL open tasks (≤8) for "done"; trivial replies only when same-chat tasks exist. Prompt asks for person's name in titles.

## 2026-10-03: multi-message understanding (emulator-tested; phone adb dropped overnight)
- Bursts: `ChatMessageProcessor.process` saves each msg to new encrypted table `chat_history` (DB v4, `ChatHistoryDao`, MIGRATION_3_4) and waits `BURST_MS`=20 s of quiet per chat; then `merge()` → one ChatMessage("Speaker: text" lines, `burst=true`) → `processNow`. CALL/dryRun bypass. History in prompt: 12 saved lines, "use earlier lines for details".
- Open tasks shown with due + "guessed" flag; prompt returns `"update": [{n, when, title}]` (`ChatVerdict.updates`) → `ChatTaskScheduler.update()`. `#autodue` tag on guessed deadlines; real deadline replaces it even if later.
- `mergeSameWork` merges duplicate tasks in one verdict, joining time words. Candidate matching an open task (isSameTask / ≥2 keywords) → update (either direction), `anchoredDue` keeps the task's day when phrase has no day word.
- Parser fixes: "day after tomorrow"/parso was +1 day (now +2); bare whole hours 1–6 → PM.
- 📌 log lines now show "· due EEE d MMM h:mm a".

- 19:30–19:50: `ChatTaskScheduler.isSameTask`/`sharesWork` + `distinct()` (each title has its own non-GENERIC word → different: biology vs chemistry, Riya vs Kabir; test `SameTaskTest`). `groundedWhen()` rejects model time words (numbers / day words via DAY_GROUPS) not present in the chat → falls back to the newest message's text (model had invented "kal 5 baje"). Logs show `(from "<when>")`.
- Voice popup vs wake listener mic fight (popup went deaf after 1–2 commands, listener reclaimed mic after 30 s): `WakeListenerService.pauseForPopup/resumeAfterPopup` (ACTION_PAUSE/RESUME, `popupOpen`, 5-min safety) called from VoiceOverlayActivity onResume/onPause.
- Phone adb now 192.168.1.3:39159. NOTE `adb -e` matches Wi-Fi devices too → always use `-s emulator-5554`.

- 19:51 emulator: full scenario passes — burst "essay bhej dena / parso tak / 11 baje se pehle" → one task Mon 5 Oct 11:00 AM; later "4 baje tak bhi chalega" → 🔁 updated to Mon 5 Oct 4:00 PM (no duplicate).
- 19:55 Full code review done → `PAA_EXECUTION_PLAN.md` (bugs B1–B22, speed S0–S7, voice lock V1–V2, cloud brain C1–C3, automation A1–A4, UX M6, milestone order). Owner also asked: respond only to owner's voice (plan V1/V2: Vosk speaker model x-vector).

## Next step
- Emulator (emulator-5554, PAA installed, model imported): open PAA, tap "🔬 Test local AI", read result (`adb -e shell uiautomator dump` / screencap) and `adb -e logcat -d | grep -iE "LocalLlm|FATAL"`; if ✅, have user install `app/build/outputs/apk/debug/app-debug.apk` (13:38) on phone via WhatsApp Document and re-import the `.tar.gz`.

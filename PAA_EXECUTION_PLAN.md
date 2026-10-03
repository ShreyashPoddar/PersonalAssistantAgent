# PAA — Execution Plan (bugs, speed, features, automation)

Written 2026-10-03 after a full read of the code (`app/src/main/java/com/paa/assistant/**`, ~11k lines)
and two days of testing on the owner's phone (iQOO Z7 Pro I2301, Android 15, 8 GB RAM, Dimensity 7200)
and the emulator. **This file is for the agent that implements it. Do the tasks in order. Do not skip
the "Verify" step of any task.** `HANDOFF.md` has the history and current state; read it once first.

---

## 0. Rules and setup (read before touching code)

### 0.1 Owner's rules — never break these
1. **Chat and call content never leaves the phone.** WhatsApp/Telegram messages, call transcripts,
   chat-derived tasks (`sourceApp` starting with `WhatsApp`, see `core/privacy/PrivacyGuard.kt`) must
   never go to Gemini, Claude, Tavily or any server. Only on-device models (Gemma via `LocalLlm`, Vosk).
2. **No text-to-speech replies**, except: the in-call earpiece replies in `WakeListenerService.say()`,
   and the opt-in "PAA calls you" feature (task A2a) — ask the owner before adding any other spoken output.
3. Alarms ring only 07:00–19:00 (`UserProfile.ALARM_START_HOUR/END_HOUR`); outside that, silent.
4. Owner names: "shreyash"/"shreyas"; batch 2028; hackathon group `hackstreet_Boys`.
5. Anything that acts **on other people** (sending a message, placing a call, submitting a form) must
   show the exact content and get an explicit tap from the owner the first time, and stay rate-limited.
6. Keep replies to the owner short. Ask before spawning sub-agents.

### 0.2 Environment (Windows, Git Bash)
- Project root: `C:\Users\shrey\Desktop\PAA`. **Not a git repo yet** → task M0.1 fixes that.
- Build + unit tests (system JDK 25 is too new; use Android Studio's JBR):
  ```bash
  export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
  ./gradlew -q assembleDebug testDebugUnitTest
  ```
  Failing test details: `app/build/test-results/testDebugUnitTest/*.xml` (grep `<failure`).
- APK: `app/build/outputs/apk/debug/app-debug.apk` (~200 MB).
- adb: `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb"`. Always `export MSYS_NO_PATHCONV=1` first
  (Git Bash mangles `/sdcard/...` paths otherwise).
- **Always pass `-s <serial>`.** `adb -e` also matches Wi-Fi devices.
  - Emulator: `emulator-5554` (AVD `Pixel_10_Pro_XL`; start with
    `"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd Pixel_10_Pro_XL -no-snapshot-save &`).
    The Gemma model is already imported there.
  - Phone: wireless debugging, `adb connect 192.168.1.3:<port>` — **the port changes**; ask the owner.
- Inject a fake chat message (debug builds only, `app/src/debug/.../DebugInjectReceiver.kt`):
  ```bash
  adb -s emulator-5554 shell am broadcast -a com.paa.assistant.DEBUG_INJECT -p com.paa.assistant \
    --es text "'kal 5 baje tak notes bhej dena'" --es chat "'Riya Test'" [--ez group true] [--ez out true] [--es sender "'Name'"]
  ```
  Messages of one chat are read together after **20 s of quiet** (`BURST_MS`), then the AI takes
  30–90 s on CPU. Read results:
  ```bash
  adb -s emulator-5554 shell run-as com.paa.assistant cat files/detection_log.json
  ```
- Kotlin regex gotcha: in Kotlin source a regex `\b` must be written `"\\b"`. When editing through
  Python/sed scripts the backslashes get eaten; prefer the Edit tool and re-check the line.
- **Do not reinstall the app while it is transcribing a call or running the AI** (check the log for
  `⏳`). The install kills the job.
- Keep code style: short doc comments explaining *why*, no comment noise, names like surrounding code.

### 0.3 Definition of done for every task
1. Unit tests added/updated and `./gradlew -q assembleDebug testDebugUnitTest` passes.
2. The task's own **Verify** scenario passes on the emulator (or the phone if it needs real hardware —
   then ask the owner to do the physical part and read the logs yourself).
3. One line per task appended to `HANDOFF.md` (what changed, file names, anything left open).
4. Commit: `git add -A && git commit -m "<task id>: <summary>"`.

---

## 1. How the app works today (1-minute map)

```
WhatsApp notification ─► NotificationListener ─┐
Open chat on screen ───► ChatTaskAccessibilityService ─┤ ChatMessage
Own sent message ──────► ChatTaskAccessibilityService ─┘
        │
        ▼
ChatMessageProcessor.process()  → saves line to chat_history (encrypted Room table)
        │  waits 20 s for the rest of the burst, merges lines
        ▼
processNow(): rules (group mention, internship batch, hackathon links)
        → LocalLlm.extractTasks()  (Gemma 3n E2B, MediaPipe, CPU, ~40–90 s)
        → verdict: tasks / done / update
        → ChatTaskScheduler.schedule()/update() → TaskRepository → ReminderScheduler (AlarmManager)
        → DetectionLog (shown as "Recent chat checks" on the 🧠 card)

"Oyee PA" ─► WakeListenerService (mic always on, Vosk grammar decoders EN+HI)
        ├─ not in call → VoiceOverlayActivity (popup) → SpeechManager → IntentRouter
        │                  → LocalTaskParser (regex) | Gemini (cloud, non-private) | Gemma (offline)
        └─ in call → Hindi free decoder transcript → background pre-analysis → schedule on command
Call ends ─► iQOO recorder saves m4a ─► CallRecordingProcessor (MediaStore observer)
        → decode → Vosk Hindi (4 threads) → 600-char pieces → ChatMessageProcessor (ChatApp.CALL)
```
Key files: `services/ChatMessageProcessor.kt`, `core/ai/LocalLlm.kt`, `services/ChatTaskScheduler.kt`,
`services/WakeListenerService.kt`, `services/CallRecordingProcessor.kt`, `core/router/LocalTaskParser.kt`,
`ui/overlay/VoiceOverlayViewModel.kt`, `core/audio/SpeechManager.kt`, `core/reminders/*`.

---

## M0 — Safety net (do first)

### M0.1 Put the project under git
1. Append to `.gitignore`: `*.apk`, `*.zip`, `/*.png`, `/app/build`, `/build`, `.kotlin/`.
2. `git init && git add -A && git commit -m "Baseline before execution plan"`.
3. Verify: `git status` is clean; `git ls-files | grep -E "\.env|\.apk|/models/"` prints nothing.

### M0.2 Golden-conversation eval harness (regression tests for the AI pipeline)
Why: every AI change so far broke something else. This makes it measurable.
1. Create `tools/scenarios.json`: a list of scenarios. Each scenario:
   ```json
   {"id": "burst_parso", "chat": "Eval Burst1", "group": false,
    "steps": [{"text": "bro english ka essay bhej dena"}, {"text": "parso tak"}, {"text": "11 baje se pehle"},
              {"wait": "result"},
              {"text": "sorry yaar, 4 baje tak bhi chalega"}, {"wait": "result"}],
    "expect": [{"contains": "📌 Send English essay", "due_day_offset": 2, "due_hour": 11},
               {"contains": "🔁 updated", "due_day_offset": 2, "due_hour": 16}]}
   ```
   Start with ≥15 scenarios taken from `HANDOFF.md` / this plan: Hinglish two-task message, ad
   ("Flat 50% off"), "call pe baat karte hai" (no task), group @mention ("@shreyash bring the projector
   at 1145"), "haan kar dunga" with no context (no task), deadline spread over 3 messages, later
   deadline change, "done" reply closing a task ("10 final na??" then own "Hn bhai"), two different
   subjects in two chats (must stay 2 tasks), internship for 2027 batch (skipped), hackathon link in
   a random group (skipped).
   Use a **unique chat name per run** (append a timestamp) so earlier runs don't interfere.
2. Create `tools/eval_chats.py` (Python 3.12 is installed): for each scenario, send steps through
   `adb shell am broadcast … DEBUG_INJECT` (use `subprocess.run([...], stdin=subprocess.DEVNULL)`),
   wait for a result line (poll `detection_log.json` every 5 s, timeout 600 s; a result is any entry
   for that chat whose `r` does not contain `⏳` or `🧠`), parse the `· due EEE d MMM h:mm a` text, and
   compare with `expect`. Print a pass/fail table and exit non-zero on any failure.
   Args: `--serial emulator-5554`, `--only <id>`.
3. Verify: `python tools/eval_chats.py --serial emulator-5554` runs and prints the table. Record the
   baseline pass rate in `HANDOFF.md`. **Run it after every task that touches the chat pipeline.**

---

## M1 — Bugs (P0). Fix in this order.

### B1 — Recurring tasks never repeat
- Where: `TaskEntity.recurrenceRule` is saved (MainScreen editor, parser), but nothing reads it after
  an alarm fires (`services/ReminderReceiver.kt`, `ReminderActionReceiver`).
- Fix:
  1. New `core/reminders/Recurrence.kt` with `fun next(rule: String, after: Long): Long?` supporting
     `FREQ=DAILY`, `FREQ=WEEKLY;BYDAY=MO,TU…`, `FREQ=MONTHLY;BYMONTHDAY=n`, with optional `RRULE:` prefix.
  2. In `ReminderActionReceiver` ACTION_DONE and in `ReminderReceiver.fireReminder` for `KIND_DUE`:
     if the task has a rule, compute `next(rule, due)`, update `dueTimestamp`, keep status `PENDING`,
     re-schedule via `TaskRepository.updateTask` (use a Hilt `@EntryPoint` to get the repository
     inside the receiver). Done on a recurring task = done for this occurrence only.
- Verify: unit test `RecurrenceTest` (daily, weekly Mon/Wed from a Tuesday, monthly 31st → skips
  short months). Emulator: create a daily task due in 2 min from the app, wait for the alarm, tap
  ✓ Done, check it now shows tomorrow's time.

### B2 — Prompt can exceed the model's 2048-token window → local AI fails on busy chats
- Where: `LocalLlm.createEngine()` `setMaxTokens(2048)` (input + output together);
  `buildChatPrompt()` = ~1300 tokens of instructions + up to 12 history lines + 8 learned examples +
  8 open tasks + a burst of up to 1400 chars. That can pass 2048 → inference error → message dropped.
- Fix:
  1. Add `fun tokens(text: String): Int` using `LlmInference.sizeInTokens(text)` (check the method
     exists in MediaPipe tasks-genai 0.10.35; if not, estimate `chars / 3` for Latin, `chars / 1.5`
     for Devanagari).
  2. In `buildChatPrompt`, build the prompt in parts and enforce a budget of 2048 − 350 (reserved for
     the JSON answer): drop learned examples first, then trim history to the newest lines, then open
     tasks to 4, then cut the message from the front for calls.
  3. Log `"prompt N tokens"` in Logcat (tag LocalLlm, no message text).
- Verify: unit test for the trimming order (make the budget logic a pure function). Eval harness
  still passes. Inject a burst of 6 long messages in a chat with 12 history lines → no `❌ local AI failed`.

### B3 — Timeouts don't actually stop the model, so the queue stalls
- Where: `LocalLlm.extractTasks/selfTest` use `withTimeout { runInterruptible { ask(...) } }`.
  MediaPipe's native `generateResponse()` ignores thread interrupts, so the coroutine can't return
  until generation ends; the mutex stays held. `generate()` (used by `planTasks`, `interpretWithGemma`)
  has **no timeout at all**.
- Fix: in `ask()`, use `session.generateResponseAsync()` with a result listener and a
  `suspendCancellableCoroutine`; on cancellation call `session.cancelGenerateResponseAsync()` (verify
  the method name in 0.10.35; if it doesn't exist, close the session). Give `generate()` a 60 s timeout.
- Verify: unit test isn't possible (native); on the emulator, temporarily set the timeout to 5 s,
  inject a message, confirm the log shows the timeout **and** the next injected message starts
  within ~1 s (Logcat timestamps). Restore the timeout.

### B4 — Closing the voice popup kills text-to-speech and the recognizer app-wide
- Where: `VoiceOverlayActivity.onDestroy()` → `viewModel.release()` → `SpeechManager.release()`.
  `SpeechManager` is a `@Singleton`; after release its TTS is null forever (never re-initialised).
- Fix: `VoiceOverlayViewModel.release()` should only `stopListening()`/`stopSpeaking()`. Make
  `SpeechManager.speak()` lazily re-init TTS if null. Remove `release()` from the activity path.
- Verify: open popup → close → reopen → give a command → works; Logcat has no "TTS not ready".

### B5 — One transient speech error switches the popup to the weak English Vosk model for good
- Where: `SpeechManager.onError` sets `useVosk = true` on network/language errors and never resets it.
- Fix: reset `useVosk = false` and `onDeviceUnsupported = false` at the start of each popup session
  (`VoiceOverlayViewModel.init`) unless `SpeechRecognizer.isOnDeviceRecognitionAvailable()` is false.
  Log which engine handled each command (`Log.i`, no text).
- Verify: toggle airplane mode on during a command (forces error) → next popup session uses Android's
  recognizer again (Logcat `SodaSpeechRecognizer` lines).

### B6 — Voice popup handles one command per tap and has no memory of the previous turn
- Where: `VoiceOverlayViewModel.deliverResponse()` goes IDLE; every command is independent, so
  "no no, make it 12" or "and also remind Riya" can't refer back.
- Fix:
  1. After a response, if the popup was opened by voice ("auto_listen"), start listening again for a
     follow-up window of 8 s (show "Anything else?"). Stop on silence/no-match without an error message.
  2. Keep the last 6 turns (user text + reply + `lastTaskId` touched) in the ViewModel. Pass them to
     Gemini as chat history (`model.startChat(history = …)`); for local intents, "it/that/usko" =
     `lastTaskId`.
- Verify: "remind me to call mom at 6 pm" → (no tap) "no make it 7" → one task at 7 PM.

### B7 — Gemini can't reschedule → rephrased reschedules create duplicate tasks
- Where: `GeminiClient.buildToolDeclarations()` has create/update-status/calendar/… but no reschedule.
- Fix: add `reschedule_task(task_title_hint: string, new_due_iso: string)`; handle it in both
  `VoiceOverlayViewModel.executeGeminiToolCall` and `MainViewModel` via `TaskRepository.rescheduleTask`.
  Add to the system prompt (`core/ai/PromptTemplates.kt`): "To change the time of an existing task
  always call reschedule_task, never create_task".
- Verify: say "shift the github thing to tomorrow evening" (goes to Gemini) → existing task moves, no
  new task.

### B8 — `QuickScheduleReceiver` is dead code with its own uncancellable alarms
- Where: `services/QuickScheduleReceiver.kt` (never referenced) + manifest entry.
- Fix: delete the file and the `<receiver>` block.
- Verify: build passes; `grep -rn QuickSchedule app/src` is empty.

### B9 — Notification "✓ Done" bypasses the repository
- Where: `ReminderActionReceiver` ACTION_DONE calls `dao.updateStatus` directly → geofence stays
  registered, recurrence (B1) skipped.
- Fix: use `TaskRepository.completeTask()` (via EntryPoint), keep the feedback insert.
- Verify: location task → ✓ Done from notification → `adb shell dumpsys activity service
  com.google.android.gms/.location...` not needed; just check `GeofenceManager.removeGeofence` is
  called (Log.i) and the task disappears from the widget.

### B10 — "Next tasks" lists undated and overdue tasks first; evening summary counts wrong tasks
- Where: `TaskDao.getUpcomingTasks` orders `dueTimestamp ASC` → NULLs first in SQLite; used by
  `WakeListenerService.nextTasksSummary`, `EveningRetrospectiveWorker` ("pending tasks from today"
  but counts any 10 pending), `MorningBriefingWorker`.
- Fix: add `getNextTasks(now, limit)` (`dueTimestamp >= now ORDER BY dueTimestamp`) and
  `getDueBetween(start, end)`. Use them in those three places. Evening message: count tasks due today
  and still pending.
- Verify: unit test on the DAO is hard (SQLCipher) → test the query logic by making the callers use
  a pure filter function and testing that; manual check of the in-call "what's next" answer.

### B11 — Notification listener re-processes the last 10 minutes after every restart; own replies counted as incoming
- Where: `NotificationListener.lastSeen` is an in-memory map; default "since" is now−10 min.
  Also MessagingStyle includes the owner's own replies (sent from the notification or another device)
  with a null sender / the self Person → treated as a message *from* the chat.
- Fix: persist `lastSeen` per chat key in SharedPreferences (hash the key). Detect self messages:
  `b.getCharSequence("sender") == null` **or** sender equals
  `extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)` / the `android.messagingUser` Person
  name → set `outgoing = true`.
- Verify: force-stop the app and reopen within 10 min → no duplicate "⏳ checking" lines for old
  messages (phone test). Reply from the notification → log shows `→ <chat>` (outgoing).

### B12 — "Oyee PA" is off after a reboot until the app is opened
- Where: nothing starts `WakeListenerService` on boot; Android 14+ forbids starting a
  microphone foreground service from the background.
- Fix: on `BOOT_COMPLETED` (ReminderReceiver), if the listener is enabled, post a low-priority
  notification "Tap to turn Oyee PA back on" whose tap starts the service from the foreground. Also
  keep `ChatTaskAccessibilityService.onServiceConnected` trying (it may be allowed) and log the result.
- Verify on the phone: reboot → notification appears (or listener already running) → tap → "✅ listening".

### B13 — Detection log stores message snippets unencrypted
- Where: `services/DetectionLog.kt` writes `filesDir/detection_log.json` in plain text, while the
  rest of the data is SQLCipher-encrypted.
- Fix: move entries to a Room table `detection_log` (DB migration 4→5, keep MAX 60 rows) and expose a
  `Flow`. Keep the same `DetectionLog.add()` API (it can launch on an IO scope). Update the eval
  harness to read via a debug-only `content query` provider or a debug broadcast that dumps the log to
  Logcat (tag `PAA_LOG`, debug builds only).
- Verify: `run-as … cat files/detection_log.json` no longer exists; UI still shows entries; eval passes.

### B14 — Processing in the background is lost if the app dies during the 20 s burst wait
- Where: `ChatMessageProcessor.bursts` is in memory.
- Fix: add column `processed INTEGER NOT NULL DEFAULT 0` to `chat_history` (migration), mark lines
  processed after `processNow`; on processor creation, re-queue chats with unprocessed lines younger
  than 15 min.
- Verify: inject 2 messages, force-stop the app within 20 s, reopen → they get processed.

### B15 — Heavy jobs run on top of each other (call transcription + AI + in-call decoders)
- Covered in S5 (HeavyWork lock). Listed here so it isn't forgotten.

### B16 — Hindi speech model is loaded all the time (~80 MB) even outside calls
- Where: `WakeListenerService.startListening()` loads `loadHindiModel()` for the free decoder and
  the Hindi wake grammar.
- Fix: keep the Hindi *wake grammar* (needed for "ओये पीए"), but create the Hindi *free* recognizer
  only when `inCall()` becomes true and close it 2 min after the call ends. (The model object is
  shared; the big cost is the free-decoder recognizer state.) Measure before/after with
  `adb shell dumpsys meminfo com.paa.assistant` (TOTAL PSS) and record both numbers.
- Verify: memory number drops outside calls; in-call transcription still works (call yourself on
  speaker on the phone, check `← your call` log lines).

### B17 — Learned examples are shuffled → non-deterministic prompts
- Where: `ChatMessageProcessor.learnedExamples()` `.shuffled()`.
- Fix: newest first, alternate yes/no, max 4 total. Verify: unit-free; eval passes.

### B18 — Morning briefing "🎙️ Listen" button reads aloud (owner doesn't want TTS)
- Where: `MorningBriefingWorker.postBriefingNotification` adds a Listen action with `speak_text`.
- Fix: remove the action. Also stop sending the task list to Gemini for the briefing: build the
  summary locally (Gemma or plain text) — the briefing is about the owner's tasks.

### B19 — Gemini API key is compiled into the APK
- Where: `app/build.gradle.kts` `buildConfigField GEMINI_API_KEY`. Anyone with the APK can extract it.
- Fix (owner action + code): (a) in Google Cloud console restrict the key to Android app
  `com.paa.assistant` + the debug/release SHA-1 (print it with `./gradlew signingReport`), and to the
  Generative Language API only; (b) longer term use the proxy from C3. Do not share APKs publicly.

### B20 — Release build never tested (minify is on)
- Fix: add keep rules to `app/proguard-rules.pro` for `org.vosk.**`, `com.sun.jna.**`,
  `com.google.mediapipe.**`, `net.zetetic.**`, Room entities, Hilt-generated classes; build
  `assembleRelease` (debug-signed is fine for personal use) and smoke-test: app opens, AI test ✅,
  injected message… (DEBUG_INJECT doesn't exist in release — test with a real WhatsApp message).

### B21 — Model file name is wrong and a 0.55 GB unused model sits on the phone
- Where: `LocalLlm.MODEL_FILE_NAME = "gemma3-1b.task"` though the active model is Gemma 3n E2B.
  Phone also has `files/models/gemma3-1b-small.task` (unused).
- Fix: rename constant to `local_model.task`; on startup, if the old file exists and the new doesn't,
  `renameTo`. Delete `gemma3-1b-small.task` (ask the owner first — it's on their phone).

### B22 — Accessibility "sent" detection can fire on clearing the text box
- Where: `ChatTaskAccessibilityService` TYPE_VIEW_TEXT_CHANGED treats "box cleared in one go" as sent.
- Fix: only count it as sent if within 1.5 s a new outgoing bubble with that text appears
  (`message_text` node whose bubble has the `status` tick) — otherwise ignore. Keep the send-button path.
- Verify on phone: type text, select-all + delete → no `→` log line; type + send → one log line.

---

## M2 — Speed, memory, and "don't hang the phone"

Measured on the phone: Gemma 3n E2B on CPU = 1 s load (cached), **40–90 s per chat message**
(prefill of a ~1,500-token prompt dominates); GPU backend loaded a second 3 GB copy and Android
killed PAA (LOW_MEMORY), so big models run on CPU only (`createEngine`). During a call the phone had
92 MB free RAM and 4 GB in swap.

### S0 — Benchmark screen first (so every change is measured)
Add a debug section on the 🧠 card ("⚙️ Performance") showing: last prompt tokens, prefill ms, decode
ms, total ms (from B2/B3 logging), last call transcription real-time factor, current PSS
(`ActivityManager.getProcessMemoryInfo`), and whether the LLM engine is loaded. Record a baseline in
`HANDOFF.md` before S1–S7.

### S1 — Don't run the AI on messages with no task signal (biggest win)
1. New pure object `core/router/TaskSignal.kt`: `fun hasSignal(text: String, outgoing: Boolean): Boolean`.
   True if the message contains any of: a time/date (reuse `LocalTaskParser.resolveWhen(text) != null`
   or words kal/aaj/parso/tomorrow/tonight/baje/pm/am/deadline/last date), a request/promise verb in
   English or Hinglish (send/bhej, call, pay, submit, bring/lana, register, kar dena, karna hai,
   remind, yaad, done/ho gaya/kar diya, pls/please + verb), a link, a `?` addressed to the owner,
   or the owner's name.
2. In `processNow`, before the AI: if `!hasSignal(...)` and the chat has no open tasks → log
   `skipped: no task words` and return. Keep the burst merge (signal over the whole burst).
3. Unit test with ≥40 labelled messages (put them in `TaskSignalTest`): every true task in the eval
   scenarios must have signal; chatter like "haha", "😂", "kya scene", "Samosa party ke baju mei",
   "Abe saale" must not.
- Verify: eval harness still passes; on the phone, count AI runs per hour before/after from Logcat.

### S2 — Shrink the prompt
In `LocalLlm.buildChatPrompt` cut the base instructions to ≤ 600 tokens: one compact rule list,
3 short few-shot examples instead of 7, remove duplicated explanations. Keep: owner identity, who must
act, Hinglish times, "use earlier lines for details", JSON format, done/update fields. Measure with S0
(prefill ms) and the eval harness (quality must not drop).

### S3 — Reuse the fixed part of the prompt (prefix/KV cache)
The instruction prefix is identical on every call. Check whether MediaPipe tasks-genai 0.10.35 has
`LlmInferenceSession.cloneSession()`. If yes: keep a "base" session that already ate the static
prefix (built once after engine load), and for each message `cloneSession()` + add only the dynamic
part. If not, evaluate **LiteRT-LM** (`com.google.ai.edge.litertlm`, Gemma 3n `.litertlm` from
HuggingFace/Kaggle) which supports prefix caching: build a throwaway branch, run the same eval and S0
benchmark on the phone, switch only if faster at equal quality **and** memory stays < 3.5 GB PSS.

### S4 — Calls: less work while talking
In `WakeListenerService.analyzeDuringCall`: analyse only when ≥ 400 new characters **and** S1 says the
new text has a task signal; at most one analysis per 60 s. Pause chat-message AI while `inCall()`
(the processor can check a shared flag and re-queue). Keep the "Oyee PA" instant path.

### S5 — One heavy job at a time, lowest priority
1. `core/HeavyWork.kt`: an app-wide `Mutex` + a single-thread dispatcher whose thread runs at
   `Process.THREAD_PRIORITY_BACKGROUND`. LLM inference and call transcription both run under it.
2. Call transcription threads: 4 when charging, 2 otherwise (`BatteryManager.isCharging`).
3. `PAAApplication.onTrimMemory(level >= TRIM_MEMORY_RUNNING_LOW)` → `LocalLlm.releaseNow()`
   (close engine if idle). Release the engine 2 min after screen-off (register `ACTION_SCREEN_OFF`).
4. Defer call-recording processing while a call is active or battery < 20 % and not charging.
- Verify: during a test call + injected messages, the phone UI stays responsive (owner judges) and
  `dumpsys meminfo` PSS stays < 3.5 GB; no LOW_MEMORY in `dumpsys activity exit-info com.paa.assistant`.

### S6 — Better and faster call transcription: Android's on-device recognizer on the recording
Vosk-small-Hindi transcripts are noisy (the model called them "nonsensical"). On Android 13+ the
system recognizer can read audio from a file descriptor:
`RecognizerIntent.EXTRA_AUDIO_SOURCE` (ParcelFileDescriptor of 16 kHz mono PCM),
`EXTRA_AUDIO_SOURCE_ENCODING = ENCODING_PCM_16BIT`, `EXTRA_AUDIO_SOURCE_SAMPLING_RATE = 16000`,
`EXTRA_AUDIO_SOURCE_CHANNEL_COUNT = 1`, `EXTRA_SEGMENTED_SESSION = EXTRA_AUDIO_SOURCE` for long audio,
`EXTRA_LANGUAGE = "hi-IN"` (also try `en-IN`), with `SpeechRecognizer.createOnDeviceSpeechRecognizer`.
1. Check support with `checkRecognitionSupport(intent, executor, callback)`; if `hi-IN` is only
   "supported but not installed", call `triggerModelDownload`.
2. In `CallRecordingProcessor`, add `transcribeWithAndroid(pcm)` (pipe the decoded PCM through a
   `ParcelFileDescriptor.createPipe()`), collect `onSegmentResults`. Fall back to Vosk if unsupported.
3. Compare on the owner's recordings `Alo 2026-10-02 19-46-01.m4a` and `Dadaji 2026-10-02 20-25-02.m4a`
   (in `/sdcard/Recordings/Record/Call/`): time taken and whether the AI finds sensible content. Keep
   the better one; record numbers in `HANDOFF.md`.

### S7 — Always-on wake word with near-zero CPU
Two Vosk grammar decoders run on every audio buffer all day. Replace the first stage with a tiny
keyword-spotting model and run Vosk only for ~2 s after a hit:
- Option A (open source): **openWakeWord** (TFLite, Apache-2.0). Train a custom "oyee P A" model
  (their notebook generates synthetic samples; add 20–50 recordings of the owner — see V1 for how to
  record). Run via TFLite on 80 ms frames.
- Option B: **Picovoice Porcupine** custom keyword (very accurate, needs a free personal AccessKey
  from the owner — ask first).
Keep the current Vosk path as the confirmation stage (reduces false wakes). Measure CPU with
`adb shell top -b -n 1 -H -p $(pidof com.paa.assistant)` (thread `oyee-pa`) before/after.

---

## M3 — Voice: only the owner can use "Oyee PA" (speaker verification)

### V1 — Enrol the owner's voice
1. Add Vosk's speaker model (`vosk-model-spk-0.4`, ~13 MB, from alphacephei.com — same source as the
   bundled models) to `assets/model-spk` with a `uuid` file (see how `model-hi` is bundled).
2. 🧠 card → "🔐 Voice lock: Enrol my voice". Screen asks the owner to say "Oyee P A" 5 times and read
   3 short sentences (Hinglish). For each clip, run a Vosk `Recognizer(model, 16000f, spkModel)`
   (vosk-android supports `SpeakerModel` + `setSpkModel`); the result JSON contains an `"spk"` vector
   (128 floats) and `"spk_frames"`. Average the vectors (only clips with `spk_frames` ≥ 100), L2-normalise,
   store in the encrypted DB (new table `voiceprint`, DB migration). Never store the audio.
3. Show "enrolled ✅" and let the owner re-enrol or turn the lock off.

### V2 — Check every wake and every in-call command
1. In `WakeListenerService`, attach the speaker model to the English wake recogniser
   (`setSpkModel`). When `isWake()` is true, read the `spk` vector from the same result; compute
   cosine similarity with the voiceprint.
2. Accept if similarity ≥ threshold (start 0.55; tune), and the utterance has ≥ 60 frames. If too short
   to judge, accept but require the *command* (popup speech or in-call command) to match too: buffer
   the last 3 s of audio and run the spk recognizer on wake + command together.
3. Rejected → log `🔐 voice didn't match (0.31)` and do nothing (no popup, no in-call action).
4. Popup commands (SpeechManager uses Android's recognizer, which gives no audio): when the popup was
   opened by a verified wake, trust it for that session (B6 follow-up window included).
5. Tuning: owner says "Oyee P A" 10× (all must pass) and plays a YouTube voice / has a friend say it
   10× (≥ 9 must be rejected). Put the similarity numbers in `HANDOFF.md`; pick the threshold midway.
- Honest limit: x-vectors from ~1 s of speech are noisy; expect some false rejects in noise. Offer
  "Say Oyee P A again" retry rather than lowering the threshold too much.

---

## M4 — Cloud AI for non-private heavy work

### C1 — One `CloudBrain` interface, two providers
- `core/ai/CloudBrain.kt`: `suspend fun ask(prompt: CloudPrompt, tools: List<Tool> = emptyList()): CloudAnswer`.
- `GeminiBrain` = wraps today's `GeminiClient` (fast, cheap, has Google Search grounding) — default
  for voice questions and tool calls.
- `ClaudeBrain` = Anthropic Messages API over HTTPS (`POST https://api.anthropic.com/v1/messages`,
  headers `x-api-key`, `anthropic-version: 2023-06-01`, `content-type: application/json`), model
  `claude-sonnet-5-5` for heavy reasoning (planning a week, long PDFs, research write-ups), optionally
  `claude-opus-5-5` when the owner says "deep"/"think hard". Key `ANTHROPIC_API_KEY` in `.env`
  (same `getSecret` mechanism), until C3.
- Router rule (in `VoiceOverlayViewModel`/`MainViewModel`): regex parser → Gemini Flash; use Claude when
  the request is long-form (plan/compare/write/analyse a shared document) or the owner asks for "deep".

### C2 — Make the privacy rule impossible to break by accident
- Wrap any text that came from chats/calls in `@JvmInline value class PrivateText(val value: String)`.
  `CloudPrompt` only accepts `String` built from: owner's direct voice/typed command, files the owner
  explicitly shared to PAA, public web pages, public hackathon data, cloud-safe tasks
  (`PrivacyGuard.cloudSafe`). Add a unit test that `CloudPrompt` has no constructor taking `PrivateText`.

### C3 — Keys off the phone (before ever sharing the APK)
- Small proxy (Cloudflare Worker or Firebase Function) holding the Gemini/Claude/Tavily keys; app sends
  a per-install secret generated on first run and registered once by the owner. Owner action needed
  (account). Until then: B19 key restriction.

---

## M5 — Automation

Every automation in this section follows the same safety pattern:
**draft → show exact content to the owner → owner taps once → act → log result**, with an option
"don't ask again for this contact/type" stored per contact, and a daily cap.

### A1 — Send WhatsApp messages for the owner
1. Contacts: request `READ_CONTACTS`; `core/contacts/ContactResolver.kt` fuzzy-matches names
   ("Riya", "riya ece") → phone in E.164 (+91…). Ambiguous → ask in the popup.
2. Three ways to send, tried in order:
   a. **Reply through the notification** (no screen needed): if `NotificationListener` currently holds
      a WhatsApp notification for that chat with a `RemoteInput` action, fill it
      (`RemoteInput.addResultsToIntent`) and `actionIntent.send(context, 0, fillIn)`.
   b. **Accessibility send** (phone unlocked): `Intent(ACTION_VIEW, Uri.parse("https://wa.me/$e164?text=${Uri.encode(text)}"))
      .setPackage("com.whatsapp").addFlags(FLAG_ACTIVITY_NEW_TASK)`; `ChatTaskAccessibilityService`
      waits for the chat to open (`conversation_contact_name` matches), finds the send button
      (verify the id with `uiautomator dump` on the phone — usually `com.whatsapp:id/send`), clicks it,
      then confirms the bubble appeared.
   c. **Locked phone**: post a notification "Ready to send to Riya: '…' [Send]" → tap opens the
      chat with the text prefilled (way b without the auto-click).
3. Voice: new `LocalIntent.SEND_MESSAGE` — "Riya ko bol do main 10 min late hu" / "message Riya that
   I'll be late" → contact + text (text in the owner's words; translate only if asked). Show the
   draft; "send" / tap → send.
4. Scheduled messages: "wish Aman happy birthday at 12 AM" → stored as a task with type `MESSAGE`
   (new column `action TEXT` holding JSON `{type:"whatsapp", to:"+91…", text:"…"}`) → at due time
   ReminderReceiver runs the send flow (b if unlocked, else c). Quiet-hours rule doesn't block
   messages, only sounds.
5. Follow-ups: when a chat task like "Send ppt to Rahul" is overdue, offer "Open chat with draft".
- Limits: max 20 automated messages/day, never to groups unless the owner names the group
  explicitly, never forward chat content to a different person automatically.
- Verify: on the phone, send to the owner's own "Message yourself" chat (a/b/c each once).
- Risk to tell the owner once: WhatsApp's terms disallow automated/bulk messaging; low-volume
  personal use with taps is unlikely to be flagged, but it isn't zero risk.

### A2 — Reminder calls
**A2a (recommended, free, on-device): "PAA calls you".**
1. Per-task toggle "Call me" (and voice: "call me at 5 to remind me about X").
2. Implement a self-managed call: `PhoneAccount` with `CAPABILITY_SELF_MANAGED`, permission
   `MANAGE_OWN_CALLS`, a `ConnectionService`, foreground service type `phoneCall`;
   at due time `TelecomManager.addNewIncomingCall()` → full-screen incoming-call UI "PAA — Send ppt to
   Rahul". Respect 07:00–19:00 (outside → normal silent notification).
3. On answer: this is the one place TTS is allowed (owner opted in per task): speak the task, then
   listen (SpeechRecognizer) for "done / ho gaya", "snooze 30 minutes", "call me again at 6",
   "cancel" → act and say a short confirmation. No answer → retry once after 10 min, then a normal alarm.
- Verify on the emulator (self-managed calls work there) and once on the phone.

**A2b (optional, paid, needs a server): AI calls other people.** Only if the owner confirms the cost.
Outbound calls via an Indian-capable telephony API (Twilio needs KYC for +91 numbers; Exotel/Plivo are
alternatives) + a server that streams audio to speech-to-text + a cloud LLM + text-to-speech. Must
start with "This is an automated assistant calling for Shreyash", must not record without consent,
and each call is approved by the owner in the app with the exact script. Architecture: app →
`POST /call {to, purpose, script}` on the C3 proxy → telephony API → transcript + outcome posted
back → PAA shows the result as a notification and updates the task.

### A3 — Hackathon registration ("assisted one-tap", not unattended bots)
Unstop/Devfolio/Devpost have no public registration API; logging in and submitting forms with a bot
breaks their terms, and OTP/CAPTCHA steps need a human anyway. So PAA does everything except the final tap.
1. **Profile vault** (encrypted DB, new screen): name, email, phone, college, degree, branch, grad year
   2028, gender (optional), city, GitHub, LinkedIn, portfolio, resume URL, default team members.
2. **Details**: when a hackathon task exists (already created from links by `HackathonLinkResolver`),
   fetch public details — Unstop already via `api/public/competition/<id>`; add eligibility, team size,
   fee, mode, stages. Public data → cloud allowed (C2), e.g. Claude summary "is it worth it / am I eligible".
3. **Reminders**: 3 days, 24 h and 2 h before the registration deadline (respect quiet hours).
4. **"Register now" button** (in the reminder and the task): opens an in-app `WebView` at the
   registration URL (owner logs in once; cookies persist via `CookieManager`). After each page load,
   inject an autofill script: per-site selector maps for Unstop/Devfolio/Devpost
   (`assets/autofill/<host>.json`: field selector → profile key) plus a generic fallback that matches
   `<label>`/placeholder text ("full name", "email", "college", "graduation year"…) to profile keys
   and dispatches `input`/`change` events. Highlight filled fields; the owner checks, does OTP/CAPTCHA,
   and taps submit. Never auto-click a submit button.
5. **Done detection**: success URL/text patterns per site ("You have successfully registered",
   "/registered") → mark the task done and save the registration link.
6. Optional desktop helper `tools/register.py <url>`: Playwright with a persistent Chrome profile, same
   autofill maps, headed browser, the owner clicks submit.
- Verify: on the phone with a real open Unstop hackathon: reminder → Register now → fields filled →
  owner submits → task marked done.

### A4 — More automations (ranked by value ÷ effort; do after A1–A3)
1. **Google Classroom deadlines** (official Classroom API, OAuth with the owner's college Google
   account) → tasks with exact due dates. Data comes from Google, processed locally.
2. **Smart follow-ups**: "You told Rahul you'd send the ppt (yesterday). Open chat with draft?" — from
   overdue chat tasks, once per task per day, 07:00–19:00.
3. **Auto-reply while busy** (opt-in per contact): during a call / in class (calendar) / driving,
   reply via notification RemoteInput: "In a call, will call back" — uses A1 way (a) only.
4. **Bank/UPI SMS** (`READ_SMS`, sideloaded app so Play policy doesn't apply): bill-due and EMI
   reminders, spending summary — parsed locally with regex + Gemma.
5. **Daily plan at 7 AM** generated locally (Gemma) from all tasks incl. private ones, shown as a
   notification (text only). Replace the Gemini morning briefing (B18).
6. **Busy-group digest**: once a day, Gemma summarises only lines that mention the owner, deadlines,
   links or money from groups the owner marks as "digest".
7. **Class timetable**: auto-silent during lectures from calendar events; "remind me after class".
8. **Attendance tracker** per subject with a reminder when it drops below 75 %.

---

## M6 — Small features and UX gaps
- **Settings screen** for everything in `UserProfile` (names, batch, hackathon groups, alarm window,
  burst wait, group-request default) stored in DataStore; `UserProfile` reads from it.
- **Task details**: tap a task → source message, AI reason, "deadline understood from: …", edit time,
  "not a task" (feedback), history of updates.
- **Completed history + search**.
- **Encrypted export/import** of tasks and settings (`allowBackup=false` means a reinstall loses data).
- **Model management**: show the model name/size, delete unused models (B21), re-import.
- **APK size**: optional — download `model-hi` on first use instead of bundling (saves ~45 MB).

---

## Order of work (milestones)

| # | Milestone | Tasks | Needs the owner? |
|---|---|---|---|
| 1 | Safety net | M0.1, M0.2 | no |
| 2 | Correctness | B1–B11, B13, B14, B16, B17, B21, B22 | B11/B12/B22 phone checks |
| 3 | Speed | S0, S1, S2, S5, S4, S3, S6 | phone benchmarks |
| 4 | Voice | B4, B5, B6, B7, B12, V1, V2, S7 | enrolment + tuning on phone |
| 5 | Release hygiene | B18, B19, B20, C3 | Google Cloud console, proxy account |
| 6 | Cloud brain | C1, C2 | Anthropic API key |
| 7 | WhatsApp sending | A1 | phone test to own number |
| 8 | PAA calls you | A2a | — |
| 9 | Hackathons | A3 | real hackathon to try |
| 10 | Extras | A4, M6, A2b | A2b: cost approval |

After each milestone: run the eval harness, update `HANDOFF.md`, commit, and give the owner a short
summary with what to test on the phone.

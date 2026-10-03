package com.paa.assistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.paa.assistant.core.audio.VoskEngine
import com.paa.assistant.ui.overlay.VoiceOverlayActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Recognizer
import java.util.Locale
import javax.inject.Inject

/**
 * "Oyee PA" listener — an optional, always-on, fully offline listener (bundled Vosk model).
 *
 *  - Keeps a rolling transcript of the last [WINDOW_MS] in memory only; nothing is saved or sent.
 *  - "Oyee PA" outside a call → opens the voice popup.
 *  - "Oyee PA" during a phone/WhatsApp call → the on-device model reads the last ~2 minutes and
 *    schedules what was agreed; a short "Done, you'll be reminded" plays in the earpiece only.
 *
 * During a call Android gives the microphone only to the call — except to apps with an enabled
 * accessibility service, which PAA has (ChatTaskAccessibilityService). Only the user's side is
 * heard, plus the other person's voice when on speakerphone; the call audio itself can't be read.
 */
@AndroidEntryPoint
class WakeListenerService : Service() {

    @Inject lateinit var processor: ChatMessageProcessor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audio: AudioRecord? = null
    @Volatile private var audioThread: Thread? = null
    private var lastWake = 0L
    private var starting = false
    private var lastNearMiss = 0L
    private var silentChunks = 0
    private var resumeJob: Job? = null
    private var tts: TextToSpeech? = null

    /** (time, sentence) heard during the call; in memory only, cleared when the call ends. */
    private val transcript = ArrayDeque<Pair<Long, String>>()

    // Call pre-analysis: tasks are worked out in the background while people talk, so "Oyee PA"
    // can schedule instantly instead of waiting ~1 min for the model.
    private val prepared = mutableListOf<Pair<Long, ChatMessageProcessor.Prepared>>()
    private var analyzedUpTo = 0L
    @Volatile private var analyzing = false
    private var analyzerJob: Job? = null
    /** "Oyee PA, start listening" → everything from this time on is kept until "Oyee PA, bas". */
    private var listenFrom: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
                // Voice-communication audio goes to the earpiece/headset during a call — the other side can't hear it
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The voice popup owns the microphone while it's open; two recorders at once leave one hearing silence
        if (intent?.action == ACTION_PAUSE) {
            popupOpen = true
            resumeJob?.cancel()
            stopListening()
            // Safety net if the popup never says it closed
            resumeJob = scope.launch { delay(MAX_POPUP_MS); popupOpen = false; if (isEnabled(this@WakeListenerService)) startListening() }
            return START_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            popupOpen = false
            restartLater(800)
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        // onStartCommand can arrive twice quickly; two recorders at once get one of them muted by Android
        if (audioThread == null && !starting) { starting = true; scope.launch { try { startListening() } finally { starting = false } } }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "\"Oyee PA\" listener", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while PAA listens offline for \"Oyee PA\""
            }
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, WakeListenerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for \"Oyee PA\"")
            .setContentText("Offline — nothing is recorded or sent")
            .setOngoing(true)
            .addAction(0, "Turn off", stop)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun startListening() {
        DetectionLog.init(this)
        DetectionLog.add("system", "Oyee PA", "⏳ loading offline speech model…")
        val model = VoskEngine(this).loadModel() ?: run {
            Log.e(TAG, "Speech model unavailable")
            DetectionLog.add("system", "Oyee PA", "❌ offline speech model failed to load")
            stopSelf(); return
        }
        val record = try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 2))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: SecurityException) { null }
        if (record == null) {
            DetectionLog.add("system", "Oyee PA", "❌ microphone unavailable — allow Microphone for PAA, retrying")
            restartLater(10_000); return
        }
        // Two decoders on one stream: a tiny wake-phrase grammar (accurate for "oyee pa", which the
        // general model mishears as "oh gee paw"/"i thought"), and free text only while in a call.
        val wake = Recognizer(model, VoskEngine.SAMPLE_RATE, WAKE_GRAMMAR).apply { setWords(true) }
        val hindi = VoskEngine(this).loadHindiModel()
        val free = Recognizer(hindi ?: model, VoskEngine.SAMPLE_RATE)
        // "ओये पा" is a Hindi word the English model can't hold on to; this decoder knows it
        val wakeHi = hindi?.let { Recognizer(it, VoskEngine.SAMPLE_RATE, WAKE_GRAMMAR_HI).apply { setWords(true) } }
        audio = record
        record.startRecording()
        DetectionLog.add("system", "Oyee PA", "✅ listening")
        audioThread = Thread {
            val buf = ShortArray(RATE / 10)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) { if (n < 0) break else continue }
                    if (n > 0 && buf.take(n).all { it == 0.toShort() }) {
                        if (++silentChunks == 300) DetectionLog.add("system", "Oyee PA", "🔇 microphone gives only silence — another app may be using it")
                    } else silentChunks = 0
                    if (wake.acceptWaveForm(buf, n)) {
                        val json = wake.result
                        val w = words(json)
                        if (isWake(w)) scope.launch { onWake() }
                        else if (w.any { it.first != "[unk]" }) { nearMiss(w); Log.i(TAG, "near miss: " + json.replace(Regex("\\s+"), " ")) }
                    }
                    if (wakeHi != null && wakeHi.acceptWaveForm(buf, n)) {
                        val json = wakeHi.result
                        val w = words(json)
                        if (isWakeHindi(w)) scope.launch { onWake() }
                        else if (w.any { it.first != "[unk]" }) { nearMiss(w); Log.i(TAG, "near miss hi: " + json.replace(Regex("\\s+"), " ")) }
                    }
                    if (inCall()) {
                        if (free.acceptWaveForm(buf, n)) text(free.result).let { scope.launch { remember(it) } }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Listener error: ${e.javaClass.simpleName}")
                scope.launch { restartLater(5_000) }
            } finally {
                wake.close(); free.close(); wakeHi?.close()
            }
        }.apply { name = "oyee-pa"; start() }
    }

    private fun stopListening() {
        audioThread?.interrupt()
        audioThread?.join(1_000)
        audioThread = null
        audio?.let { runCatching { it.stop() }; it.release() }
        audio = null
    }

    private fun restartLater(ms: Long) {
        stopListening()
        resumeJob?.cancel()
        resumeJob = scope.launch { delay(ms); if (isEnabled(this@WakeListenerService) && !popupOpen) startListening() }
    }

    private fun words(json: String?): List<Pair<String, Double>> = runCatching {
        val arr = JSONObject(json ?: "").optJSONArray("result") ?: return@runCatching emptyList()
        (0 until arr.length()).map { arr.getJSONObject(it).let { w -> w.getString("word") to w.getDouble("conf") } }
    }.getOrDefault(emptyList())

    private fun text(json: String?): String =
        json?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }?.trim().orEmpty()

    /** Only wake-grammar words can appear here, never the user's speech — safe to log for tuning. */
    private fun nearMiss(w: List<Pair<String, Double>>) {
        val now = System.currentTimeMillis()
        if (now - lastNearMiss < 3_000) return
        lastNearMiss = now
        DetectionLog.add("system", "Oyee PA", "👂 heard \"" + w.joinToString(" ") { it.first.replace("[unk]", "?") } + "\" — not accepted as Oyee PA")
    }

    private fun remember(sentence: String) {
        if (sentence.isBlank()) return
        val now = System.currentTimeMillis()
        transcript.addLast(now to sentence)
        val keepFrom = listenFrom ?: (now - WINDOW_MS)
        while (transcript.isNotEmpty() && transcript.first().first < keepFrom - 60_000L) transcript.removeFirst()
        if (analyzerJob?.isActive != true) analyzerJob = scope.launch { analyzeDuringCall() }
    }

    /** Runs while the call lasts: analyses each new stretch of talk (dry run) and keeps the tasks found. */
    private suspend fun analyzeDuringCall() {
        while (inCall()) {
            delay(5_000)
            val fresh = transcript.filter { it.first > analyzedUpTo }
            val chars = fresh.sumOf { it.second.length }
            if (analyzing || fresh.isEmpty() || (chars < 150 && System.currentTimeMillis() - fresh.first().first < 20_000)) continue
            analyzeChunk(fresh)
        }
        // Call over: forget everything heard
        transcript.clear(); prepared.clear(); analyzedUpTo = 0L; listenFrom = null
    }

    private suspend fun analyzeChunk(chunk: List<Pair<Long, String>>) {
        if (chunk.isEmpty()) return
        analyzing = true
        try {
            val context = transcript.filter { it.first < chunk.first().first }.takeLast(4).map { it.second }
            val found = mutableListOf<ChatMessageProcessor.Prepared>()
            processor.process(
                ChatMessage(text = chunk.joinToString(". ") { it.second }, app = ChatApp.CALL, outgoing = false,
                    chatName = "your call", sender = null, isGroup = false, recentMessages = context),
                dryRun = found
            )
            prepared += found.map { chunk.last().first to it }
            analyzedUpTo = maxOf(analyzedUpTo, chunk.last().first)
        } finally {
            analyzing = false
        }
    }

    private fun onWake() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 5_000) return
        lastWake = now
        DetectionLog.add("system", "Oyee PA", "🗣️ wake word heard" + if (inCall()) " (in a call)" else "")
        if (inCall()) handleInCall() else openVoicePopup()
    }

    private fun inCall(): Boolean {
        val mode = getSystemService(AudioManager::class.java).mode
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    /** In a call: act on what was said with "Oyee PA"; replies go to the earpiece only. */
    private fun handleInCall() {
        val wakeAt = System.currentTimeMillis()
        scope.launch {
            delay(2_500)  // let the free-text decoder finish the sentence that held the command
            val command = transcript.filter { it.first >= wakeAt - 4_000 }.joinToString(" ") { it.second }.lowercase(Locale.ROOT)
            when (commandOf(command)) {
                Command.START -> {
                    listenFrom = wakeAt
                    DetectionLog.add("system", "Oyee PA", "👂 listening to the call until you say \"Oyee PA, bas\"")
                    say("Okay, I'm listening. Say Oyee PA, bas, when done.")
                }
                Command.STOP -> {
                    val from = listenFrom ?: (wakeAt - WINDOW_MS)
                    listenFrom = null
                    scheduleSince(from, wakeAt - 4_000)
                }
                Command.QUESTION -> say(nextTasksSummary())
                Command.SCHEDULE -> scheduleSince(listenFrom ?: (wakeAt - WINDOW_MS), wakeAt - 4_000)
            }
        }
    }

    /** Schedules tasks talked about since [from]: instantly from the pre-analysis, then the rest. */
    private suspend fun scheduleSince(from: Long, until: Long) {
        val ready = prepared.filter { it.first in from..until }.map { it.second }
        prepared.removeAll { it.first in from..until }
        var count = processor.commit(ready)
        if (count > 0) say("Done, $count ${if (count == 1) "task" else "tasks"} scheduled.")
        // Talk not analysed yet (said in the last moments, or while the model was busy)
        while (analyzing) delay(500)
        val tail = transcript.filter { it.first > maxOf(analyzedUpTo, from) && it.first <= until }
        if (tail.isNotEmpty()) {
            if (count == 0) say("One moment.")
            analyzeChunk(tail)
            val more = prepared.filter { it.first in from..until }.map { it.second }
            prepared.removeAll { it.first in from..until }
            val extra = processor.commit(more)
            if (extra > 0) say(if (count > 0) "And $extra more." else "Done, $extra ${if (extra == 1) "task" else "tasks"} scheduled.")
            count += extra
        }
        if (count == 0) say(if (transcript.isEmpty()) "Sorry, I didn't catch anything." else "I didn't find a task in that.")
    }

    private enum class Command { START, STOP, QUESTION, SCHEDULE }

    /** Command words as the Hindi or English decoder writes them. */
    private fun commandOf(s: String): Command = when {
        Regex("बस|इनफ|इनफ़|स्टॉप|हो गया|रुक|बंद|काफी|enough|stop|\\bbas\\b|that's it").containsMatchIn(s) -> Command.STOP
        Regex("स्टार्ट|सुनो|सुनते|सुनना|लिसन|आगे|रिकॉर्ड|start|listen|aage|record").containsMatchIn(s) -> Command.START
        Regex("क्या है|कब है|कौन सा|कितने|अगला|नेक्स्ट|मेरे टास्क|what|when|which|next task|my tasks|how many").containsMatchIn(s) -> Command.QUESTION
        else -> Command.SCHEDULE
    }

    /** Spoken answer about the owner's own tasks (earpiece only). */
    private suspend fun nextTasksSummary(): String {
        val tasks = com.paa.assistant.data.db.AppDatabase.getInstance(this).taskDao().getUpcomingTasks(3)
        if (tasks.isEmpty()) return "You have no pending tasks."
        val fmt = java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH)
        return "Your next tasks: " + tasks.joinToString("; ") { t ->
            t.title + (t.dueTimestamp?.let { " at " + fmt.format(java.util.Date(it)) } ?: "")
        } + "."
    }

    /** Outside a call: open the voice popup (needs "Display over other apps", else a tap-to-open alert). */
    private fun openVoicePopup() {
        transcript.clear()
        stopListening()  // free the microphone for the popup
        val intent = Intent(this, VoiceOverlayActivity::class.java)
            .putExtra("auto_listen", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(this)) {
            startActivity(intent)
        } else {
            val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_WAKE, "Oyee PA wake-up", NotificationManager.IMPORTANCE_HIGH))
            nm.notify(
                NOTIFICATION_ID + 1,
                NotificationCompat.Builder(this, CHANNEL_WAKE)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Yes? Tap to speak")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        }
        // The popup resumes us when it closes (ACTION_RESUME); until then it has the microphone
        popupOpen = true
        resumeJob?.cancel()
        resumeJob = scope.launch { delay(MAX_POPUP_MS); popupOpen = false; if (isEnabled(this@WakeListenerService)) startListening() }
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "paa_wake_reply")
    }

    companion object {
        private const val TAG = "PAA_Wake"
        private const val CHANNEL = "paa_wake_listener"
        private const val CHANNEL_WAKE = "paa_wake_popup"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_STOP = "com.paa.assistant.ACTION_STOP_WAKE"
        private const val WINDOW_MS = 2 * 60_000L
        private const val MAX_POPUP_MS = 5 * 60_000L
        private const val ACTION_PAUSE = "com.paa.assistant.ACTION_PAUSE_WAKE"
        private const val ACTION_RESUME = "com.paa.assistant.ACTION_RESUME_WAKE"
        @Volatile private var popupOpen = false

        /** Voice popup opened: hand it the microphone. */
        fun pauseForPopup(context: Context) = send(context, ACTION_PAUSE)

        /** Voice popup closed: listen for "Oyee PA" again. */
        fun resumeAfterPopup(context: Context) = send(context, ACTION_RESUME)

        private fun send(context: Context, action: String) {
            if (!isEnabled(context)) return
            runCatching { context.startService(Intent(context, WakeListenerService::class.java).setAction(action)) }
                .onFailure { Log.w(TAG, "Couldn't reach the listener: ${it.javaClass.simpleName}") }
        }
        private const val PREFS = "paa_settings"
        private const val KEY_ENABLED = "wake_listener_enabled"

        private const val RATE = 16000

        /** Hindi wake phrases, plus common look-alikes as decoys so they aren't forced into a match. */
        private const val WAKE_GRAMMAR_HI = "[\"ओये पीए\", \"ओए पीए\", \"ओय पीए\", \"ओ पीए\", \"ओये पी ए\", \"ओए पी ए\", \"ओ पी ए\", \"ओये पा\", \"पीए\", \"पापा\", \"पानी\", \"ओके\", \"ओपन\", \"[unk]\"]"

        /** "oyee P A" spelled as letters: "oye/oy/oi/o/oh" then "p" "a". */
        private fun isWakeLetters(words: List<Pair<String, Double>>): Boolean = (2 until words.size).any { j ->
            words[j].first == "a" && words[j - 1].first == "p" && words[j - 1].second >= 0.4 &&
                words[j - 2].first in setOf("oye", "oy", "oi", "o", "oh")
        }

        /** "ओये पीए" / "ओये पी ए" (the owner says the letters P-A), or "ओये पा". */
        fun isWakeHindi(words: List<Pair<String, Double>>): Boolean = (1 until words.size).any { j ->
            val prefix = setOf("ओये", "ओए", "ओय", "ओ")
            when (words[j].first) {
                "पीए" -> words[j].second >= 0.5 && words[j - 1].first in prefix
                "ए" -> j >= 2 && words[j - 1].first == "पी" && words[j - 2].first in prefix
                "पा" -> words[j].second >= 0.6 && words[j - 1].first in setOf("ओये", "ओए", "ओय")
                else -> false
            }
        } || words.indices.any { j ->
            if (j == 0 || words[j].first != "पा" || words[j].second < 0.6) return@any false
            when (words[j - 1].first) {
                "ओये", "ओए", "ओय" -> true
                "ओ", "अरे" -> words.size == 2
                else -> false
            }
        }

        /** Phrases the wake decoder may output; everything else becomes [unk]. */
        private const val WAKE_GRAMMAR = "[\"oye p a\", \"oy p a\", \"oi p a\", \"o p a\", \"oh p a\", \"oye pa\", \"oy pa\", \"oi pa\", \"hey pa\", \"o pa\", \"oh pa\", \"p a\", \"[unk]\"]"

        /**
         * True if the wake decoder's words (word, confidence) contain "oyee pa" as the bundled model hears it:
         * "oye/oy/oi pa", or "o/oh hey pa". Plain "hey pa" (e.g. "hey Paul") and "oh pa" ("oh papa", "open app")
         * are decoys in the grammar so they don't get forced into a wake match.
         */
        fun isWake(words: List<Pair<String, Double>>): Boolean = isWakeLetters(words) || words.indices.any { j ->
            if (j == 0 || words[j].first != "pa" || words[j].second < 0.6) return@any false
            val (prev, prevConf) = words[j - 1]
            when (prev) {
                // the owner's real "oyee" decodes as "oy" at 20–50%
                "oye", "oy", "oi" -> true
                // "oh pa" / "o pa" said on its own ("open the app" adds a sound in front)
                "oh", "o" -> words.size == 2
                "hey" -> j >= 2 && words[j - 2].first in setOf("o", "oh") && prevConf >= 0.55
                // unknown sound right before a final "pa" said on its own
                "[unk]" -> j == words.lastIndex && words.size <= 4 && words[j].second >= 0.95
                else -> false
            }
        }

        fun isEnabled(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        /** Starts the listener if the user turned it on. Call from a foreground context (app UI). */
        fun startIfEnabled(context: Context) {
            if (!isEnabled(context)) return
            try {
                context.startForegroundService(Intent(context, WakeListenerService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start listener: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeListenerService::class.java))
        }
    }
}

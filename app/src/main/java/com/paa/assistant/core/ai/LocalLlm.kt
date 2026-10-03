package com.paa.assistant.core.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.paa.assistant.core.profile.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A task found by the on-device model. [whenPhrase] is copied verbatim from the message and resolved by the parser. */
data class LlmTask(
    val title: String,
    val whenPhrase: String?,
    val priority: Int,
    val isRegistration: Boolean,
    /** 0..1 — how sure the model is that this is a real task for the user. */
    val confidence: Float = 0.6f,
    /** Plain-language explanation, shown to the user. */
    val reason: String? = null
)

/**
 * LocalLlm — on-device Gemma 3 1B (MediaPipe LLM Inference) for private chat messages.
 *
 * Everything here runs on the phone; no text ever leaves the device. Used for WhatsApp /
 * Telegram content, which must never be sent to Gemini or Tavily.
 *
 * The model file is NOT bundled in the APK. Install it from the app ("Install local AI") or with:
 *   adb push <model>.task /sdcard/Android/data/com.paa.assistant/files/models/local_model.task
 *
 * The model loads lazily and is released after [IDLE_RELEASE_MS] of inactivity (~600 MB RAM).
 */
@Singleton
class LocalLlm @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "LocalLlm"
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: LlmInference? = null
    private var releaseJob: Job? = null

    companion object {
        const val MODEL_FILE_NAME = "local_model.task"
        /** Name used before 2026-10-03 (it held Gemma 3n all along); renamed on first use. */
        private const val OLD_MODEL_FILE_NAME = "gemma3-1b.task"
        private const val IDLE_RELEASE_MS = 5 * 60_000L  // loading is the slow part; keep warm between messages
        private const val INFERENCE_TIMEOUT_MS = 120_000L  // includes a cold model load
        private const val GENERATE_TIMEOUT_MS = 90_000L
        /** The engine's window (input + output) is 2048 tokens; keep room for the JSON answer. */
        const val PROMPT_BUDGET = 2048 - 350

        /** Entries seen in the last .tar.gz (for diagnostics). */
        val lastTarEntries = mutableListOf<String>()

        /**
         * Streams a .tar.gz and writes the first regular-file entry ending in ".task" to [out].
         * Uses Apache Commons Compress — a hand-rolled tar reader misaligned the data by 4 bytes on
         * Android (correct length, but shifted), which corrupted the model's zip structure.
         */
        fun extractTaskFromTarGz(input: java.io.InputStream, out: File): Boolean {
            lastTarEntries.clear()
            val gz = org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(input.buffered(1 shl 20), true)
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: return false
                    lastTarEntries += "${entry.name}(${entry.size})"
                    if (entry.isFile && entry.name.endsWith(".task") && entry.size > 100L * 1024 * 1024) {
                        out.outputStream().use { o -> tar.copyTo(o, 1 shl 20) }
                        return out.length() == entry.size
                    }
                }
            }
        }

        private fun readFully(s: java.io.InputStream, b: ByteArray): Int {
            var off = 0
            while (off < b.size) { val n = s.read(b, off, b.size - off); if (n < 0) break; off += n }
            return off
        }

            /** Parses the model's JSON reply (tolerates text around it). */
            /**
             * Small models slip on JSON: a stray "}" after a value (`{"title": "Call me"}, "when": …`),
             * or output cut off before the closing brackets. Repairs those before giving up.
             */
            internal fun parseLenient(json: String): JSONObject {
                runCatching { return JSONObject(json) }
                var fixed = json.replace(Regex("""("|\d|true|false|null)\s*}\s*,\s*("(?:when|priority|registration|confidence|reason)")"""), "$1, $2")
                runCatching { return JSONObject(fixed) }
                val opens = fixed.count { it == '[' } - fixed.count { it == ']' }
                val braces = fixed.count { it == '{' } - fixed.count { it == '}' }
                fixed = fixed.trimEnd().trimEnd(',') + "}".repeat(maxOf(0, braces - 1)) + "]".repeat(maxOf(0, opens)) + "}"
                return runCatching { JSONObject(fixed) }.getOrElse { JSONObject(json) }
            }

            fun parseVerdict(raw: String): ChatVerdict {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) throw IllegalStateException("No JSON in model output")
            val root = parseLenient(raw.substring(start, end + 1))
            val arr = root.optJSONArray("tasks")
            val tasks = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").trim().takeIf { it.length >= 2 } ?: return@mapNotNull null
                LlmTask(
                    title = title.take(60),
                    whenPhrase = if (o.isNull("when")) null else o.optString("when").takeIf { it.isNotBlank() && it != "null" },
                    priority = o.optInt("priority", 1).coerceIn(0, 3),
                    isRegistration = o.optBoolean("registration", false),
                    confidence = o.optDouble("confidence", 0.6).toFloat().coerceIn(0f, 1f),
                    reason = o.optString("reason").trim().takeIf { it.isNotBlank() && it != "null" }
                )
            }
            val doneArr = root.optJSONArray("done")
            val done = if (doneArr == null) emptyList() else
                (0 until doneArr.length()).mapNotNull { k -> doneArr.optInt(k, 0).takeIf { it > 0 }?.minus(1) }
            val updArr = root.optJSONArray("update")
            val updates = if (updArr == null) emptyList() else (0 until updArr.length()).mapNotNull { k ->
                val o = updArr.optJSONObject(k) ?: return@mapNotNull null
                val n = o.optInt("n", 0).takeIf { it > 0 } ?: return@mapNotNull null
                fun str(key: String) = if (o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
                TaskUpdate(n - 1, str("when"), str("title")).takeIf { it.whenPhrase != null || it.title != null }
            }
            return ChatVerdict(tasks, root.optString("reason").trim().takeIf { it.isNotBlank() && it != "null" }, done.distinct(), updates)
        }

        /**
         * Prompt within [PROMPT_BUDGET] tokens: drops the owner's ✓/✗ examples first, then older chat lines,
         * then open tasks beyond 4, and finally cuts the start of a long message (calls).
         */
        internal fun fitPrompt(message: String, ctx: ChatContext, count: (String) -> Int): String {
            var c = ctx
            var m = message
            val steps = listOf<() -> Boolean>(
                { (c.learnedExamples.isNotEmpty()).also { c = c.copy(learnedExamples = emptyList()) } },
                { (c.recentMessages.size > 4).also { c = c.copy(recentMessages = c.recentMessages.takeLast(4)) } },
                { (c.openTasks.size > 4).also { c = c.copy(openTasks = c.openTasks.take(4)) } },
                { (c.recentMessages.isNotEmpty()).also { c = c.copy(recentMessages = emptyList()) } },
            )
            var prompt = buildChatPrompt(m, c)
            for (step in steps) {
                if (count(prompt) <= PROMPT_BUDGET) return prompt
                if (step()) prompt = buildChatPrompt(m, c)
            }
            while (count(prompt) > PROMPT_BUDGET && m.length > 200) {
                m = m.takeLast(m.length * 3 / 4)
                prompt = buildChatPrompt(m, c)
            }
            return prompt
        }

        internal fun buildChatPrompt(message: String, ctx: ChatContext): String {
            val now = SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm", Locale.ENGLISH).format(Date())
            val names = UserProfile.names.joinToString(" or ") { it.replaceFirstChar(Char::uppercase) }
            val who = when {
                ctx.isCall -> "This is an automatic speech-to-text transcript of the last minute or two of the owner's phone call " +
                    "(both speakers mixed together, may contain recognition errors). The owner just said \"Oyee PA\" to ask you " +
                    "to schedule whatever they agreed to do or were asked to do on this call. Ignore the words \"oyee PA\" themselves."
                ctx.burst -> "These are the newest messages of the chat \"${ctx.chatName ?: "?"}\"" + (if (ctx.isGroup) " (a group)" else "") +
                    ", oldest first, one per line as 'Speaker: text' ('You' is the owner). A task and its deadline or details are often " +
                    "spread over several lines — read them together as one conversation."
                ctx.outgoing -> "The owner SENT this message" + (ctx.chatName?.let { " to $it" } ?: "") + "."
                ctx.isGroup -> "The owner RECEIVED this in the group \"${ctx.chatName ?: "?"}\"" + (ctx.sender?.let { " from $it" } ?: "") + "."
                else -> "The owner RECEIVED this" + ((ctx.sender ?: ctx.chatName)?.let { " from $it" } ?: "") + "."
            }
            fun clean(s: String) = s.take(200).replace("\"", "'").replace("\n", " ")
            val history = if (ctx.recentMessages.isEmpty()) "" else
                "Earlier lines (oldest first):\n" +
                    ctx.recentMessages.takeLast(8).joinToString("\n") { "  ${clean(it)}" } + "\n"
            val learned = if (ctx.learnedExamples.isEmpty()) "" else
                "The owner's own past judgements (follow them):\n" +
                    ctx.learnedExamples.joinToString("\n") { (m, title) ->
                        "  \"${clean(m)}\" → " + (title?.let { "IS a task: $it" } ?: "NOT a task")
                    } + "\n"
            val open = if (ctx.openTasks.isEmpty()) "" else
                "Open tasks (done = clearly finished now, e.g. \"ho gaya\", \"bhej diya\", \"hn bhai\" answering it; update = new deadline/detail):\n" +
                    ctx.openTasks.mapIndexed { n, t -> "  ${n + 1}. ${clean(t)}" }.joinToString("\n") + "\n"
            return chatPromptPrefix() + """
Now: $now
$who
$history$open$learned
${if (ctx.burst) "Messages" else "Message"}: "${message.take(if (ctx.isCall || ctx.burst) 1400 else 800).replace("\"", "'").let { if (ctx.burst) it else it.replace("\n", " ") }}"
JSON:"""
        }

        /**
         * The fixed part of the chat prompt: identical on every call, so it comes FIRST — the engine can
         * keep it pre-processed and only read the per-message part (see [LocalLlm.prefixSession]).
         * Kept short: prompt length is what makes this phone slow.
         */
        internal fun chatPromptPrefix(): String {
            val names = UserProfile.names.joinToString(" or ") { it.replaceFirstChar(Char::uppercase) }
            return """
You are the personal assistant of $names (graduates ${UserProfile.GRADUATION_YEAR}). Read chat messages (English, Hindi, Hinglish)
like a person would and list the real to-dos THE OWNER must do. Reply with ONLY JSON.

Rules:
- A task = a promise the owner makes ("kal bhej dunga") or a request made to the owner. In groups only if it names the owner.
- Not a task: suggestions ("call pe baat karte hai"), jokes, ads, announcements for everyone, someone else's plan, things already done.
- "haan kar dunga"/"ok will do" accepts the request above it; with nothing above it is NOT a task.
- Internship/job posts: only if open to the ${UserProfile.GRADUATION_YEAR} batch or no batch named.
- Use earlier lines only to fill in details (deadline, what "it" is, who); never create a task found only there.
- title: 2-7 English words starting with a verb; name the person involved using ONLY names from the messages.
- when: ALL time words for that task joined, even from different lines ("kal tak" + "5 baje se pehle" = "kal 5 baje se pehle"); else null. 140/315/1145 are clock times.
- One task per piece of work. confidence 0-1. priority 0-3. registration true only for hackathon/event sign-ups.
- done: numbers of open tasks the messages show are already finished. update: open tasks whose deadline/details changed (never re-create them).
- Example words below (Rahul, ppt) are only about the format — never copy them.

Format:
{"tasks": [{"title": "...", "when": "..." or null, "priority": 1, "registration": false, "confidence": 0.9, "reason": "one short sentence"}], "reason": "why no task, if none", "done": [], "update": [{"n": 1, "when": "...", "title": null}]}

Examples:
Message (sent to Rahul): "ok bro I'll call you by 140 and send the ppt at 315"
{"tasks": [{"title": "Call Rahul", "when": "by 140", "priority": 1, "registration": false, "confidence": 0.95, "reason": "You promised Rahul a call by 1:40."}, {"title": "Send the ppt to Rahul", "when": "at 315", "priority": 1, "registration": false, "confidence": 0.95, "reason": "You promised Rahul the ppt at 3:15."}], "reason": "", "done": [], "update": []}
Message (received): "Register now for the biggest hackathon of the year! Get 20% off"
{"tasks": [], "reason": "An advertisement.", "done": [], "update": []}
Message (sent): "I already submitted it lol"
{"tasks": [], "reason": "Already done.", "done": [], "update": []}

"""
        }
    }

    val modelFile: File
        get() {
            val dir = context.getExternalFilesDir("models")
            val file = File(dir, MODEL_FILE_NAME)
            File(dir, OLD_MODEL_FILE_NAME).takeIf { !file.exists() && it.exists() }?.renameTo(file)
            return file
        }

    fun isAvailable(): Boolean = modelFile.exists()

    /** Last failure, for the in-app diagnostics. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Loads the model and runs a tiny prompt. Returns a human-readable result for the "Test local AI" button.
     * Note: if the phone runs out of memory loading the model, Android kills the app — the persisted
     * "testing…" log line without a result then shows that.
     */
    suspend fun selfTest(): String = withContext(Dispatchers.Default) {
        if (!isAvailable()) return@withContext "❌ Model file not found. Install it from the 🧠 card."
        val sizeGb = "%.2f".format(modelFile.length() / 1e9)
        val start = System.currentTimeMillis()
        var loadedAt = start
        try {
            val reply = kotlinx.coroutines.withTimeout(180_000L) {
                mutex.withLock {
                    val llm = engine ?: createEngine().also { engine = it }
                    loadedAt = System.currentTimeMillis()
                    scheduleRelease()
                    ask(llm, "Reply with exactly one word: OK")
                }
            }
            val end = System.currentTimeMillis()
            "✅ Local AI works on $backendInUse (${sizeGb} GB model, load ${(loadedAt - start) / 1000}s, reply ${(end - loadedAt) / 1000}s). Reply: \"${reply.trim().take(40)}\""
        } catch (e: Throwable) {
            lastError = "${e.javaClass.simpleName}: ${e.message?.take(160)}"
            "❌ Local AI failed after ${(System.currentTimeMillis() - start) / 1000}s: $lastError"
        }
    }

    /** Context about a chat message, given to the model so it can judge who has to do what. */
    data class ChatContext(
        val outgoing: Boolean,
        val chatName: String?,
        val sender: String?,
        val isGroup: Boolean,
        /** Earlier messages of the chat, oldest first, as "Name: text" lines — to understand replies and "it". */
        val recentMessages: List<String> = emptyList(),
        /** The user's own past verdicts, as (message, title or null if not a task) — few-shot personalisation. */
        val learnedExamples: List<Pair<String, String?>> = emptyList(),
        /** The message is a speech-to-text transcript of the owner's call (both speakers mixed). */
        val isCall: Boolean = false,
        /** Still-open tasks (title + deadline), so the model can tell when one gets done or changes. */
        val openTasks: List<String> = emptyList(),
        /** The message is several new chat lines ("Speaker: text"), read together. */
        val burst: Boolean = false
    )

    /** Model verdict: tasks found (possibly none) and, when none, why not. */
    data class ChatVerdict(
        val tasks: List<LlmTask>,
        val noTaskReason: String?,
        /** Indexes into [ChatContext.openTasks] that this message shows are already done. */
        val done: List<Int> = emptyList(),
        /** New deadline / clearer title for open tasks (index into [ChatContext.openTasks]). */
        val updates: List<TaskUpdate> = emptyList()
    )

    data class TaskUpdate(val index: Int, val whenPhrase: String?, val title: String?)

    /**
     * Reads the whole message (with the conversation around it) and decides which real tasks it holds.
     * Returns null if the model is unavailable or fails.
     */
    suspend fun extractTasks(message: String, ctx: ChatContext): ChatVerdict? {
        if (!isAvailable()) return null
        return withContext(Dispatchers.Default) {
            try {
                // A stuck model must not block chat processing forever
                // Messages queue for the one model; the timeout covers only this message's own run
                val raw = mutex.withLock {
                    kotlinx.coroutines.withTimeout(if (ctx.isCall) 2 * INFERENCE_TIMEOUT_MS else INFERENCE_TIMEOUT_MS) {
                        val llm = engine ?: createEngine().also { engine = it }
                        scheduleRelease()
                        ask(llm, fitPrompt(message.take(1500), ctx) { runCatching { llm.sizeInTokens(it) }.getOrDefault(it.length / 3) })
                    }
                }
                parseVerdict(raw)
            } catch (e: Throwable) {
                lastError = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
                Log.e(tag, "Local inference failed: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    /**
     * A valid .task bundle: a zip whose local-file signature "PK" is at byte 0, or at
     * byte 4 — real Gemma .task files start with 4 zero bytes before the zip data.
     */
    private fun isZip(f: File): Boolean = f.inputStream().use { s ->
        val b = ByteArray(8)
        val n = readFully(s, b)
        fun pkAt(o: Int) = n >= o + 4 && b[o] == 'P'.code.toByte() && b[o + 1] == 'K'.code.toByte() &&
            b[o + 2] == 3.toByte() && b[o + 3] == 4.toByte()
        pkAt(0) || pkAt(4)
    }

    /** True if the installed model file is a valid .task bundle (not e.g. a leftover .tar.gz). */
    fun installedModelLooksValid(): Boolean = isAvailable() && runCatching { isZip(modelFile) }.getOrDefault(false)

    /**
     * Installs a model the user picked: a .task file, Kaggle's .tar.gz, or a .zip wrapping a .task.
     * @return null on success, otherwise a plain-language reason.
     */
    suspend fun importModel(uri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        val (name, size) = describe(uri)
        val what = "\"$name\" (${size?.let { "%.2f GB".format(it / 1e9) } ?: "unknown size"})"
        val tmp = File(modelFile.parentFile, "$MODEL_FILE_NAME.part")
        try {
            mutex.withLock {
                engine?.close(); engine = null
                tmp.delete()
                // A broken earlier install (e.g. the .tar.gz copied as-is) only wastes space — remove it first
                if (!installedModelLooksValid()) modelFile.delete()
                val freeGb = (modelFile.parentFile?.usableSpace ?: 0L) / 1e9
                if (freeGb < 3.3) {
                    return@withLock "Not enough free storage: %.1f GB free, about 3.3 GB needed to unpack the model. Free up space and try again.".format(freeGb)
                }
                val head = ByteArray(4)
                val read = context.contentResolver.openInputStream(uri)?.use { readFully(it, head) }
                    ?: return@withLock "Couldn't open $what."
                val isGzip = read >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
                val isZipFile = read == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    when {
                        // Kaggle on a PC: .tar.gz with the .task inside
                        isGzip -> if (!extractTaskFromTarGz(input, tmp)) return@withLock "$what is a .tar.gz but has no .task model inside."
                        // Either a real .task (zip of TF_LITE_* parts) or a .zip wrapping one (Kaggle on a phone)
                        isZipFile -> if (!copyTaskOrUnwrapZip(input, tmp)) return@withLock "$what is a .zip but has no .task model inside."
                        else -> return@withLock "$what isn't a model file. Pick gemma-3n-E2B-it-int4.task or Kaggle's .tar.gz/.zip."
                    }
                }
                if (tmp.length() < 100L * 1024 * 1024 || !isZip(tmp)) {
                    val first = runCatching { tmp.inputStream().use { s -> ByteArray(8).also { readFully(s, it) } } }.getOrNull()
                        ?.joinToString(" ") { "%02x".format(it) }
                    return@withLock "$what doesn't contain a complete model. [debug: extracted ${tmp.length()} bytes, " +
                        "starts with $first, archive entries: ${lastTarEntries.joinToString()}]"
                }
                modelFile.delete()
                tmp.renameTo(modelFile)
                null
            }
        } catch (e: java.io.EOFException) {
            "$what ends early — the download is incomplete. Download it again (it should be about 2.5 GB as .tar.gz or 3.1 GB as .task)."
        } catch (e: Exception) {
            Log.e(tag, "Model import failed: ${e.javaClass.simpleName}")
            "Couldn't install $what: ${e.javaClass.simpleName} ${e.message?.take(80) ?: ""}"
        } finally {
            tmp.delete()
        }
    }

    private fun describe(uri: android.net.Uri): Pair<String, Long?> = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val n = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
            val s = c.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 }?.let(c::getLong)
            (n ?: "file") to s
        }
    }.getOrNull() ?: ("file" to null)

    /** Copies [input] if it already is a .task bundle; if it's a .zip wrapping a .task, extracts that instead. */
    private fun copyTaskOrUnwrapZip(input: java.io.InputStream, out: File): Boolean {
        // Copy first, then look inside — ZipInputStream can't rewind
        val copy = File(out.parentFile, "$MODEL_FILE_NAME.zip.part")
        try {
            copy.outputStream().use { input.copyTo(it, 1 shl 20) }
            val entries = java.util.zip.ZipFile(copy).use { z -> z.entries().toList().map { it.name } }
            if (entries.any { it.startsWith("TF_LITE_") }) return copy.renameTo(out)  // it's the model itself
            val inner = entries.firstOrNull { it.endsWith(".task") } ?: return false
            java.util.zip.ZipFile(copy).use { z ->
                z.getInputStream(z.getEntry(inner)).use { s -> out.outputStream().use { s.copyTo(it, 1 shl 20) } }
            }
            return true
        } finally {
            copy.delete()
        }
    }

    /** Backend the current engine runs on ("GPU" or "CPU"), for diagnostics. */
    @Volatile var backendInUse: String = "-"
        private set

    /** GPU is several times faster on the phone's Mali GPU; falls back to CPU (e.g. emulator). */
    private fun createEngine(): LlmInference {
        fun build(backend: LlmInference.Backend) = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            // Input + output tokens: the prompt carries conversation history and learned examples
            .setMaxTokens(2048)
            .setMaxTopK(1)
            .setPreferredBackend(backend)
            .build()
        // A failed GPU init can crash natively (uncatchable), so the attempt is marked beforehand:
        // if the marker survives a restart, the GPU crashed us and we stay on CPU.
        val prefs = context.getSharedPreferences("local_llm", Context.MODE_PRIVATE)
        val gpuCrashed = prefs.getBoolean("gpu_attempt_pending", false) || prefs.getBoolean("gpu_broken", false)
        if (gpuCrashed) prefs.edit().putBoolean("gpu_broken", true).putBoolean("gpu_attempt_pending", false).commit()
        // A 3 GB model on the GPU needs a second copy in GPU memory: on the 8 GB phone Android then
        // kills PAA (LOW_MEMORY) mid-message. Big models run on CPU — slower, but they finish.
        val smallModel = modelFile.length() < 1_500_000_000L
        if (!gpuCrashed && smallModel && hasOpenCl()) {
            prefs.edit().putBoolean("gpu_attempt_pending", true).commit()
            try {
                return LlmInference.createFromOptions(context, build(LlmInference.Backend.GPU)).also { backendInUse = "GPU" }
            } catch (e: Throwable) {
                Log.w(tag, "GPU backend unavailable, using CPU: ${e.javaClass.simpleName}")
                prefs.edit().putBoolean("gpu_broken", true).commit()
            } finally {
                prefs.edit().putBoolean("gpu_attempt_pending", false).commit()
            }
        }
        return LlmInference.createFromOptions(context, build(LlmInference.Backend.CPU)).also { backendInUse = "CPU" }
    }

    /** Greedy decoding: the engine's default session samples (temperature 0.8), which breaks the JSON. */
    /** Timing of the last model run, for the 🧠 card ("prompt 1480 tokens · 52 s"). */
    @Volatile var lastStats: String = "-"
        private set

    /**
     * Greedy decoding (the engine's default session samples at temperature 0.8, which breaks the JSON).
     * Async + cancel: the blocking generateResponse() ignores coroutine timeouts and kept the model busy.
     */
    private suspend fun ask(llm: LlmInference, prompt: String): String {
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(1).setTemperature(0f).build()
        val session = LlmInferenceSession.createFromOptions(llm, options)
        val start = System.currentTimeMillis()
        val tokens = runCatching { session.sizeInTokens(prompt) }.getOrDefault(-1)
        var future: com.google.common.util.concurrent.ListenableFuture<String>? = null
        try {
            session.addQueryChunk(prompt)
            val f = session.generateResponseAsync().also { future = it }
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching { session.cancelGenerateResponseAsync() } }
                f.addListener({
                    runCatching { f.get() }
                        .onSuccess { cont.resume(it) }
                        .onFailure { if (cont.isActive) cont.resumeWithException(it.cause ?: it) }
                }, Runnable::run)
            }
        } finally {
            lastStats = "prompt $tokens tokens · ${(System.currentTimeMillis() - start) / 1000} s"
            Log.i(tag, "Local AI: $lastStats")
            // Closing while native generation still runs can crash: wait (bounded) for it to stop
            withContext(NonCancellable) {
                withTimeoutOrNull(15_000) { while (future?.isDone == false) delay(100) }
                runCatching { session.close() }
            }
        }
    }

    private fun hasOpenCl(): Boolean = listOf("/vendor/lib64/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/system/lib64/libOpenCL.so")
        .any { File(it).exists() }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock {
                engine?.close()
                engine = null
            }
        }
    }

    /**
     * Free-form on-device answer (e.g. "how can I finish everything tonight?").
     * Returns null if the model is unavailable or fails.
     */
    suspend fun generate(prompt: String): String? {
        if (!isAvailable()) return null
        return withContext(Dispatchers.Default) {
            try {
                mutex.withLock {
                    val llm = engine ?: createEngine().also { engine = it }
                    scheduleRelease()
                    withTimeout(GENERATE_TIMEOUT_MS) { ask(llm, prompt) }.trim()
                }
            } catch (e: Throwable) {
                Log.e(tag, "Local generation failed: ${e.javaClass.simpleName}")
                null
            }
        }
    }


    /** Date arithmetic is done here, not by the model — small models are unreliable at it. */
    fun resolveTimestamp(day: String?, time: String?): Long? {
        if (day == null && time == null) return null
        val cal = Calendar.getInstance()
        val d = day?.lowercase(Locale.ENGLISH)?.trim()

        when {
            d == null || d == "today" || d == "tonight" -> Unit
            d == "tomorrow" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            d == "day after tomorrow" -> cal.add(Calendar.DAY_OF_YEAR, 2)
            Regex("\\d{4}-\\d{2}-\\d{2}").matches(d) -> {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(d) ?: return null
                cal.time = date
            }
            else -> {
                val target = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
                    .indexOf(d).takeIf { it >= 0 }?.plus(1) ?: return null
                do { cal.add(Calendar.DAY_OF_YEAR, 1) } while (cal.get(Calendar.DAY_OF_WEEK) != target)
            }
        }

        val match = time?.let { Regex("(\\d{1,2}):(\\d{2})").find(it) }
        cal.set(Calendar.HOUR_OF_DAY, match?.groupValues?.get(1)?.toInt()?.coerceIn(0, 23) ?: 9)
        cal.set(Calendar.MINUTE, match?.groupValues?.get(2)?.toInt()?.coerceIn(0, 59) ?: 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // A time-only answer that has already passed today means tomorrow
        if (d == null && cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
}

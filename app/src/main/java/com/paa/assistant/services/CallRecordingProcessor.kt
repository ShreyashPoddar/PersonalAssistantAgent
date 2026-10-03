package com.paa.assistant.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.paa.assistant.core.audio.VoskEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Recognizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * After a call ends, iQOO's own recorder saves both sides to Recordings/Record/Call/. This picks up
 * each new recording, transcribes it on the phone (Hindi model — calls are Hindi/Hinglish), and lets
 * the local AI schedule what was agreed. Nothing leaves the phone; the transcript isn't stored.
 *
 * Android gives apps no access to call audio *during* a call on this phone, so this is the only way
 * to hear the other person when on earpiece or earphones.
 */
@Singleton
class CallRecordingProcessor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val processor: ChatMessageProcessor
) {
    private val mutex = Mutex()
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    /** Starts watching for new call recordings (called from the always-bound notification listener). */
    fun watch(scope: CoroutineScope) {
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // The recorder writes and renames the file in steps — wait until it settles
                pending?.cancel()
                pending = scope.launch { delay(4_000); scanNew() }
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer!!)
        }.onFailure { Log.w(TAG, "Can't watch recordings: ${it.javaClass.simpleName}") }
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    /** Processes call recordings added since the last run (first run: only the last 10 minutes). */
    suspend fun scanNew() = mutex.withLock {
        val prefs = context.getSharedPreferences("call_recordings", Context.MODE_PRIVATE)
        val nowSec = System.currentTimeMillis() / 1000
        val since = prefs.getLong("last_added", nowSec - 600)
        val found = mutableListOf<Triple<Uri, String, Long>>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.DATE_ADDED} > ?",
                arrayOf("%Record/Call%", since.toString()),
                "${MediaStore.Audio.Media.DATE_ADDED} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    found += Triple(uri, c.getString(1) ?: "call", c.getLong(2))
                }
            }
        }.onFailure {
            DetectionLog.add("system", "Call recordings", "❌ can't read recordings — allow Music and audio for PAA on the 🧠 card")
            return@withLock
        }
        for ((uri, name, added) in found) {
            process(uri, contactOf(name))
            // Marked done only afterwards: if the app is killed mid-way, the recording is retried next time
            prefs.edit().putLong("last_added", added).apply()
        }
    }

    private suspend fun process(uri: Uri, contact: String) {
        DetectionLog.init(context)
        DetectionLog.add("📞 $contact", "call recording", "⏳ transcribing on the phone…")
        val transcript = withContext(Dispatchers.Default) { runCatching { transcribe(uri) }.getOrNull() }
        if (transcript.isNullOrBlank()) {
            DetectionLog.add("📞 $contact", "call recording", "❌ couldn't transcribe the recording")
            return
        }
        DetectionLog.add("📞 $contact", "call recording", "🧠 transcribed ${transcript.length} characters, finding tasks…")
        // Hindi text is token-heavy: ~600 characters at a time keeps the model within its time limit; overlapping pieces keep sentences together
        var total = 0
        for (piece in pieces(transcript)) {
            total += processor.process(
                ChatMessage(text = piece, app = ChatApp.CALL, outgoing = false, chatName = "call with $contact", sender = null, isGroup = false)
            )
        }
        DetectionLog.add("📞 $contact", "call recording", if (total > 0) "✅ $total task(s) from this call" else "no tasks in this call")
        if (total > 0) notifyDone(contact, total)
    }

    /** Decodes the recording (AAC etc.) to 16 kHz mono PCM and runs it through the Hindi model. */
    /** Decodes to 16 kHz mono, then transcribes 4 slices in parallel (~3× faster on the phone's cores). */
    private suspend fun transcribe(uri: Uri): String {
        val model = VoskEngine(context).loadHindiModel() ?: return ""
        val pcm = decodeToPcm16k(uri)
        if (pcm.isEmpty()) return ""
        val slices = slicesAtPauses(pcm, PARALLEL)
        return kotlinx.coroutines.coroutineScope {
            slices.map { (from, to) ->
                async(Dispatchers.Default) {
                    Recognizer(model, VoskEngine.SAMPLE_RATE).use { rec ->
                        val text = StringBuilder()
                        var i = from
                        while (i < to) {
                            val n = minOf(4000, to - i)
                            if (rec.acceptWaveForm(pcm.copyOfRange(i, i + n), n)) text.append(sentence(rec.result)).append(". ")
                            i += n
                        }
                        text.append(sentence(rec.finalResult)).toString()
                    }
                }
            }.awaitAll().joinToString(". ")
        }.replace(Regex("(\\.\\s*){2,}"), ". ").trim()
    }

    /** Whole recording as 16 kHz mono samples (a 5-minute call is ~10 MB). */
    private fun decodeToPcm16k(uri: Uri): ShortArray {
        val extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var inRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var all = ShortArray(1 shl 20)
        var size = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var pos = 0.0  // resampling position, in input frames
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.let { inRate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = buf.remaining() / channels
                    val mono = ShortArray(frames) { f -> var sum = 0; for (c in 0 until channels) sum += buf.get(f * channels + c); (sum / channels).toShort() }
                    codec.releaseOutputBuffer(o, false)
                    // Linear resample to 16 kHz
                    val step = inRate / VoskEngine.SAMPLE_RATE.toDouble()
                    val out = ShortArray((frames / step).toInt() + 2)
                    var n = 0
                    while (pos < frames - 1 && n < out.size) {
                        val k = pos.toInt(); val frac = pos - k
                        out[n++] = (mono[k] * (1 - frac) + mono[k + 1] * frac).toInt().toShort()
                        pos += step
                    }
                    pos = maxOf(0.0, pos - frames)
                    val pcm = out.copyOf(n)
                    if (size + pcm.size > all.size) all = all.copyOf(maxOf(all.size * 2, size + pcm.size))
                    pcm.copyInto(all, size); size += pcm.size
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop(); codec.release(); extractor.release()
        }
        return all.copyOf(size)
    }

    /** Splits into [parts] slices, each cut at the quietest moment near the even split point. */
    private fun slicesAtPauses(pcm: ShortArray, parts: Int): List<Pair<Int, Int>> {
        val cuts = mutableListOf(0)
        for (k in 1 until parts) {
            val target = pcm.size * k / parts
            var best = target; var bestEnergy = Long.MAX_VALUE
            // look ±3 s for the quietest 100 ms window
            var w = maxOf(cuts.last() + 1600, target - 48_000)
            while (w < minOf(pcm.size - 1600, target + 48_000)) {
                var e = 0L
                for (i in w until w + 1600) e += pcm[i].toLong() * pcm[i]
                if (e < bestEnergy) { bestEnergy = e; best = w + 800 }
                w += 800
            }
            cuts += best
        }
        cuts += pcm.size
        return cuts.zipWithNext()
    }

    private fun sentence(json: String?): String =
        json?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }?.trim().orEmpty()

    private fun notifyDone(contact: String, count: Int) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tasks from calls", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            contact.hashCode(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("📞 $count task${if (count == 1) "" else "s"} from your call with $contact")
                .setContentText("Open PAA to see them")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val TAG = "PAA_CallRec"
        private const val CHANNEL = "paa_call_tasks"
        private const val PARALLEL = 4

        /** "Alo 2026-10-02 19-46-01.m4a" → "Alo"; "+91 98765 43210 2026-…" → the number. */
        fun contactOf(fileName: String): String =
            fileName.substringBeforeLast('.').replace(Regex("\\s*\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}-\\d{2}-\\d{2}.*$"), "").trim().ifEmpty { "unknown" }

        /** ~600-character pieces, split at sentence ends, each starting with the end of the previous one. */
        fun pieces(t: String, size: Int = 600): List<String> {
            val sentences = t.split(Regex("(?<=\\.)\\s+")).filter { it.isNotBlank() }
            val out = mutableListOf<String>()
            var cur = StringBuilder()
            for (s in sentences) {
                if (cur.length + s.length > size && cur.isNotEmpty()) {
                    out += cur.toString().trim()
                    val tail = cur.toString().takeLast(150)
                    cur = StringBuilder(tail.substringAfter(". ", tail)).append(' ')
                }
                cur.append(s).append(' ')
            }
            if (cur.isNotBlank()) out += cur.toString().trim()
            return out
        }
    }
}

package com.paa.assistant.core.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import kotlin.coroutines.resume

/**
 * Fully offline speech recognition using Vosk and the English (India) model bundled in the APK
 * (assets/model-en-in). Nothing is downloaded and no audio leaves the phone.
 *
 * Used whenever Android's own on-device recognizer isn't available for en-IN.
 */
class VoskEngine(private val context: Context) {
    private val tag = "VoskEngine"
    private var service: SpeechService? = null

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    /** Copies the bundled model to app storage once (first run only), then loads it (shared app-wide). */
    suspend fun loadModel(): Model? {
        sharedModel?.let { return it }
        return suspendCancellableCoroutine { cont ->
            StorageService.unpack(
                context, ASSET_MODEL, "model-en-in",
                { m -> sharedModel = m; if (cont.isActive) cont.resume(m) },
                { e -> Log.e(tag, "Model unpack failed: ${e.javaClass.simpleName}"); if (cont.isActive) cont.resume(null) }
            )
        }
    }

    /** Hindi model (assets/model-hi): transcribes Hindi/Hinglish calls; English words come out in Devanagari. */
    suspend fun loadHindiModel(): Model? {
        sharedHindi?.let { return it }
        return suspendCancellableCoroutine { cont ->
            StorageService.unpack(
                context, "model-hi", "model-hi",
                { m -> sharedHindi = m; if (cont.isActive) cont.resume(m) },
                { e -> Log.e(tag, "Hindi model unpack failed: ${e.javaClass.simpleName}"); if (cont.isActive) cont.resume(null) }
            )
        }
    }

    suspend fun start(listener: Listener) {
        stop()
        val m = loadModel() ?: return listener.onError("Offline speech model couldn't be loaded.")
        try {
            val recognizer = Recognizer(m, SAMPLE_RATE)
            service = SpeechService(recognizer, SAMPLE_RATE).also { s ->
                s.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        field(hypothesis, "partial")?.let(listener::onPartial)
                    }

                    // Vosk calls onResult at the end of each spoken phrase (silence detected)
                    override fun onResult(hypothesis: String?) {
                        val text = field(hypothesis, "text") ?: return
                        stop()
                        listener.onFinal(text)
                    }

                    override fun onFinalResult(hypothesis: String?) {
                        field(hypothesis, "text")?.let(listener::onFinal)
                    }

                    override fun onError(exception: Exception?) {
                        stop()
                        listener.onError("Offline speech error: ${exception?.javaClass?.simpleName ?: "unknown"}")
                    }

                    override fun onTimeout() {
                        stop()
                        listener.onError("I didn't hear anything. Tap to speak.")
                    }
                }, LISTEN_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.e(tag, "Could not start offline recognition: ${e.javaClass.simpleName}")
            listener.onError("Could not start offline speech recognition.")
        }
    }

    /** Ends listening; any speech so far is delivered through onFinalResult. */
    fun stop() {
        service?.let {
            try { it.stop(); it.shutdown() } catch (_: Exception) {}
        }
        service = null
    }

    private fun field(json: String?, key: String): String? =
        json?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** One loaded model for the whole app (voice popup + "Oyee PA" listener). */
        @Volatile private var sharedModel: Model? = null
        @Volatile private var sharedHindi: Model? = null
        private const val ASSET_MODEL = "model-en-in"
        const val SAMPLE_RATE = 16000f
        private const val LISTEN_TIMEOUT_MS = 10_000
    }
}

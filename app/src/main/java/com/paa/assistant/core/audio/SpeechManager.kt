package com.paa.assistant.core.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Locale
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class SpeechEvent {
    data class Partial(val text: String) : SpeechEvent()
    data class Final(val text: String) : SpeechEvent()
    data class Error(val code: Int, val message: String, val isRecoverable: Boolean = true) : SpeechEvent()
    object ListeningStarted : SpeechEvent()
    object ListeningStopped : SpeechEvent()
}

/**
 * SpeechManager — handles all voice I/O for PAA.
 *
 * Input: Android SpeechRecognizer
 *  - Persistent reusable recognizer on Main Looper
 *  - Dynamic language selection matching device locale
 *  - Generous silence timeouts (2.5s) to prevent cutoffs
 *
 * Output: Android TextToSpeech engine
 *  - Instant on-device speech synthesis
 */
@Singleton
class SpeechManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "SpeechManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── SPEECH RECOGNIZER ─────────────────────────────────────────────────────
    private var recognizer: SpeechRecognizer? = null
    private val _speechEvents = Channel<SpeechEvent>(Channel.BUFFERED)
    val speechEvents: Flow<SpeechEvent> = _speechEvents.receiveAsFlow()
    private var lastRecognizedText: String = ""
    private var isListening = false
    private var fallbackCommitRunnable: Runnable? = null

    // ── TEXT-TO-SPEECH ────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        initializeTTS()
        initializeRecognizer()
    }

    private fun initializeTTS() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(0.95f)
                tts?.setPitch(1.0f)
                ttsReady = true
                Log.d(tag, "TTS initialized successfully")
            } else {
                Log.e(tag, "TTS initialization failed with status: $status")
            }
        }
    }

    private fun initializeRecognizer() {
        mainHandler.post {
            try {
                if (recognizer == null) {
                    recognizer = createRecognizer()
                    setupRecognitionListener()
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize SpeechRecognizer", e)
            }
        }
    }

    /** True while the on-device (offline) recognizer is in use. */
    private var usingOnDevice = false
    /** Set once on-device recognition fails for our language, so we stop trying it. */
    private var onDeviceUnsupported = false
    /** Ask for offline recognition; switched off if the phone has no offline pack for the language. */
    private var preferOffline = true
    private var lastLanguage = HINGLISH_LANGUAGE

    /** Bundled offline recognizer (assets/model-en-in) — used when Android can't recognise en-IN on-device. */
    private val vosk = VoskEngine(context)
    private var useVosk = false
    private val voskScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private fun androidOnDeviceAvailable(): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            !onDeviceUnsupported && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun startVosk() {
        isListening = true
        _speechEvents.trySend(SpeechEvent.ListeningStarted)
        voskScope.launch {
            vosk.start(object : VoskEngine.Listener {
                override fun onPartial(text: String) {
                    lastRecognizedText = text
                    _speechEvents.trySend(SpeechEvent.Partial(text))
                }
                override fun onFinal(text: String) {
                    if (!isListening) return
                    isListening = false
                    lastRecognizedText = ""
                    _speechEvents.trySend(SpeechEvent.Final(text))
                }
                override fun onError(message: String) {
                    isListening = false
                    _speechEvents.trySend(SpeechEvent.Error(0, message, isRecoverable = true))
                }
            })
        }
    }

    /**
     * Prefers Android's on-device recognizer (speech never leaves the phone) when available,
     * falling back to the standard recognizer otherwise.
     */
    companion object {
        /** English (India) handles Hinglish ("kal 5 baje call karna") far better than en-US. */
        const val HINGLISH_LANGUAGE = "en-IN"
        private val OFFLINE_FALLBACK_ERRORS = setOf(
            1,  // ERROR_NETWORK_TIMEOUT
            2,  // ERROR_NETWORK

            11, // ERROR_SERVER_DISCONNECTED
            12, // ERROR_LANGUAGE_NOT_SUPPORTED
            13, // ERROR_LANGUAGE_UNAVAILABLE
            14  // ERROR_CANNOT_CHECK_SUPPORT
        )
    }

    private fun createRecognizer(): SpeechRecognizer {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            !onDeviceUnsupported && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            usingOnDevice = true
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        usingOnDevice = false
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    /**
     * Asks Android to download the on-device English (India) speech model, so Hinglish voice
     * commands are transcribed without going to Google's servers. Returns a message for the user.
     */
    fun downloadOfflineModel(): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return "Offline speech download needs Android 13+. Use Settings → Google → Voice → Offline speech recognition → English (India)."
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return "This phone has no on-device speech recognizer. Install/update the \"Speech Recognition & Synthesis from Google\" app, then try again."
        }
        mainHandler.post {
            try {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                r.triggerModelDownload(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, HINGLISH_LANGUAGE)
                )
                r.destroy()
            } catch (e: Exception) {
                Log.w(tag, "Offline model download request failed: ${e.javaClass.simpleName}")
            }
        }
        onDeviceUnsupported = false  // try on-device / offline again once the model is in
        preferOffline = true
        useVosk = false
        recognizer?.destroy()
        recognizer = null
        return "Downloading the offline English (India) speech model. Follow Android's prompt if one appears; it finishes in the background."
    }

    private fun setupRecognitionListener() {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(tag, "onReadyForSpeech")
                isListening = true
                _speechEvents.trySend(SpeechEvent.ListeningStarted)
            }

            override fun onBeginningOfSpeech() {
                Log.d(tag, "onBeginningOfSpeech")
                cancelFallbackCommit()
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(tag, "onEndOfSpeech")
                isListening = false
                _speechEvents.trySend(SpeechEvent.ListeningStopped)

                // If onResults doesn't fire within 2000ms after end of speech, commit captured text
                if (lastRecognizedText.isNotBlank()) {
                    cancelFallbackCommit()
                    val runnable = Runnable {
                        if (lastRecognizedText.isNotBlank()) {
                            val committed = lastRecognizedText
                            lastRecognizedText = ""
                            Log.d(tag, "Fallback commit after end-of-speech")
                            _speechEvents.trySend(SpeechEvent.Final(committed))
                        }
                    }
                    fallbackCommitRunnable = runnable
                    mainHandler.postDelayed(runnable, 2000L)
                }
            }

            override fun onError(error: Int) {
                isListening = false
                cancelFallbackCommit()

                // If speech was already captured via partial results, deliver it as Final!
                if (lastRecognizedText.isNotBlank()) {
                    Log.d(tag, "Recovered from speech error ($error) using captured text: $lastRecognizedText")
                    val text = lastRecognizedText
                    lastRecognizedText = ""
                    _speechEvents.trySend(SpeechEvent.Final(text))
                    return
                }

                // Android's recognizer can't do en-IN offline here → use the bundled Vosk model instead
                if (error in OFFLINE_FALLBACK_ERRORS) {
                    Log.i(tag, "Speech engine: Vosk (Android recognizer error $error)")
                    useVosk = true
                    try { recognizer?.destroy() } catch (_: Exception) {}
                    recognizer = null
                    startListening(lastLanguage)
                    return
                }

                val isRecoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_CLIENT

                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "I didn't hear anything. Tap to speak."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out. Tap to speak."
                    SpeechRecognizer.ERROR_NETWORK -> "No internet connection for speech."
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error. Please check microphone."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Retrying..."
                    SpeechRecognizer.ERROR_CLIENT -> "Speech recognition cancelled."
                    else -> "Speech recognition error ($error)"
                }

                Log.w(tag, "SpeechRecognizer error: $error ($message)")
                _speechEvents.trySend(SpeechEvent.Error(error, message, isRecoverable))
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                cancelFallbackCommit()

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.trim()?.ifBlank { lastRecognizedText } ?: lastRecognizedText
                lastRecognizedText = ""

                if (text.isNotBlank()) {
                    _speechEvents.trySend(SpeechEvent.Final(text))
                } else {
                    _speechEvents.trySend(SpeechEvent.Error(SpeechRecognizer.ERROR_NO_MATCH, "Didn't catch that. Tap to speak.", isRecoverable = true))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim() ?: return

                if (partial.isNotBlank()) {
                    lastRecognizedText = partial
                    _speechEvents.trySend(SpeechEvent.Partial(partial))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onSegmentResults(segmentResults: Bundle) {}
            override fun onEndOfSegmentedSession() {}
        })
    }

    private fun cancelFallbackCommit() {
        fallbackCommitRunnable?.let { mainHandler.removeCallbacks(it) }
        fallbackCommitRunnable = null
    }

    /**
     * Start listening to the user.
     * Uses device default locale tag or supplied language.
     */
    fun startListening(language: String = HINGLISH_LANGUAGE) {
        lastLanguage = language
        // Fully offline: Android's on-device recognizer if it supports en-IN, else the bundled Vosk model
        if (useVosk || !androidOnDeviceAvailable()) {
            useVosk = true
            mainHandler.post { startVosk() }
            return
        }
        mainHandler.post {
            cancelFallbackCommit()
            lastRecognizedText = ""

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(tag, "SpeechRecognizer.isRecognitionAvailable returned false")
            }

            if (recognizer == null) {
                try {
                    recognizer = createRecognizer()
                    setupRecognitionListener()
                } catch (e: Exception) {
                    Log.e(tag, "Failed to create SpeechRecognizer", e)
                    _speechEvents.trySend(SpeechEvent.Error(0, "Speech recognizer initialization failed: ${e.message}", isRecoverable = false))
                    return@post
                }
            }

            try {
                recognizer?.cancel()
            } catch (_: Exception) {}

            val targetLang = if (language.isNotBlank()) language else Locale.getDefault().toLanguageTag()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLang)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Keep speech on the phone when an offline pack for this language is installed
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                // Relaxed silence tolerances (2.5 seconds) so natural thinking pauses don't cut off speech
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            }

            try {
                recognizer?.startListening(intent)
                Log.i(tag, "Speech engine: Android ${if (usingOnDevice) "on-device" else "standard"} ($targetLang)")
            } catch (e: Exception) {
                Log.e(tag, "startListening failed", e)
                _speechEvents.trySend(SpeechEvent.Error(0, "Could not start listening: ${e.message}", isRecoverable = true))
            }
        }
    }

    /**
     * Stop listening immediately.
     */
    fun stopListening() {
        if (useVosk) {
            vosk.stop()
            isListening = false
            return
        }
        mainHandler.post {
            cancelFallbackCommit()
            lastRecognizedText = ""
            isListening = false
            try { recognizer?.stopListening() } catch (_: Exception) {}
        }
    }

    /**
     * Commits any active partial transcription immediately and stops listening.
     */
    fun commitOrStop(): String? {
        cancelFallbackCommit()
        val text = lastRecognizedText
        lastRecognizedText = ""
        stopListening()
        return if (text.isNotBlank()) text else null
    }

    /**
     * Speak text using on-device TTS.
     */
    /** Forget a previous fallback to Vosk, unless Android really has no on-device recognizer. */
    fun resetEngineChoice() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            useVosk = false
            onDeviceUnsupported = false
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (tts == null) initializeTTS()
        if (!ttsReady) {
            Log.w(tag, "TTS not ready yet")
            return
        }
        val utteranceId = UUID.randomUUID().toString()

        if (onDone != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    mainHandler.post { onDone() }
                }
                override fun onError(utteranceId: String?) {
                    mainHandler.post { onDone() }
                }
            })
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Stop TTS playback immediately.
     */
    fun stopSpeaking() {
        tts?.stop()
    }

    /**
     * Release all resources.
     */
    fun release() {
        cancelFallbackCommit()
        mainHandler.post {
            try { recognizer?.destroy() } catch (_: Exception) {}
            recognizer = null
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}

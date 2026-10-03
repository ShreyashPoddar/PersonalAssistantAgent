package com.paa.assistant.services

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android requires a VoiceInteractionService to declare a recognitionService.
 * PAA does its own speech capture in SpeechManager, so this exposes no recognizer to other apps.
 */
class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}

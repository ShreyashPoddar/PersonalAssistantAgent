package com.paa.assistant.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telecom.DisconnectCause
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.paa.assistant.core.reminders.ReminderScheduler
import com.paa.assistant.core.router.LocalTaskParser
import com.paa.assistant.ui.theme.PAATheme
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The answered "PAA calls you" call: reads the task aloud (the owner opted in to speech for these
 * calls) and acts on the reply — "done", "snooze 30 minutes", "call me again at 6".
 */
class PaaCallActivity : ComponentActivity() {
    private val status = mutableStateOf("")
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        WakeListenerService.pauseForPopup(this)  // this call needs the microphone
        val call = PaaCallService.current
        if (call == null) { finish(); return }
        if (!intent.getBooleanExtra(EXTRA_ANSWER, true)) {
            // Declined: fall back to a normal reminder in 10 minutes
            ReminderScheduler.snooze(this, call.taskId, call.title, 10)
            call.end(DisconnectCause.REJECTED)
            finish(); return
        }
        if (call.state != android.telecom.Connection.STATE_ACTIVE) call.setActive()
        status.value = call.title
        setContent {
            PAATheme {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📞 PAA", fontSize = 28.sp)
                    Text(call.title, fontSize = 22.sp, modifier = Modifier.padding(16.dp))
                    Text(status.value, fontSize = 15.sp)
                    Button(onClick = { act(call, "done") }, modifier = Modifier.padding(top = 24.dp)) { Text("✓ Done") }
                    Button(onClick = { act(call, "snooze 10") }) { Text("⏱ 10 min later") }
                    Button(onClick = { hangUp(call) }) { Text("Hang up") }
                }
            }
        }
        tts = TextToSpeech(this) { ok ->
            if (ok != TextToSpeech.SUCCESS) return@TextToSpeech
            tts?.language = Locale("en", "IN")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) {}
                override fun onDone(id: String?) { if (id == "ask") runOnUiThread { listen(call) } }
            })
            tts?.speak("Reminder: ${call.title}. Say done, snooze, or call me again at a time.", TextToSpeech.QUEUE_FLUSH, null, "ask")
        }
    }

    private fun listen(call: PaaCallService.PaaConnection) {
        status.value = "🎙️ Listening… (done / snooze 30 minutes / call me again at 6)"
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(r: Bundle?) =
                    act(call, r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: "")
                override fun onError(error: Int) = act(call, "")
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }
    }

    private fun act(call: PaaCallService.PaaConnection, said: String) {
        val s = said.lowercase()
        val repo = dagger.hilt.android.EntryPointAccessors.fromApplication(applicationContext, RepositoryEntryPoint::class.java).repository()
        lifecycleScope.launch {
            val reply = when {
                Regex("\\b(done|ho gaya|ho gya|kar diya|finished|complete)").containsMatchIn(s) -> {
                    repo.completeTask(call.taskId); "Great, marked done."
                }
                Regex("call me (again )?(at|by)|baje").containsMatchIn(s) && LocalTaskParser().resolveWhen(s) != null -> {
                    val at = LocalTaskParser().resolveWhen(s)!!
                    repo.getTaskById(call.taskId)?.let { repo.updateTask(it.copy(dueTimestamp = at)) }
                    "Okay, I'll call you again at " + java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(java.util.Date(at)) + "."
                }
                else -> {
                    val minutes = Regex("(\\d+)\\s*(min|minute|ghante|hour)").find(s)?.let { m ->
                        m.groupValues[1].toInt() * if (m.groupValues[2].startsWith("h") || m.groupValues[2] == "ghante") 60 else 1
                    } ?: 10
                    ReminderScheduler.snooze(this@PaaCallActivity, call.taskId, call.title, minutes)
                    "Okay, I'll remind you in $minutes minutes."
                }
            }
            status.value = reply
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { runOnUiThread { hangUp(call) } }
                override fun onDone(id: String?) { runOnUiThread { hangUp(call) } }
            })
            tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "bye")
        }
    }

    private fun hangUp(call: PaaCallService.PaaConnection) {
        call.end(DisconnectCause.LOCAL)
        finish()
    }

    override fun onDestroy() {
        WakeListenerService.resumeAfterPopup(this)
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ANSWER = "answer"
        fun intent(context: Context, answer: Boolean) =
            Intent(context, PaaCallActivity::class.java).putExtra(EXTRA_ANSWER, answer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

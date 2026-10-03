package com.paa.assistant.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * One-off test: can this phone give PAA the *other person's* voice during a call?
 *
 * Android blocks call audio for normal apps, but some vendors let apps with an enabled accessibility
 * service read the call sources. Once a call starts, each source is opened for a few seconds and only its
 * loudness is measured — no audio is kept. The user stays silent while the other person talks, so a loud
 * VOICE_DOWNLINK / VOICE_CALL means the other side is readable.
 */
class CallAudioProbeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground("Call audio test armed", "Start a call within 3 min. When it connects, stay silent and let them talk ~25 s.")
        scope.launch { run() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        DetectionLog.init(this)
        WakeListenerService.stop(this)  // free the microphone
        DetectionLog.add("diagnostics", "Call audio test", "⏳ waiting for a call (3 min)…")
        val am = getSystemService(AudioManager::class.java)
        val deadline = System.currentTimeMillis() + 3 * 60_000L
        while (!inCall(am)) {
            if (System.currentTimeMillis() > deadline) {
                finish("❌ No call started within 3 minutes. Tap the test again, then call.")
                return
            }
            delay(1_000)
        }
        delay(4_000)  // let the call connect
        foreground("Call audio test running", "Stay silent — let the other person talk")
        val results = SOURCES.map { (name, src) -> name to level(src) }
        val report = results.joinToString(", ") { (n, db) -> "$n " + (db?.let { "%.0f dB".format(it) } ?: "blocked") }
        val other = results.filter { it.first in setOf("DOWNLINK", "CALL") }.mapNotNull { it.second }.maxOrNull()
        val verdict = when {
            other != null && other > -50 -> "✅ The other person's voice is readable on this phone!"
            other != null -> "⚠️ Call sources open but silent (other person not heard)"
            else -> "❌ This phone blocks call audio for apps"
        }
        finish("$verdict — $report")
    }

    /** Loudness (dBFS) of [SECONDS] from [source], or null if the phone refuses it. */
    private fun level(source: Int): Double? {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        } catch (e: Exception) { return null }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) return null
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null
            val buf = ShortArray(rate / 10)
            var sum = 0.0; var count = 0L
            val end = System.currentTimeMillis() + SECONDS * 1000
            while (System.currentTimeMillis() < end) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) return null
                for (i in 0 until n) { sum += buf[i].toDouble() * buf[i]; count++ }
            }
            if (count == 0L) return null
            val rms = sqrt(sum / count)
            return if (rms < 1) -96.0 else 20 * log10(rms / 32768.0)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun finish(result: String) {
        DetectionLog.add("diagnostics", "Call audio test", result)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Call audio test done").setContentText(result)
                .setStyle(NotificationCompat.BigTextStyle().bigText(result)).build()
        )
        WakeListenerService.startIfEnabled(this)
        stopSelf()
    }

    private fun inCall(am: AudioManager) =
        am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun foreground(title: String, text: String) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Call audio test", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title).setContentText(text).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIFICATION_ID, n)
    }

    companion object {
        private const val CHANNEL = "paa_call_probe"
        private const val NOTIFICATION_ID = 7401
        private const val SECONDS = 5
        private val SOURCES = listOf(
            "DOWNLINK" to MediaRecorder.AudioSource.VOICE_DOWNLINK,
            "CALL" to MediaRecorder.AudioSource.VOICE_CALL,
            "COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "MIC" to MediaRecorder.AudioSource.MIC,
        )

        fun start(context: Context) =
            context.startForegroundService(Intent(context, CallAudioProbeService::class.java))
    }
}

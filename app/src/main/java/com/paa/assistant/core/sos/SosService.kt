package com.paa.assistant.core.sos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.paa.assistant.core.profile.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SOS (P1-T9): countdown with Cancel → live location by SMS to the chosen contacts → optional siren → call →
 * location again every few minutes → "I'm safe" (after a fingerprint/PIN) stops it and tells everyone.
 * A drill sends only to the owner's own number, with no call and no siren. No model is involved.
 */
class SosService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var siren: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { stopAll(); return START_NOT_STICKY }
            ACTION_SAFE -> { scope.launch { sendAll(SosMessage.safe(name(), drill)); stopAll() }; return START_NOT_STICKY }
        }
        if (job != null) return START_NOT_STICKY
        drill = intent?.getBooleanExtra(EXTRA_DRILL, false) == true
        val settings = SosSettings(this)
        startInForeground(notification("SOS in ${settings.countdownSeconds} s — tap Cancel to stop", countdown = true))
        job = scope.launch {
            for (s in settings.countdownSeconds downTo 1) {
                nm().notify(ID, notification("SOS in $s s — tap Cancel to stop", countdown = true))
                vibrate()
                delay(1_000)
            }
            nm().notify(ID, notification(if (drill) "SOS drill running" else "SOS sent — sharing your location", countdown = false))
            if (settings.siren && !drill) startSiren()
            sendLocation()
            if (!drill) settings.callNumber()?.let { call(it) }
            val rounds = settings.durationMinutes / settings.repeatMinutes.coerceAtLeast(1)
            repeat(rounds) {
                delay(settings.repeatMinutes * 60_000L)
                sendLocation()
            }
            stopAll()
        }
        return START_NOT_STICKY
    }

    private fun name() = UserProfile.names.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "me"

    @SuppressLint("MissingPermission")
    private suspend fun sendLocation() {
        val loc = if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) runCatching {
            val client = LocationServices.getFusedLocationProviderClient(this)
            val cts = CancellationTokenSource()
            withTimeoutOrNull(15_000) {
                com.google.android.gms.tasks.Tasks.await(client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token))
            } ?: com.google.android.gms.tasks.Tasks.await(client.lastLocation)
        }.getOrNull() else null
        sendAll(SosMessage.text(name(), loc?.latitude, loc?.longitude, loc?.accuracy, System.currentTimeMillis(), drill))
    }

    private fun sendAll(text: String) {
        if (!granted(Manifest.permission.SEND_SMS)) { Log.w(TAG, "no SEND_SMS permission"); return }
        val s = SosSettings(this)
        val numbers = if (drill) listOf(s.ownNumber).filter { it.isNotBlank() } else s.contacts.map { it.number }
        val sms = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        for (n in numbers) runCatching { sms.sendMultipartTextMessage(n, null, sms.divideMessage(text), null, null) }
            .onFailure { Log.w(TAG, "sms failed: ${it.javaClass.simpleName}") }
    }

    private fun call(number: String) {
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        runCatching { startActivity(Intent(action, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun startSiren() = runCatching {
        val am = getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        siren = MediaPlayer().apply {
            setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
            setDataSource(this@SosService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            isLooping = true
            prepare(); start()
        }
    }

    private fun vibrate() = runCatching {
        val v = getSystemService(android.os.Vibrator::class.java)
        v.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun stopAll() {
        job?.cancel(); job = null
        siren?.let { runCatching { it.stop() }; it.release() }; siren = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); siren?.release(); super.onDestroy() }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun nm() = getSystemService(NotificationManager::class.java)

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            runCatching { startForeground(ID, n, type) }.onFailure { startForeground(ID, n) }
        } else startForeground(ID, n)
    }

    private fun notification(text: String, countdown: Boolean): Notification {
        nm().createNotificationChannel(NotificationChannel(CHANNEL, "SOS", NotificationManager.IMPORTANCE_HIGH))
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (drill) "PAA SOS (drill)" else "PAA SOS")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(!countdown)
        if (countdown) b.addAction(0, "Cancel", PendingIntent.getService(this, 1, Intent(this, SosService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE))
        else b.addAction(0, "I'm safe", PendingIntent.getActivity(this, 2, Intent(this, SosSafeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
        return b.build()
    }

    companion object {
        private const val TAG = "PAA_SOS"
        private const val CHANNEL = "paa_sos"
        private const val ID = 7911
        const val ACTION_CANCEL = "com.paa.assistant.SOS_CANCEL"
        const val ACTION_SAFE = "com.paa.assistant.SOS_SAFE"
        const val EXTRA_DRILL = "drill"
        @Volatile var drill = false

        fun start(ctx: Context, drill: Boolean) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, SosService::class.java).putExtra(EXTRA_DRILL, drill))
    }
}

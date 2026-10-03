package com.paa.assistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.paa.assistant.core.reminders.ReminderScheduler

/**
 * "PAA calls you": at a task's deadline PAA rings like a real incoming call (self-managed call, no SIM
 * involved). Answering opens [PaaCallActivity], which reads the task and listens for
 * "done" / "snooze 30 minutes" / "call me again at 6".
 */
class PaaCallService : ConnectionService() {

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        val extras = request?.extras ?: Bundle()
        val taskId = extras.getLong(ReminderScheduler.EXTRA_TASK_ID, -1L)
        val title = extras.getString(ReminderScheduler.EXTRA_TITLE) ?: "your task"
        return PaaConnection(applicationContext, taskId, title).apply {
            setAddress(Uri.fromParts("paa", "PAA", null), TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName("PAA — $title", TelecomManager.PRESENTATION_ALLOWED)
            connectionProperties = Connection.PROPERTY_SELF_MANAGED
            setAudioModeIsVoip(true)
            setRinging()
            current = this
        }
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        Log.w(TAG, "Couldn't ring (another call active?) — falling back to the normal alarm")
        val extras = request?.extras ?: return
        ReminderScheduler.snooze(applicationContext, extras.getLong(ReminderScheduler.EXTRA_TASK_ID), extras.getString(ReminderScheduler.EXTRA_TITLE) ?: "", 0)
    }

    class PaaConnection(private val context: Context, val taskId: Long, val title: String) : Connection() {
        /** Self-managed calls draw their own ringing UI: a call-style notification. */
        override fun onShowIncomingCallUi() {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "PAA calls you", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            })
            val answer = PendingIntent.getActivity(context, 1, PaaCallActivity.intent(context, answer = true), PendingIntent.FLAG_IMMUTABLE)
            val decline = PendingIntent.getActivity(context, 2, PaaCallActivity.intent(context, answer = false), PendingIntent.FLAG_IMMUTABLE)
            val builder = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("PAA is calling")
                .setContentText(title)
                .setFullScreenIntent(answer, true)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val caller = Person.Builder().setName("PAA — $title").setImportant(true).build()
                builder.setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
            } else {
                builder.addAction(Notification.Action.Builder(null, "Decline", decline).build())
                    .addAction(Notification.Action.Builder(null, "Answer", answer).build())
            }
            nm.notify(NOTIFICATION_ID, builder.build())
        }

        override fun onAnswer() {
            setActive()
            context.startActivity(PaaCallActivity.intent(context, answer = true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        override fun onReject() = end(DisconnectCause.REJECTED)
        override fun onDisconnect() = end(DisconnectCause.LOCAL)
        override fun onAbort() = end(DisconnectCause.CANCELED)

        fun end(cause: Int) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            setDisconnected(DisconnectCause(cause))
            destroy()
            if (current === this) current = null
        }
    }

    companion object {
        private const val TAG = "PaaCall"
        private const val CHANNEL = "paa_calls_you_v1"
        private const val NOTIFICATION_ID = 7501
        @Volatile var current: PaaConnection? = null

        private fun handle(context: Context) =
            PhoneAccountHandle(ComponentName(context, PaaCallService::class.java), "paa_calls_you")

        /** Rings now for [taskId]. Returns false if Android refused (then the normal alarm is used). */
        fun ring(context: Context, taskId: Long, title: String): Boolean = runCatching {
            val telecom = context.getSystemService(TelecomManager::class.java)
            telecom.registerPhoneAccount(
                PhoneAccount.builder(handle(context), "PAA").setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED).build()
            )
            telecom.addNewIncomingCall(handle(context), Bundle().apply {
                putLong(ReminderScheduler.EXTRA_TASK_ID, taskId)
                putString(ReminderScheduler.EXTRA_TITLE, title)
            })
            true
        }.onFailure { Log.w(TAG, "Ring failed: ${it.javaClass.simpleName}") }.getOrDefault(false)

        /** Tag on tasks the owner wants a call for. */
        const val TAG_CALL_ME = "#callme"
    }
}

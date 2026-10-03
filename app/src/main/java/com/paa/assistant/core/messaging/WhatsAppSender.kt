package com.paa.assistant.core.messaging

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.paa.assistant.services.ChatTaskAccessibilityService
import com.paa.assistant.services.DetectionLog
import com.paa.assistant.services.NotificationListener

/**
 * Sends a WhatsApp message the owner approved. Tries, in order:
 *  1. Reply through WhatsApp's own notification for that chat (works with the screen off).
 *  2. Open the chat with the text filled in and tap Send via the accessibility service (phone unlocked).
 *  3. Phone locked: a notification "Send to Riya" — one tap opens the chat ready to send.
 * Every send is logged; [DAILY_LIMIT] caps automated messages.
 */
object WhatsAppSender {
    private const val PREFS = "paa_sent_messages"
    const val DAILY_LIMIT = 20

    enum class Result { SENT, OPENED_FOR_TAP, LIMIT_REACHED, FAILED }

    fun send(context: Context, contact: Contact, text: String): Result {
        if (sentToday(context) >= DAILY_LIMIT) return Result.LIMIT_REACHED
        val result = when {
            replyViaNotification(context, contact.name, text) -> Result.SENT
            !isLocked(context) && ChatTaskAccessibilityService.isRunning() -> {
                ChatTaskAccessibilityService.autoSend(contact.name, text)
                openChat(context, contact, text)
                Result.SENT
            }
            else -> { notifyToSend(context, contact, text); Result.OPENED_FOR_TAP }
        }
        if (result == Result.SENT) countSent(context)
        DetectionLog.add("→ ${contact.name}", text, when (result) {
            Result.SENT -> "📤 sent by PAA"
            Result.OPENED_FOR_TAP -> "📤 ready — tap the notification to send"
            else -> "❌ not sent"
        })
        return result
    }

    /** Way 1: WhatsApp's notification for this chat has a "Reply" action we can fill in. */
    private fun replyViaNotification(context: Context, chatName: String, text: String): Boolean {
        val listener = NotificationListener.instance ?: return false
        val sbn = runCatching { listener.activeNotifications }.getOrNull()?.firstOrNull { n ->
            n.packageName.startsWith("com.whatsapp") &&
                (n.notification.extras.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)
                    ?: n.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
                    ?.toString()?.equals(chatName, ignoreCase = true) == true
        } ?: return false
        val action = sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true } ?: return false
        return runCatching {
            val fill = Intent()
            val results = Bundle().apply { action.remoteInputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(action.remoteInputs, fill, results)
            action.actionIntent.send(context, 0, fill)
            true
        }.getOrDefault(false)
    }

    private fun chatIntent(contact: Contact, text: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${contact.phoneE164.removePrefix("+")}?text=${Uri.encode(text)}"))
            .setPackage("com.whatsapp")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openChat(context: Context, contact: Contact, text: String) =
        context.startActivity(chatIntent(contact, text))

    /** Way 3: locked phone — one tap opens the chat with the text ready. */
    private fun notifyToSend(context: Context, contact: Contact, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("paa_send", "Messages ready to send", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(context, contact.phoneE164.hashCode(), chatIntent(contact, text), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            contact.phoneE164.hashCode(),
            NotificationCompat.Builder(context, "paa_send")
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle("Send to ${contact.name}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun isLocked(context: Context) = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun today() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    private fun sentToday(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(today(), 0)
    private fun countSent(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        it.edit().clear().putInt(today(), sentToday(context) + 1).apply()
    }
}

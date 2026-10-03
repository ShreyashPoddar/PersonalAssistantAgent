package com.paa.assistant.core.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.paa.assistant.services.ReminderActionReceiver
import com.paa.assistant.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds PAA's task notifications.
 *
 * Channels are versioned (…_v2): Android never lets an app raise the importance of an
 * existing channel, so a channel that was once created quiet would stay quiet forever.
 */
object ReminderNotifier {
    private const val CHANNEL_DUE = "paa_task_due_v2"
    private const val CHANNEL_EARLY = "paa_task_early_v2"
    private const val CHANNEL_SCHEDULED = "paa_task_scheduled_v2"
    private const val CHANNEL_QUIET = "paa_task_quiet_v2"

    const val ACTION_DONE = "com.paa.assistant.ACTION_MARK_DONE"
    const val ACTION_SNOOZE = "com.paa.assistant.ACTION_SNOOZE"
    const val ACTION_UNDO = "com.paa.assistant.ACTION_UNDO_SCHEDULE"
    const val ACTION_REOPEN = "com.paa.assistant.ACTION_REOPEN"

    fun notificationId(taskId: Long) = (taskId % Int.MAX_VALUE).toInt()
    private fun scheduledNotificationId(taskId: Long) = notificationId(taskId) + 500_000

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DUE, "Task deadlines", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alarm-style alert when a task is due"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 300, 700, 300, 700)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), alarmAudio)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EARLY, "Upcoming task heads-up", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminder shortly before a task is due"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, "Task alerts during quiet hours", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Silent task alerts outside the 7 AM–7 PM ringing window"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SCHEDULED, "Tasks scheduled from chats", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Confirmation when PAA auto-schedules a task found in WhatsApp/Telegram"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    /** The reminder itself: heads-up (EARLY) or alarm-style with repeating sound (DUE). */
    /** @param registerUrl hackathon tasks: adds "📝 Register now" (assisted registration in PAA's browser). */
    fun showReminder(context: Context, taskId: Long, title: String, kind: String, due: Long, registerUrl: String? = null) {
        ensureChannels(context)
        val isCatchUp = kind == ReminderScheduler.KIND_CATCHUP
        val isDue = kind == ReminderScheduler.KIND_DUE || isCatchUp
        // Outside 7 AM–7 PM nothing rings: silent notification only
        val quiet = !ReminderScheduler.isRingTime()
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(due))
        val channel = when {
            quiet -> CHANNEL_QUIET
            isDue -> CHANNEL_DUE
            else -> CHANNEL_EARLY
        }

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(
                when {
                    isCatchUp -> "⏰ Overdue: $title"
                    isDue -> "⏰ Due now: $title"
                    else -> "🔔 Coming up: $title"
                }
            )
            .setContentText(
                when {
                    isCatchUp -> "Deadline was $time"
                    isDue -> "Deadline $time"
                    else -> "Due at $time"
                }
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (isDue) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_DUE)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(if (isDue) "⏰ A task is due" else "🔔 Task coming up")
                    .build()
            )
            .setAutoCancel(true)
            .setContentIntent(openApp(context, taskId))
            .addAction(0, "✓ Done", action(context, ACTION_DONE, taskId, title, 1))
            .addAction(0, "⏱ Snooze 10m", action(context, ACTION_SNOOZE, taskId, title, 2))
        registerUrl?.let { url ->
            builder.addAction(0, "📝 Register now", PendingIntent.getActivity(
                context, notificationId(taskId) + 900_000,
                com.paa.assistant.core.hackathon.RegisterActivity.intent(context, url, taskId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        }

        if (quiet) builder.setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true)
        if (isDue && !quiet) {
            // Pops over the lock screen like an alarm clock
            builder.setFullScreenIntent(openApp(context, taskId), true)
        }

        val notification = builder.build()
        // DUE alerts keep ringing until you tap Done, Snooze or open it (only inside the ringing window)
        if (isDue && !quiet) notification.flags = notification.flags or Notification.FLAG_INSISTENT

        context.getSystemService(NotificationManager::class.java).notify(notificationId(taskId), notification)
    }

    /** Confirmation after a chat task was auto-scheduled, with Undo. */
    fun showScheduled(context: Context, taskId: Long, title: String, due: Long, source: String, isDefaultDeadline: Boolean, reason: String? = null) {
        ensureChannels(context)
        val whenText = SimpleDateFormat("h:mm a, MMM d", Locale.getDefault()).format(Date(due)) +
            if (isDefaultDeadline) " (end of day)" else ""

        val notification = NotificationCompat.Builder(context, CHANNEL_SCHEDULED)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("📌 Scheduled: $title")
            .setContentText("Due $whenText • from $source")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Due $whenText • from $source" + (reason?.let { "\nWhy: $it" } ?: "")
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_SCHEDULED)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("📌 PAA scheduled a task")
                    .setContentText("Unlock to view")
                    .build()
            )
            .setAutoCancel(true)
            .setContentIntent(openApp(context, taskId))
            .addAction(0, "↩ Undo", action(context, ACTION_UNDO, taskId, title, 3))
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(scheduledNotificationId(taskId), notification)
    }

    /** A chat message showed the task is already done; "Not done" reopens it. */
    fun showAutoCompleted(context: Context, taskId: Long, title: String, chat: String) {
        ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(notificationId(taskId))
        val notification = NotificationCompat.Builder(context, CHANNEL_SCHEDULED)
            .setSmallIcon(android.R.drawable.checkbox_on_background)
            .setContentTitle("✅ Done: $title")
            .setContentText("Marked complete from your chat with $chat")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, taskId))
            .addAction(0, "↩ Not done", action(context, ACTION_REOPEN, taskId, title, 4))
            .build()
        nm.notify(scheduledNotificationId(taskId), notification)
    }

    fun cancelScheduled(context: Context, taskId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(scheduledNotificationId(taskId))
    }

    private fun openApp(context: Context, taskId: Long): PendingIntent = PendingIntent.getActivity(
        context, notificationId(taskId),
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun action(context: Context, action: String, taskId: Long, title: String, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, notificationId(taskId) * 4 + slot,
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(action)
                .putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId)
                .putExtra(ReminderScheduler.EXTRA_TITLE, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

package com.paa.assistant.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.paa.assistant.core.messaging.ContactResolver
import com.paa.assistant.core.reminders.ReminderNotifier
import com.paa.assistant.core.reminders.ReminderScheduler
import com.paa.assistant.data.db.AppDatabase
import com.paa.assistant.services.ReminderActionReceiver
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Smart follow-ups: a chat task that is 2+ hours overdue gets one nudge a day (07:00–19:00) —
 * "You told Rahul you'd send the ppt" with "Open chat" and "✓ Done". Runs every 2 hours, on the phone only.
 */
@HiltWorker
class FollowUpWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val database: AppDatabase
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!ReminderScheduler.isRingTime()) return Result.success()
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences("paa_followups", Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now))
        database.taskDao().getOverdueTasks(now - 2 * 3_600_000L)
            .filter { it.sourceApp.startsWith("WhatsApp") }
            .filter { prefs.getString(it.id.toString(), null) != today }
            .take(3)
            .forEach { task ->
                prefs.edit().putString(task.id.toString(), today).apply()
                notify(task.id, task.title, chatOf(task.sourceApp))
            }
        return Result.success()
    }

    /** "WhatsApp (Rahul)" / "WhatsApp (Praveen in GOA)" / "WhatsApp (your message to Rahul)" → chat name. */
    private fun chatOf(sourceApp: String): String? = Regex("\\((?:your message to |.* in )?(.+)\\)$").find(sourceApp)?.groupValues?.get(1)

    private fun notify(taskId: Long, title: String, chat: String?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Follow-ups on overdue chat tasks", NotificationManager.IMPORTANCE_DEFAULT))
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Still open: $title")
            .setContentText(chat?.let { "From your chat with $it — want to reply?" } ?: "This is overdue.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .addAction(0, "✓ Done", PendingIntent.getBroadcast(
                context, (taskId % 100_000).toInt() + 300_000,
                Intent(context, ReminderActionReceiver::class.java).setAction(ReminderNotifier.ACTION_DONE)
                    .putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId).putExtra(ReminderScheduler.EXTRA_TITLE, title),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
        // Personal chats: open the conversation directly (needs Contacts permission to find the number)
        chat?.let { ContactResolver.find(context, it).singleOrNull() }?.let { contact ->
            builder.addAction(0, "💬 Open chat", PendingIntent.getActivity(
                context, (taskId % 100_000).toInt() + 400_000,
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${contact.phoneE164.removePrefix("+")}"))
                    .setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            ))
        }
        nm.notify((taskId % 100_000).toInt() + 800_000, builder.build())
    }

    companion object { private const val CHANNEL = "paa_followups" }
}

package com.paa.assistant.services

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.paa.assistant.data.db.AppDatabase
import com.paa.assistant.data.models.TaskEntity
import com.paa.assistant.widget.TaskGlanceWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * QuickScheduleReceiver — handles inline 1-tap task scheduling from
 * WhatsApp notification action buttons and Accessibility prompts.
 *
 * Runs fully in the background without launching any activity,
 * inserts the task into Room DB, schedules the AlarmManager reminder,
 * and updates the homescreen widget instantly.
 */
class QuickScheduleReceiver : BroadcastReceiver() {

    private val tag = "QuickScheduleReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_QUICK_SCHEDULE) return

        val title = intent.getStringExtra(EXTRA_TASK_TITLE) ?: "WhatsApp Task"
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, -1L).takeIf { it > 0 }
        val tags = intent.getStringExtra(EXTRA_TAGS) ?: "#whatsapp"
        val sourceApp = intent.getStringExtra(EXTRA_SOURCE_APP) ?: "WhatsApp"
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)

        val nm = context.getSystemService(NotificationManager::class.java)
        if (notifId != -1) {
            nm.cancel(notifId)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val task = TaskEntity(
                    title = title,
                    dueTimestamp = timestamp,
                    priority = 2,
                    tags = tags,
                    sourceApp = sourceApp
                )
                val taskId = db.taskDao().insertTask(task)

                // Schedule alarm if timestamp is valid
                if (timestamp != null && timestamp > System.currentTimeMillis()) {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    val reminderIntent = Intent(context, ReminderReceiver::class.java).apply {
                        action = "com.paa.assistant.ACTION_REMINDER_FIRE"
                        putExtra("task_id", taskId)
                        putExtra("task_title", title)
                    }
                    val pi = PendingIntent.getBroadcast(
                        context,
                        taskId.toInt(),
                        reminderIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    try {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pi)
                    } catch (e: Exception) {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, timestamp, pi)
                    }
                }

                // Update Glance home widget
                try {
                    val glanceManager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
                    val glanceIds = glanceManager.getGlanceIds(TaskGlanceWidget::class.java)
                    glanceIds.forEach { id -> TaskGlanceWidget().update(context, id) }
                } catch (e: Exception) {
                    Log.w(tag, "Widget update failed: ${e.message}")
                }

                // Show confirmation notification
                val timeFormatted = if (timestamp != null) {
                    " for " + SimpleDateFormat("h:mm a, MMM d", Locale.getDefault()).format(Date(timestamp))
                } else ""
                showConfirmationNotification(context, title, timeFormatted)

            } catch (e: Exception) {
                Log.e(tag, "Error saving quick-scheduled task", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showConfirmationNotification(context: Context, title: String, timeInfo: String) {
        val channelId = "paa_scheduled_confirm"
        val nm = context.getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(channelId, "PAA Scheduling Confirmations", NotificationManager.IMPORTANCE_LOW)
        )

        val notif = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("✓ Task Scheduled from WhatsApp")
            .setContentText("'$title'$timeInfo")
            .setAutoCancel(true)
            .setTimeoutAfter(4000)
            .build()

        nm.notify((System.currentTimeMillis() % 10000).toInt(), notif)
    }

    companion object {
        const val ACTION_QUICK_SCHEDULE = "com.paa.assistant.ACTION_QUICK_SCHEDULE"
        const val EXTRA_TASK_TITLE = "extra_task_title"
        const val EXTRA_TIMESTAMP = "extra_timestamp"
        const val EXTRA_TAGS = "extra_tags"
        const val EXTRA_SOURCE_APP = "extra_source_app"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
    }
}

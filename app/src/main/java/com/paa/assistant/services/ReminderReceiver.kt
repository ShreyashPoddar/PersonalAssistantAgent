package com.paa.assistant.services

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.paa.assistant.core.reminders.ReminderNotifier
import com.paa.assistant.core.reminders.ReminderScheduler
import com.paa.assistant.data.db.AppDatabase
import com.paa.assistant.widget.TaskGlanceWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ReminderReceiver — fired by AlarmManager when a task's EARLY heads-up or DUE alarm is reached.
 * Also handles BOOT_COMPLETED to reschedule all pending alarms after a restart.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderScheduler.ACTION_FIRE -> fireReminder(context, intent)
            Intent.ACTION_BOOT_COMPLETED -> rescheduleOnBoot(context)
        }
    }

    private fun fireReminder(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "Task reminder"
        val kind = intent.getStringExtra(ReminderScheduler.EXTRA_KIND) ?: ReminderScheduler.KIND_DUE
        val due = intent.getLongExtra(ReminderScheduler.EXTRA_DUE, System.currentTimeMillis())
        if (taskId == -1L) return

        // Skip alerts for tasks completed or deleted since the alarm was set
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val task = AppDatabase.getInstance(context).taskDao().getById(taskId)
                if (task != null && task.status == "PENDING") {
                    ReminderNotifier.showReminder(context, taskId, task.title.ifBlank { title }, kind, due)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun rescheduleOnBoot(context: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<com.paa.assistant.workers.RescheduleAlarmsWorker>().build()
        androidx.work.WorkManager.getInstance(context).enqueue(request)
    }
}

/**
 * Handles notification buttons without opening the app:
 * ✓ Done, ⏱ Snooze 10m, and ↩ Undo for auto-scheduled chat tasks.
 */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1L)
        if (taskId == -1L) return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "Task reminder"
        context.getSystemService(NotificationManager::class.java).cancel(ReminderNotifier.notificationId(taskId))

        when (intent.action) {
            ReminderNotifier.ACTION_SNOOZE -> ReminderScheduler.snooze(context, taskId, title, 10)
            ReminderNotifier.ACTION_REOPEN -> {
                ReminderNotifier.cancelScheduled(context, taskId)
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val dao = AppDatabase.getInstance(context).taskDao()
                        dao.updateStatus(taskId, "PENDING")
                        dao.getById(taskId)?.let { t ->
                            val due = t.dueTimestamp?.takeIf { it > System.currentTimeMillis() } ?: ReminderScheduler.endOfToday()
                            ReminderScheduler.schedule(context, t.id, t.title, due, t.reminderMinutesBefore)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ReminderNotifier.ACTION_DONE, ReminderNotifier.ACTION_UNDO -> {
                ReminderScheduler.cancel(context, taskId)
                ReminderNotifier.cancelScheduled(context, taskId)
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = AppDatabase.getInstance(context)
                        val dao = db.taskDao()
                        if (intent.action == ReminderNotifier.ACTION_UNDO) {
                            // Undo on an auto-detected task = "that wasn't a task" → teaches the model
                            dao.getById(taskId)?.sourceMessage?.let { msg ->
                                db.feedbackDao().insert(
                                    com.paa.assistant.data.models.TaskFeedbackEntity(message = msg, title = title, isTask = false)
                                )
                            }
                            dao.deleteById(taskId)
                        } else {
                            // Done on an auto-detected task confirms it really was a task
                            dao.getById(taskId)?.sourceMessage?.let { msg ->
                                db.feedbackDao().insert(
                                    com.paa.assistant.data.models.TaskFeedbackEntity(message = msg, title = title, isTask = true)
                                )
                            }
                            // Through the repository: removes geofences, moves repeating tasks to their next time
                            dagger.hilt.android.EntryPointAccessors.fromApplication(context, RepositoryEntryPoint::class.java)
                                .repository().completeTask(taskId)
                        }
                        try {
                            val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
                            manager.getGlanceIds(TaskGlanceWidget::class.java)
                                .forEach { TaskGlanceWidget().update(context, it) }
                        } catch (_: Exception) {}
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}

/** Hilt access from plain BroadcastReceivers. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface RepositoryEntryPoint {
    fun repository(): com.paa.assistant.data.repository.TaskRepository
}

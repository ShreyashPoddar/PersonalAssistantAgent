package com.paa.assistant.data.repository

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.paa.assistant.core.location.GeofenceManager
import com.paa.assistant.core.reminders.ReminderNotifier
import com.paa.assistant.core.reminders.ReminderScheduler
import com.paa.assistant.core.reminders.Recurrence
import com.paa.assistant.data.db.AppDatabase
import com.paa.assistant.data.models.MemoryFactEntity
import com.paa.assistant.data.models.TaskEntity
import com.paa.assistant.services.ReminderReceiver
import com.paa.assistant.widget.TaskGlanceWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TaskRepository — single source of truth for all task operations.
 *
 * Handles:
 *  - CRUD operations against Room DB
 *  - AlarmManager reminder scheduling
 *  - GeofenceManager hardware perimeter triggers
 *  - Glance widget invalidation after every state change
 *  - Memory fact management
 */
@Singleton
class TaskRepository @Inject constructor(
    @ApplicationContext val context: Context,
    private val db: AppDatabase,
    private val geofenceManager: GeofenceManager
) {
    private val taskDao = db.taskDao()
    private val memoryDao = db.memoryDao()

    // ── TASK CRUD ─────────────────────────────────────────────────────────────

    suspend fun createTask(task: TaskEntity): Long {
        val id = taskDao.insertTask(task)
        val createdTask = task.copy(id = id)
        // Schedule alarm if due time is set
        if (task.dueTimestamp != null) {
            scheduleReminder(
                taskId = id,
                title = task.title,
                dueTimestamp = task.dueTimestamp,
                reminderMinutesBefore = task.reminderMinutesBefore
            )
        }
        // Register hardware geofence if location is set
        if (task.latitude != null && task.longitude != null) {
            geofenceManager.addGeofence(createdTask)
        }
        refreshWidget()
        return id
    }

    /**
     * Done. A repeating task moves to its next occurrence instead of closing.
     * @return true if it was a repeating task that moved on.
     */
    suspend fun completeTask(taskId: Long): Boolean {
        val task = taskDao.getById(taskId)
        val rule = task?.recurrenceRule?.takeIf { it.isNotBlank() }
        val next = rule?.let { Recurrence.next(it, task.dueTimestamp ?: System.currentTimeMillis()) }
        if (task != null && next != null) {
            updateTask(task.copy(dueTimestamp = next))
            return true
        }
        taskDao.updateStatus(taskId, "COMPLETED")
        cancelReminder(taskId)
        geofenceManager.removeGeofence(taskId)
        refreshWidget()
        return false
    }

    /** Repeating tasks whose deadline passed long ago (never ticked off) move to their next occurrence. */
    suspend fun advanceMissedRecurring(graceMs: Long = 12 * 3_600_000L) {
        val now = System.currentTimeMillis()
        taskDao.getOverdueTasks(now - graceMs).filter { !it.recurrenceRule.isNullOrBlank() }.forEach { t ->
            Recurrence.next(t.recurrenceRule!!, t.dueTimestamp!!, now)?.let { updateTask(t.copy(dueTimestamp = it)) }
        }
    }

    /** Moves a task to [due]: the one matching [titleHint], else the last one created/changed. */
    suspend fun rescheduleTask(titleHint: String?, due: Long): TaskEntity? {
        // Name given: exact substring first, else the pending task sharing the most words ("github repo" ~ "GitHub repository")
        val named = titleHint?.let { hint ->
            taskDao.searchByTitle(hint).firstOrNull() ?: run {
                val words = hint.lowercase().split(Regex("\\W+")).filter { it.length >= 3 }
                taskDao.getUpcomingTasks(100).maxByOrNull { t ->
                    val title = t.title.lowercase()
                    words.count { w -> title.contains(w) || title.split(Regex("\\W+")).any { it.startsWith(w) } }
                }?.takeIf { t -> words.any { w -> t.title.lowercase().contains(w) } }
            }
        }
        val task = named ?: taskDao.getLastTouchedPending() ?: return null
        val moved = task.copy(dueTimestamp = due)
        updateTask(moved)
        return moved
    }

    suspend fun completeTaskByTitleFuzzy(titleQuery: String): Boolean {
        val matches = taskDao.searchByTitle(titleQuery)
        return if (matches.isNotEmpty()) {
            completeTask(matches.first().id)
            true
        } else false
    }

    suspend fun deleteTask(taskId: Long) {
        taskDao.deleteById(taskId)
        cancelReminder(taskId)
        geofenceManager.removeGeofence(taskId)
        refreshWidget()
    }

    suspend fun deleteTaskByTitleFuzzy(titleQuery: String): Boolean {
        val matches = taskDao.searchByTitle(titleQuery)
        return if (matches.isNotEmpty()) {
            deleteTask(matches.first().id)
            true
        } else false
    }

    suspend fun updateTask(task: TaskEntity) {
        taskDao.updateTask(task.copy(updatedAt = System.currentTimeMillis()))
        if (task.dueTimestamp != null) {
            cancelReminder(task.id)
            scheduleReminder(task.id, task.title, task.dueTimestamp, task.reminderMinutesBefore)
        }
        if (task.latitude != null && task.longitude != null) {
            geofenceManager.removeGeofence(task.id)
            geofenceManager.addGeofence(task)
        }
        refreshWidget()
    }

    suspend fun getTaskById(taskId: Long): TaskEntity? = taskDao.getById(taskId)

    /** Deletes every pending task auto-detected from chats (and its alarms). Returns how many. */
    suspend fun deleteAllChatTasks(): Int {
        val tasks = taskDao.getPendingChatTasks()
        tasks.forEach { deleteTask(it.id) }
        return tasks.size
    }

    suspend fun recreateAllGeofences() {
        geofenceManager.recreateAllGeofences()
    }

    // ── QUERIES ───────────────────────────────────────────────────────────────

    fun observePendingTasks(): Flow<List<TaskEntity>> = taskDao.observePendingTasks()

    fun observeTodayTasks(): Flow<List<TaskEntity>> {
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 86_400_000L
        return taskDao.observeTodayTasks(dayStart, dayEnd)
    }

    suspend fun getUpcomingTasks(limit: Int = 5) = taskDao.getUpcomingTasks(limit)
    suspend fun getOverdueTasks() = taskDao.getOverdueTasks()

    // ── ALARMS ────────────────────────────────────────────────────────────────

    private fun scheduleReminder(
        taskId: Long,
        title: String,
        dueTimestamp: Long,
        reminderMinutesBefore: Int
    ) {
        val early = if (reminderMinutesBefore > 0) reminderMinutesBefore else ReminderScheduler.DEFAULT_EARLY_MINUTES
        ReminderScheduler.schedule(context, taskId, title, dueTimestamp, early)
    }

    private fun cancelReminder(taskId: Long) {
        ReminderScheduler.cancel(context, taskId)
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(ReminderNotifier.notificationId(taskId))
    }

    // ── WIDGET REFRESH ────────────────────────────────────────────────────────

    private suspend fun refreshWidget() {
        try {
            val manager = GlanceAppWidgetManager(context)
            val ids = manager.getGlanceIds(TaskGlanceWidget::class.java)
            ids.forEach { id -> TaskGlanceWidget().update(context, id) }
        } catch (e: Exception) {
            // Widget may not be placed on homescreen yet — ignore
        }
    }

    // ── MEMORY FACTS ──────────────────────────────────────────────────────────

    suspend fun saveFact(topic: String, content: String) {
        memoryDao.insertFact(MemoryFactEntity(keyTopic = topic, factContent = content))
    }

    suspend fun deleteFact(id: Long) {
        memoryDao.deleteFact(id)
    }

    suspend fun getRecentFacts(limit: Int = 20): List<String> =
        memoryDao.getRecentFacts(limit).map { it.factContent }

    fun observeAllFacts(): Flow<List<MemoryFactEntity>> = memoryDao.observeAllFacts()

    fun observeAllFiles(): Flow<List<com.paa.assistant.data.models.FileIndexEntity>> =
        db.fileIndexDao().observeAllFiles()
}

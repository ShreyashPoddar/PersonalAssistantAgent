package com.paa.assistant.data.db

import androidx.room.*
import com.paa.assistant.data.models.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    /** Live list for the home-screen widget: pending tasks, soonest deadline first (undated last). */
    @Query("""
        SELECT * FROM tasks
        WHERE status = 'PENDING'
        ORDER BY dueTimestamp IS NULL, dueTimestamp ASC, priority DESC
        LIMIT :limit
    """)
    fun observeUpcomingTasks(limit: Int): Flow<List<TaskEntity>>

    @Query("SELECT COUNT(*) FROM tasks WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /** Pending tasks that PAA created automatically from chats (sourceApp "WhatsApp (…)"). */
    @Query("SELECT * FROM tasks WHERE status = 'PENDING' AND sourceApp LIKE 'WhatsApp%'")
    suspend fun getPendingChatTasks(): List<TaskEntity>

    // ── INSERT / UPSERT ─────────────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    // ── UPDATE ──────────────────────────────────────────────────────────────
    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("UPDATE tasks SET status = :status, updatedAt = :now WHERE id = :taskId")
    suspend fun updateStatus(taskId: Long, status: String, now: Long = System.currentTimeMillis())

    // ── DELETE ──────────────────────────────────────────────────────────────
    @Delete
    suspend fun deleteTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteById(taskId: Long)

    // ── QUERY: ALL ACTIVE TASKS (Flow for reactive UI) ─────────────────────
    @Query("SELECT * FROM tasks WHERE status = 'PENDING' ORDER BY priority DESC, dueTimestamp ASC")
    fun observePendingTasks(): Flow<List<TaskEntity>>

    // ── QUERY: TODAY'S TASKS ─────────────────────────────────────────────────
    @Query("""
        SELECT * FROM tasks 
        WHERE status = 'PENDING' 
          AND dueTimestamp >= :dayStart 
          AND dueTimestamp < :dayEnd
        ORDER BY dueTimestamp ASC
    """)
    fun observeTodayTasks(dayStart: Long, dayEnd: Long): Flow<List<TaskEntity>>

    // ── QUERY: OVERDUE TASKS ─────────────────────────────────────────────────
    @Query("""
        SELECT * FROM tasks
        WHERE status = 'PENDING'
          AND dueTimestamp IS NOT NULL
          AND dueTimestamp < :now
        ORDER BY dueTimestamp ASC
    """)
    suspend fun getOverdueTasks(now: Long = System.currentTimeMillis()): List<TaskEntity>

    // ── QUERY: UPCOMING (next N tasks regardless of date) ────────────────────
    @Query("""
        SELECT * FROM tasks
        WHERE status = 'PENDING'
        ORDER BY dueTimestamp ASC
        LIMIT :limit
    """)
    suspend fun getUpcomingTasks(limit: Int = 5): List<TaskEntity>

    // ── QUERY: SINGLE TASK BY ID ─────────────────────────────────────────────
    /** The task the user most recently created or changed — what "that"/"it" refers to. */
    @Query("SELECT * FROM tasks WHERE status = 'PENDING' ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun getLastTouchedPending(): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :taskId LIMIT 1")
    suspend fun getById(taskId: Long): TaskEntity?

    // ── QUERY: FULL TEXT SEARCH (fuzzy match for "mark X as done" parsing) ──
    @Query("SELECT * FROM tasks WHERE status = 'PENDING' AND title LIKE '%' || :query || '%' LIMIT 5")
    suspend fun searchByTitle(query: String): List<TaskEntity>

    // ── QUERY: COMPLETED TODAY (for widget daily progress ring) ──────────────
    @Query("""
        SELECT COUNT(*) FROM tasks
        WHERE status = 'COMPLETED'
          AND updatedAt >= :dayStart
    """)
    suspend fun countCompletedToday(dayStart: Long): Int

    // ── QUERY: TASKS WITH ACTIVE GEOFENCE ────────────────────────────────────
    @Query("SELECT * FROM tasks WHERE status = 'PENDING' AND latitude IS NOT NULL AND longitude IS NOT NULL")
    suspend fun getPendingTasksWithGeofence(): List<TaskEntity>
}

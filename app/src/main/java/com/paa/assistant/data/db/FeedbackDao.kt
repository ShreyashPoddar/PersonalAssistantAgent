package com.paa.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.paa.assistant.data.models.TaskFeedbackEntity

@Dao
interface FeedbackDao {
    @Insert
    suspend fun insert(feedback: TaskFeedbackEntity): Long

    @Query("SELECT * FROM task_feedback WHERE isTask = :isTask ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(isTask: Boolean, limit: Int): List<TaskFeedbackEntity>

    /** Keeps the table small: only the newest [keep] entries matter for the prompt. */
    @Query("DELETE FROM task_feedback WHERE id NOT IN (SELECT id FROM task_feedback ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 200)
}

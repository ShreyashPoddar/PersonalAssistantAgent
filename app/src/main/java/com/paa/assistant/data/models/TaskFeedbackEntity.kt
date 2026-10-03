package com.paa.assistant.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The user's verdict on a chat message PAA judged: ✓ Yes (it was a task), ✗ No / Undo (it wasn't).
 * The most recent ones are shown to the on-device model as examples, so it adapts to the user
 * without any retraining. Stored in the encrypted database; never leaves the phone.
 */
@Entity(tableName = "task_feedback")
data class TaskFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val message: String,
    /** The task title PAA proposed. */
    val title: String,
    val isTask: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

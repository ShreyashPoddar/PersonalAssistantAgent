package com.paa.assistant.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single task/reminder in the PAA system.
 *
 * Priority levels:
 *  0 = LOW
 *  1 = MEDIUM (default)
 *  2 = HIGH
 *  3 = URGENT
 *
 * Status values: "PENDING", "COMPLETED", "CANCELLED"
 *
 * recurrenceRule: iCal RRULE format e.g. "RRULE:FREQ=WEEKLY;BYDAY=MO"
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String? = null,
    val category: String = "general",   // general, work, personal, finance, health
    val priority: Int = 1,
    val status: String = "PENDING",
    val dueTimestamp: Long? = null,
    val recurrenceRule: String? = null,
    val reminderMinutesBefore: Int = 0,
    val tags: String = "",              // comma-separated: "#work,#finance"
    val sourceApp: String = "paa",     // paa, whatsapp, calendar
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val geofenceRadiusMeters: Float = 150f,
    val geofenceTransition: Int = 1,    // 1 = ENTER, 2 = EXIT
    /** For chat-detected tasks: the message it came from (shown to the user, used to learn from Undo). */
    val sourceMessage: String? = null,
    /** For chat-detected tasks: the on-device model's explanation of why this is a task. */
    val aiReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

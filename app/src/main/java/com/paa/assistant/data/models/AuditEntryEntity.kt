package com.paa.assistant.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One line per action PAA was asked to do: what, who asked, what the Guard said, and how it went. */
@Entity(tableName = "audit_log")
data class AuditEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long, val actionType: String, val tier: String, val source: String,
    val summary: String, val verdict: String, val ok: Boolean, val resultMessage: String
)

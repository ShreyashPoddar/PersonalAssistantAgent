package com.paa.assistant.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Per-contact rule for outward actions (messages, SMS, calls, email). */
@Entity(tableName = "contact_rules")
data class ContactRuleEntity(
    @PrimaryKey val contactKey: String,      // normalised name or phone number, lowercase
    val outward: String = "PREVIEW",         // AUTO | PREVIEW | ALWAYS_ASK
    val autoReplyDuringCall: Boolean = false,
    val messagedBefore: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

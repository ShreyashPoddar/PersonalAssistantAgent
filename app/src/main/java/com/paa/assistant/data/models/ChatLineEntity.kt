package com.paa.assistant.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line of a chat as PAA saw it ("Riya: kal tak bhej dena" / "You: ok"), so the model can read
 * the conversation across restarts. Encrypted database; never leaves the phone.
 */
@Entity(tableName = "chat_history", indices = [Index("chatKey")])
data class ChatLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatKey: String,
    val line: String,
    val createdAt: Long = System.currentTimeMillis()
)

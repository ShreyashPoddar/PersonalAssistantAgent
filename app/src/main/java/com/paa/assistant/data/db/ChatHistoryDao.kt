package com.paa.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.paa.assistant.data.models.ChatLineEntity

@Dao
interface ChatHistoryDao {
    @Insert
    suspend fun insert(line: ChatLineEntity)

    /** Newest first. */
    @Query("SELECT line FROM chat_history WHERE chatKey = :chatKey ORDER BY id DESC LIMIT :limit")
    suspend fun recent(chatKey: String, limit: Int): List<String>

    @Query("UPDATE chat_history SET pending = 0 WHERE chatKey = :chatKey AND pending = 1")
    suspend fun markRead(chatKey: String)

    /** Lines whose burst was never read, oldest first. */
    @Query("SELECT * FROM chat_history WHERE pending = 1 ORDER BY id ASC")
    suspend fun pending(): List<ChatLineEntity>

    /** Keeps only the newest [keep] lines of a chat. */
    @Query("DELETE FROM chat_history WHERE chatKey = :chatKey AND id NOT IN (SELECT id FROM chat_history WHERE chatKey = :chatKey ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(chatKey: String, keep: Int = 40)
}

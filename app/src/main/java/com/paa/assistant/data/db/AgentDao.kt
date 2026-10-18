package com.paa.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.paa.assistant.data.models.AuditEntryEntity
import com.paa.assistant.data.models.ContactRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactRuleDao {
    @Query("SELECT * FROM contact_rules WHERE contactKey = :key")
    suspend fun get(key: String): ContactRuleEntity?

    @Upsert
    suspend fun upsert(rule: ContactRuleEntity)

    @Query("SELECT * FROM contact_rules WHERE autoReplyDuringCall = 1")
    suspend fun autoReplyOn(): List<ContactRuleEntity>

    @Query("SELECT * FROM contact_rules ORDER BY contactKey")
    fun all(): Flow<List<ContactRuleEntity>>
}

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: AuditEntryEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<AuditEntryEntity>>

    @Query("DELETE FROM audit_log WHERE at < :time")
    suspend fun deleteOlderThan(time: Long)
}

package com.paa.assistant.data.db

import androidx.room.*
import com.paa.assistant.data.models.MemoryFactEntity
import com.paa.assistant.data.models.FileIndexEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFact(fact: MemoryFactEntity): Long

    @Update
    suspend fun updateFact(fact: MemoryFactEntity)

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteFact(id: Long)

    @Query("SELECT * FROM memory_facts ORDER BY lastReferencedAt DESC")
    fun observeAllFacts(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts ORDER BY lastReferencedAt DESC LIMIT :limit")
    suspend fun getRecentFacts(limit: Int = 20): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE keyTopic = :topic")
    suspend fun getByTopic(topic: String): List<MemoryFactEntity>
}

@Dao
interface FileIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: FileIndexEntity): Long

    @Query("DELETE FROM file_index WHERE id = :id")
    suspend fun deleteFile(id: Long)

    @Query("SELECT * FROM file_index ORDER BY lastModified DESC")
    fun observeAllFiles(): Flow<List<FileIndexEntity>>

    @Query("SELECT * FROM file_index WHERE fileName LIKE '%' || :name || '%' LIMIT 3")
    suspend fun searchByName(name: String): List<FileIndexEntity>

    @Query("SELECT * FROM file_index WHERE directoryHint = :hint ORDER BY lastModified DESC")
    suspend fun getByDirectory(hint: String): List<FileIndexEntity>
}

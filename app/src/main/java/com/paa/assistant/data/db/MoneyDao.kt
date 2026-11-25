package com.paa.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.paa.assistant.data.models.LedgerEntryEntity
import com.paa.assistant.data.models.MerchantRuleEntity
import com.paa.assistant.data.models.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MoneyDao {
    /** Same id (same ref/amount/direction) from SMS and notification → kept once. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(t: TransactionEntity): Long

    @Query("SELECT * FROM transactions WHERE at >= :from AND at < :to ORDER BY at DESC")
    fun observeRange(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE at >= :from ORDER BY at DESC")
    suspend fun since(from: Long): List<TransactionEntity>

    @Query("UPDATE transactions SET category = :category WHERE id = :id")
    suspend fun setCategory(id: String, category: String)

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertRule(r: MerchantRuleEntity)

    @Query("SELECT * FROM merchant_rules")
    suspend fun rules(): List<MerchantRuleEntity>

    @Insert
    suspend fun addIou(e: LedgerEntryEntity): Long

    @Query("SELECT * FROM ledger WHERE settledAt IS NULL ORDER BY createdAt DESC")
    fun observeOpenIous(): Flow<List<LedgerEntryEntity>>

    @Query("SELECT * FROM ledger WHERE settledAt IS NULL")
    suspend fun openIous(): List<LedgerEntryEntity>

    @Query("UPDATE ledger SET settledAt = :at WHERE id = :id")
    suspend fun settle(id: Long, at: Long)
}

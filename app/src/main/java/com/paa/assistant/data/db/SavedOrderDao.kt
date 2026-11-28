package com.paa.assistant.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.paa.assistant.data.models.SavedOrderEntity

@Dao
interface SavedOrderDao {
    /** Saving under an existing label replaces it ("save this as my usual breakfast" again). */
    @Upsert
    suspend fun upsert(e: SavedOrderEntity)

    @Query("SELECT * FROM saved_orders ORDER BY timesUsed DESC, savedAt DESC")
    suspend fun all(): List<SavedOrderEntity>

    @Query("UPDATE saved_orders SET timesUsed = timesUsed + 1 WHERE label = :label")
    suspend fun markUsed(label: String)

    @Query("DELETE FROM saved_orders WHERE label = :label")
    suspend fun delete(label: String)
}

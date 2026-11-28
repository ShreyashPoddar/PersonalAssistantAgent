package com.paa.assistant.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.paa.assistant.core.money.SavedOrders

/** An order the owner saved as "my usual X" (P3-T6). Encrypted DB, never leaves the phone. */
@Entity(tableName = "saved_orders")
data class SavedOrderEntity(
    @PrimaryKey val label: String,
    val app: String, val cartSummary: String, val amountPaise: Long, val upiApp: String?,
    val savedAt: Long, val timesUsed: Int,
) {
    fun toSaved() = SavedOrders.Saved(label, app, cartSummary, amountPaise, upiApp, savedAt, timesUsed)

    companion object {
        fun of(s: SavedOrders.Saved) = SavedOrderEntity(s.label, s.app, s.cartSummary, s.amountPaise, s.upiApp, s.savedAt, s.timesUsed)
    }
}

package com.paa.assistant.data.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.paa.assistant.core.money.Iou
import com.paa.assistant.core.money.Txn

/** A money movement read from a bank SMS / UPI notification (P2-T4). Encrypted DB, never leaves the phone. */
@Entity(tableName = "transactions", indices = [Index("at")])
data class TransactionEntity(
    @PrimaryKey val id: String,
    val at: Long, val amountPaise: Long, val direction: String, val channel: String,
    val counterparty: String?, val refId: String?, val balancePaise: Long?, val bank: String,
    val category: String? = null, val note: String? = null, val source: String
) {
    fun toTxn() = Txn(id, at, amountPaise, direction, channel, counterparty, refId, balancePaise, bank, source)

    companion object {
        fun of(t: Txn, category: String?) =
            TransactionEntity(t.id, t.at, t.amountPaise, t.direction, t.channel, t.counterparty, t.refId, t.balancePaise, t.bank, category, null, t.source)
    }
}

/** The owner's correction: this counterparty is always this category (P2-T5). */
@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(@PrimaryKey val counterpartyKey: String, val category: String)

/** Who owes whom (P2-T7). */
@Entity(tableName = "ledger")
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val person: String, val amountPaise: Long, val direction: String, val reason: String?,
    val sourceChat: String?, val createdAt: Long, val settledAt: Long? = null
) {
    fun toIou() = Iou(id, person, amountPaise, direction, reason, createdAt, settledAt)
}

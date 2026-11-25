package com.paa.assistant.core.money

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.paa.assistant.data.db.MoneyDao
import com.paa.assistant.data.models.TransactionEntity
import com.paa.assistant.services.DetectionLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bank SMS / UPI notification → stored transaction (P2-T4/T5/T7): parsed, categorised (owner's rules first), kept
 * once (SMS + notification of the same payment share an id), and checked against open IOUs — a match is only
 * suggested. Bank text is as private as chats: never logged (the log gets "1 transaction", no amounts or names).
 */
@Singleton
class MoneyIntake @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: MoneyDao
) {
    /** @return true when a new transaction was stored. */
    suspend fun add(text: String, sender: String, at: Long, source: String): Boolean {
        val txn = BankSmsParser.parse(text, sender, at, source) ?: return false
        val rules = dao.rules().associate { it.counterpartyKey to it.category }
        val stored = dao.insert(TransactionEntity.of(txn, Categorizer.categorize(txn, rules))) != -1L
        if (!stored) return false
        DetectionLog.add("money", source.lowercase(), "💰 1 transaction saved")
        Ledger.match(txn, dao.openIous().map { it.toIou() })?.let { suggestSettle(it.person, it.direction) }
        return true
    }

    /** SMS history (needs READ_SMS): bank senders only, last [days] days. @return how many were new. */
    suspend fun backfill(days: Int = 180): Int {
        var n = 0
        for (sms in SmsInbox.recent(ctx, System.currentTimeMillis() - days * 86_400_000L)) {
            if (BankSmsParser.bankOf(sms.sender) != null && add(sms.body, sms.sender, sms.at, "SMS")) n++
        }
        return n
    }

    private fun suggestSettle(person: String, direction: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("paa_money", "Money", NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(
            ctx, 7501, android.content.Intent(ctx, com.paa.assistant.ui.main.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            7501, NotificationCompat.Builder(ctx, "paa_money")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (direction == "OWED_TO_ME") "Did $person pay you back?" else "Did you pay $person back?")
                .setContentText("A matching payment came in. Open PAA → Money to mark it settled.")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(open).setAutoCancel(true).build()
        )
    }
}

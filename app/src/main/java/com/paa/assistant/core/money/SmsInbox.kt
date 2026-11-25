package com.paa.assistant.core.money

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RawSms(val sender: String, val body: String, val at: Long)

/** Reads the SMS inbox on demand (P2-T2/T4). Only bank senders are ever used; nothing else is kept. */
object SmsInbox {
    fun canRead(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun recent(ctx: Context, since: Long): List<RawSms> {
        if (!canRead(ctx)) return emptyList()
        return runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                val out = mutableListOf<RawSms>()
                while (c.moveToNext()) {
                    val addr = c.getString(0) ?: continue
                    if (BankSmsParser.bankOf(addr) == null) continue  // bank senders only
                    out += RawSms(addr, c.getString(1) ?: continue, c.getLong(2))
                }
                out
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
}

/** New bank SMS as they arrive (RECEIVE_SMS). Messages from anyone who isn't a bank are ignored, not read further. */
@AndroidEntryPoint
class BankSmsReceiver : BroadcastReceiver() {
    @Inject lateinit var intake: MoneyIntake

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = parts.firstOrNull()?.originatingAddress ?: return
        if (BankSmsParser.bankOf(sender) == null) return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val at = parts.first().timestampMillis
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { intake.add(body, sender, at, "SMS") } finally { pending.finish() }
        }
    }
}

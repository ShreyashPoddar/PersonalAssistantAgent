package com.paa.assistant.core.money

import java.security.MessageDigest
import java.util.Locale

/** One money movement read from a bank SMS or a UPI-app notification (never leaves the phone). */
data class Txn(
    val id: String, val at: Long, val amountPaise: Long, val direction: String, val channel: String,
    val counterparty: String?, val refId: String?, val balancePaise: Long?, val bank: String, val source: String
)

/**
 * Bank SMS / UPI notification → [Txn] (P2-T4). Pure Kotlin, tested on synthetic messages only. Returns null for
 * anything that isn't a completed debit/credit (OTPs, promos, failed/declined, "request" messages, reminders).
 */
object BankSmsParser {
    /** Bank SMS sender IDs (the part after "XX-"), upper-case. */
    private val BANK_SENDERS = mapOf(
        "AXISBK" to "AXIS", "AXISBN" to "AXIS", "AXISMR" to "AXIS", "HDFCBK" to "HDFC", "ICICIB" to "ICICI", "ICICIT" to "ICICI",
        "SBIINB" to "SBI", "SBIUPI" to "SBI", "SBMSMS" to "SBI", "KOTAKB" to "KOTAK", "PNBSMS" to "PNB", "BOIIND" to "BOI",
        "YESBNK" to "YES", "IDFCFB" to "IDFC", "INDUSB" to "INDUSIND", "CANBNK" to "CANARA", "UNIONB" to "UNION", "PAYTMB" to "PAYTM"
    )
    val UPI_PACKAGES = setOf(
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "com.axis.mobile", "com.csam.icici.bank.imobile", "com.sbi.upi", "com.snapwork.hdfc", "com.mobikwik_new"
    )

    private val AMOUNT = Regex("(?:INR|Rs\\.?|₹)\\s?([\\d,]+(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE)
    private val DEBIT = Regex("\\b(debited|spent|withdrawn|paid|sent|deducted|purchase|dr\\.?)\\b", RegexOption.IGNORE_CASE)
    private val CREDIT = Regex("\\b(credited|received|deposited|refunded|cr\\.?)\\b", RegexOption.IGNORE_CASE)
    private val NOT_DONE = Regex(
        "\\b(otp|one time password|declined|failed|unsuccessful|reversed|will be debited|request(ed)? (money|payment)|has requested|" +
            "collect request|due (on|by)|payment due|min(imum)? amount due|emi of|offer|cashback upto|win|get up to|pre-approved|" +
            "loan|limit (is|has)|is due|auto ?pay (set|scheduled)|mandate)\\b",
        RegexOption.IGNORE_CASE
    )
    private val BALANCE = Regex(
        "(?:Avl|Available|Avail\\.?|Bal(?:ance)?)\\.? ?(?:Bal(?:ance)?|Limit)?[:\\s]*(?:is )?(?:INR|Rs\\.?|₹)\\s?([\\d,]+(?:\\.\\d{1,2})?)",
        RegexOption.IGNORE_CASE
    )
    private val REF = Regex("(?:UPI Ref(?:\\. No)?|Ref(?:erence)?(?: No)?|RRN|Txn(?: ID)?|UTR)[.:\\s#-]*([A-Za-z0-9]{6,})", RegexOption.IGNORE_CASE)
    private val VPA = Regex("\\b([\\w.\\-]{2,}@[a-zA-Z]{2,})\\b")
    private val COUNTERPARTY = Regex(
        "\\b(?:to|at|from|by|towards|info:?|trf to|paid to|sent to|received from)\\s+(?:VPA\\s+)?([A-Za-z0-9][A-Za-z0-9 &.'*_-]{1,40}?)" +
            "(?=\\s+(?:on|via|using|for|ref|upi|avl|avail|bal|-|\\.|\\(|\\d{1,2}[-/])|[.,;(]|$)",
        RegexOption.IGNORE_CASE
    )

    fun bankOf(sender: String): String? {
        // "AX-AXISBK", "VK-SBIUPI-S", "AXISBK": any part may be the bank code
        return sender.uppercase(Locale.ROOT).split('-').firstNotNullOfOrNull { BANK_SENDERS[it.take(6)] }
    }

    private fun paise(s: String): Long? = s.replace(",", "").toBigDecimalOrNull()?.movePointRight(2)?.toLong()

    /**
     * @param sender SMS sender ("AX-AXISBK") or a notification's package; [bank] is guessed from it.
     * @param source "SMS" or "NOTIF".
     */
    fun parse(text: String, sender: String, at: Long, source: String = "SMS"): Txn? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (NOT_DONE.containsMatchIn(t)) return null
        val amount = AMOUNT.find(t)?.groupValues?.get(1)?.let(::paise)?.takeIf { it > 0 } ?: return null
        val dIdx = DEBIT.find(t)?.range?.first ?: Int.MAX_VALUE
        val cIdx = CREDIT.find(t)?.range?.first ?: Int.MAX_VALUE
        if (dIdx == Int.MAX_VALUE && cIdx == Int.MAX_VALUE) return null
        val direction = if (dIdx <= cIdx) "DEBIT" else "CREDIT"
        val channel = when {
            Regex("\\bATM\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "ATM"
            Regex("\\b(UPI|VPA)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) || VPA.containsMatchIn(t) -> "UPI"
            Regex("\\b(NEFT|IMPS|RTGS)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "NEFT"
            Regex("\\b(card|POS)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "CARD"
            else -> "OTHER"
        }
        // The balance amount must not be mistaken for the counterparty or the amount
        val balance = BALANCE.findAll(t).lastOrNull()?.takeIf { it.range.first > (AMOUNT.find(t)?.range?.last ?: 0) }
            ?.groupValues?.get(1)?.let(::paise)
        val counterparty = VPA.find(t)?.groupValues?.get(1)
            ?: COUNTERPARTY.findAll(t).map { it.groupValues[1].trim() }
                .firstOrNull { c -> !Regex("^(a/c|ac|acct|account|your|xx|\\*|the|rs|inr|upi)\\b", RegexOption.IGNORE_CASE).containsMatchIn(c) && c.any(Char::isLetter) }
        val ref = REF.find(t)?.groupValues?.get(1)
        val bank = bankOf(sender) ?: if (source == "NOTIF") "UPI" else "OTHER"
        val id = sha("${ref ?: ""}|$amount|$direction|${ref?.let { "" } ?: (at / 60_000)}")
        return Txn(id, at, amount, direction, channel, counterparty?.take(60), ref, balance, bank, source)
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}

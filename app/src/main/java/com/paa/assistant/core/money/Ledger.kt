package com.paa.assistant.core.money

import java.util.Locale

/** One "who owes whom" entry (P2-T7). */
data class Iou(
    val id: Long, val person: String, val amountPaise: Long, val direction: String,  // OWED_TO_ME | I_OWE
    val reason: String?, val createdAt: Long, val settledAt: Long? = null
)

object Ledger {
    private const val WINDOW = 7 * 24 * 3_600_000L

    /**
     * An open IOU a transaction probably settles: same amount, the right direction (money in for OWED_TO_ME, out for
     * I_OWE), within 7 days after the IOU, and the person's name appears in the counterparty (when it says anything).
     * Only ever *suggested* — the owner taps to settle.
     */
    fun match(t: Txn, open: List<Iou>): Iou? = open.filter { i ->
        i.settledAt == null && i.amountPaise == t.amountPaise &&
            ((i.direction == "OWED_TO_ME" && t.direction == "CREDIT") || (i.direction == "I_OWE" && t.direction == "DEBIT")) &&
            t.at >= i.createdAt - 3_600_000L && t.at - i.createdAt <= WINDOW &&
            nameFits(i.person, t.counterparty)
    }.minByOrNull { kotlin.math.abs(t.at - it.createdAt) }

    private fun nameFits(person: String, cp: String?): Boolean {
        if (cp.isNullOrBlank()) return true
        val c = cp.lowercase(Locale.ROOT)
        return person.lowercase(Locale.ROOT).split(Regex("\\s+")).any { it.length >= 3 && it in c }
    }

    private val NOTE = Regex(
        "^(?:note (?:that )?|yaad rakh(?:o|na)? (?:ki )?)?([a-z][a-z ]{1,30}?) (owes me|ko mujhe|se lene hai|ko dene hai|i owe)\\s*(?:rs\\.?|₹|inr)?\\s*([\\d,]+)(?:\\s*(?:rs|rupees|rupay|rupaye))?(?:\\s+(?:for|ke liye)\\s+(.+))?$",
        RegexOption.IGNORE_CASE
    )
    /** "Arpit se 1500 lene hai" (owed to me) / "Riya ko 200 dene hai" (I owe). */
    private val HINDI = Regex(
        "^([a-z][a-z ]{1,30}?) (se|ko) (?:rs\\.?|₹|inr)?\\s*([\\d,]+)\\s*(?:rs|rupees|rupay|rupaye)?\\s*(lene hai|lena hai|lene hain|dene hai|dena hai|dene hain)$",
        RegexOption.IGNORE_CASE
    )
    private val I_OWE = Regex(
        "^(?:note (?:that )?)?i owe ([a-z][a-z ]{1,30}?)\\s*(?:rs\\.?|₹|inr)?\\s*([\\d,]+)(?:\\s*(?:rs|rupees))?(?:\\s+for\\s+(.+))?$",
        RegexOption.IGNORE_CASE
    )

    /** "note that Rahul owes me 500 for dinner" / "I owe Riya 200" / "Rahul se 500 lene hai" → (person, paise, direction, reason). */
    fun parseNote(said: String): Triple<String, Long, String>? {
        val s = said.trim().trimEnd('.', '!').replace(Regex("^(oyee? pa|hey pa|pa)[, ]+", RegexOption.IGNORE_CASE), "")
        I_OWE.matchEntire(s)?.let { m ->
            val amt = m.groupValues[2].replace(",", "").toLongOrNull() ?: return null
            return Triple(m.groupValues[1].trim().replaceFirstChar { it.uppercase() }, amt * 100, "I_OWE")
        }
        HINDI.matchEntire(s)?.let { h ->
            val amt = h.groupValues[3].replace(",", "").toLongOrNull() ?: return null
            val dir = if (h.groupValues[4].lowercase(Locale.ROOT).startsWith("le")) "OWED_TO_ME" else "I_OWE"
            return Triple(h.groupValues[1].trim().replaceFirstChar { it.uppercase() }, amt * 100, dir)
        }
        val m = NOTE.matchEntire(s) ?: return null
        val amt = m.groupValues[3].replace(",", "").toLongOrNull() ?: return null
        val dir = when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "owes me", "ko mujhe", "se lene hai" -> "OWED_TO_ME"
            else -> "I_OWE"
        }
        return Triple(m.groupValues[1].trim().replaceFirstChar { it.uppercase() }, amt * 100, dir)
    }
}

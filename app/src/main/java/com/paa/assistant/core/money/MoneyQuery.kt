package com.paa.assistant.core.money

import java.util.Calendar
import java.util.Locale

/**
 * "How much did I spend on food this month", "biggest expense in October", "did Rahul pay me back", "kitna kharcha
 * hua is hafte" — answered on the phone from local data only (P2-T8). Deterministic; no model, no cloud.
 */
object MoneyQuery {
    private val MONEY_WORDS = Regex(
        "\\b(spend|spent|spending|expense|expenses|kharch\\w*|paisa|paise|money|biggest|largest|owe|owes|owed|udhaar|udhar|" +
            "paid me|pay me|lene hai|dene hai|balance|kitna (gaya|laga|hua)|total)\\b",
        RegexOption.IGNORE_CASE
    )

    /** A question about the owner's money — not a command or a reminder that mentions money ("paise bhejne hai yaad dilana"). */
    fun isMoneyQuestion(s: String) = MONEY_WORDS.containsMatchIn(s) &&
        !Regex("^(send|pay|transfer|bhej)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s.trim()) &&
        !Regex("\\b(remind|reminder|yaad|alarm|baje|tomorrow|kal tak)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** [from, to) for "today", "this week/hafte", "this month/mahine", "last month", a month name; default this month. */
    fun range(s: String, now: Long): Pair<Long, Long> {
        val q = s.lowercase(Locale.ROOT)
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        fun monthOf(cal: Calendar): Pair<Long, Long> {
            val a = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
            val b = (a.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
            return a.timeInMillis to b.timeInMillis
        }
        return when {
            Regex("\\b(today|aaj)\\b").containsMatchIn(q) -> c.timeInMillis to c.timeInMillis + 86_400_000L
            Regex("\\b(yesterday|kal)\\b").containsMatchIn(q) -> c.timeInMillis - 86_400_000L to c.timeInMillis
            Regex("\\b(this week|is hafte|is week)\\b").containsMatchIn(q) ->
                (c.clone() as Calendar).apply { set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); if (timeInMillis > now) add(Calendar.WEEK_OF_YEAR, -1) }.timeInMillis to now + 1
            Regex("\\b(last month|pichle mahine)\\b").containsMatchIn(q) -> monthOf((c.clone() as Calendar).apply { add(Calendar.MONTH, -1) })
            else -> {
                val m = MONTHS.indexOfFirst { Regex("\\b$it\\w*\\b").containsMatchIn(q) }
                if (m >= 0) monthOf((c.clone() as Calendar).apply {
                    if (m > get(Calendar.MONTH)) add(Calendar.YEAR, -1)
                    set(Calendar.MONTH, m); set(Calendar.DAY_OF_MONTH, 1)
                }) else monthOf(c)
            }
        }
    }

    private fun rs(p: Long) = "₹" + String.format(Locale.ENGLISH, "%,d", p / 100) + if (p % 100 != 0L) ".%02d".format(p % 100) else ""

    /** Answer, or null when it isn't a question these functions can answer. */
    fun answer(question: String, txns: List<Txn>, categories: Map<String, String>, ious: List<Iou>, now: Long): String? {
        val q = question.lowercase(Locale.ROOT)
        val (from, to) = range(q, now)
        val inRange = txns.filter { it.at in from until to }
        val debits = inRange.filter { it.direction == "DEBIT" }

        // Owed / did X pay me back
        if (Regex("\\b(owe|owes|owed|udhaar|udhar|pay me back|paid me|lene hai|dene hai)\\b").containsMatchIn(q)) {
            val open = ious.filter { it.settledAt == null }
            val person = open.map { it.person }.firstOrNull { p -> p.lowercase(Locale.ROOT).split(" ").any { it.length >= 3 && it in q } }
            if (person != null) {
                val mine = open.filter { it.person == person }
                return if (mine.isEmpty()) "$person doesn't owe you anything open." else
                    mine.joinToString("; ") { (if (it.direction == "OWED_TO_ME") "$person owes you " else "You owe $person ") + rs(it.amountPaise) + (it.reason?.let { r -> " ($r)" } ?: "") }
            }
            if (open.isEmpty()) return "Nothing owed either way right now."
            val toMe = open.filter { it.direction == "OWED_TO_ME" }.sumOf { it.amountPaise }
            val iOwe = open.filter { it.direction == "I_OWE" }.sumOf { it.amountPaise }
            return "Others owe you ${rs(toMe)}; you owe ${rs(iOwe)}. " + open.take(5).joinToString(", ") {
                if (it.direction == "OWED_TO_ME") "${it.person} → you ${rs(it.amountPaise)}" else "you → ${it.person} ${rs(it.amountPaise)}"
            }
        }
        if (Regex("\\b(biggest|largest|sabse bada|highest)\\b").containsMatchIn(q)) {
            val b = debits.maxByOrNull { it.amountPaise } ?: return "No spending found for that period."
            return "Biggest expense: ${rs(b.amountPaise)}" + (b.counterparty?.let { " to $it" } ?: "") + "."
        }
        val cat = Categorizer.CATEGORIES.firstOrNull { it != "Uncategorised" && Regex("\\b${it.lowercase(Locale.ROOT)}\\b").containsMatchIn(q) }
            ?: mapOf("khana" to "Food", "khane" to "Food", "food" to "Food", "travel" to "Travel", "kiraya" to "Rent", "shopping" to "Shopping")
                .entries.firstOrNull { it.key in q }?.value
        if (Regex("\\b(spend|spent|spending|expense|expenses|kharch\\w*|total|kitna)\\b").containsMatchIn(q)) {
            val pool = if (cat != null) debits.filter { (categories[it.id] ?: "Uncategorised") == cat } else debits
            val total = pool.sumOf { it.amountPaise }
            val label = cat?.let { "on $it " } ?: ""
            return "You spent ${rs(total)} ${label}in that period (${pool.size} payments)." +
                if (cat == null && debits.isNotEmpty()) " Top: " + debits.groupBy { categories[it.id] ?: "Uncategorised" }
                    .mapValues { e -> e.value.sumOf { it.amountPaise } }.entries.sortedByDescending { it.value }.take(3)
                    .joinToString(", ") { "${it.key} ${rs(it.value)}" } else ""
        }
        return null
    }
}

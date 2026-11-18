package com.paa.assistant.services

import java.util.Locale

/**
 * Deterministic checks on what the model proposes, written after a month of real use (docs/build/TASK_PIPELINE_V2.md):
 * advice is not a task, titles never name the owner, and every name/number in a title must come from the chat.
 */
object ChatTaskChecks {

    /** Advice, wishes and care ("take care of your health", "so jao", "khana kha lena") — not something to do. */
    private val ADVICE = Regex(
        "\\b(take care|dhyan rakh\\w*|khayal rakh\\w*|so ja(o|na)?|sojao|so jaa|neend|rest kar\\w*|aaram kar\\w*|" +
            "khana kha\\w*|kha lena|kha lo|paani pi\\w*|drink water|stay safe|be safe|careful rehna|enjoy\\w*|" +
            "all the best|best of luck|good night|good morning|gn|gm|happy \\w+|congrat\\w*|get well|jaldi theek|" +
            "padh lo|padhai kar\\w*|mehnat kar\\w*|tension mat\\w*|chinta mat\\w*)\\b",
        RegexOption.IGNORE_CASE
    )
    private val ADVICE_TITLE = Regex(
        "\\b(health|care|sleep|rest|enjoy|eat|food|water|safe|careful|wish|study|awareness|tension|worry)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isAdvice(text: String, title: String): Boolean = ADVICE.containsMatchIn(text) && ADVICE_TITLE.containsMatchIn(title)

    /**
     * Titles must not name the owner: "Send ppt to Shreyash" for "Shreyash, ppt bhej do" is reversed.
     * "to/for <owner>" becomes "to <requester>" when the requester is known; any other owner name → null (unsure).
     */
    fun fixOwnerName(title: String, owners: List<String>, requester: String?): String? {
        val names = owners.filter { it.isNotBlank() }.joinToString("|") { Regex.escape(it) }
        if (names.isEmpty()) return title
        val any = Regex("\\b($names)('s)?\\b", RegexOption.IGNORE_CASE)
        if (!any.containsMatchIn(title)) return title
        val who = requester?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.length >= 2 && !any.matches(it) }
        val toOwner = Regex("\\b(to|for|with)\\s+($names)\\b", RegexOption.IGNORE_CASE)
        if (who != null && toOwner.containsMatchIn(title)) {
            val fixed = toOwner.replace(title) { m -> "${m.groupValues[1]} $who" }
            return fixed.takeIf { !any.containsMatchIn(it) }
        }
        return null
    }

    /** Words a title may use although they are capitalised and not in the chat. */
    private val COMMON = setOf(
        "send", "call", "submit", "pay", "bring", "buy", "check", "reply", "share", "book", "confirm", "register",
        "apply", "attend", "meet", "print", "collect", "give", "finish", "complete", "prepare", "make", "upload",
        "remind", "ask", "tell", "return", "pick", "get", "join", "add", "edit", "read", "write", "fill", "transfer",
        "the", "and", "for", "with", "from", "about", "before", "after", "today", "tomorrow", "tonight", "morning",
        "evening", "night", "noon", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
        "november", "december", "ppt", "pdf", "doc", "notes", "assignment", "report", "lab", "record", "file",
        "project", "form", "fees", "money", "rupees", "internship", "hackathon", "exam", "class", "test"
    )

    /**
     * Every capitalised word (after the first) and every number in the title must appear in what was said — catches
     * titles copied from elsewhere ("SoHo Inn booking" on an unrelated chat) and invented details. Small typos
     * (one letter) are allowed.
     */
    fun grounded(title: String, said: String): Boolean {
        val heard = Regex("[\\p{L}\\p{N}]+").findAll(said.lowercase(Locale.ROOT)).map { it.value }.toSet()
        val words = Regex("[\\p{L}\\p{N}]+").findAll(title).map { it.value }.toList()
        return words.drop(1).all { w ->
            val lw = w.lowercase(Locale.ROOT)
            val needsCheck = w.any(Char::isDigit) || (w[0].isUpperCase() && lw !in COMMON && w.length >= 3)
            !needsCheck || lw in heard || (lw.length >= 5 && heard.any { close(it, lw) }) ||
                (w.all(Char::isDigit) && said.filter(Char::isDigit).contains(w))
        }
    }

    private fun close(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    /** OTPs, PINs and card/account numbers never go into tasks or the log. */
    private val CODE_NEAR_WORD = Regex(
        "(?i)\\b(otp|one[- ]time password|verification code|code|pin|password|passcode|cvv)\\b([^\\d\\n]{0,25})(\\d{4,8})\\b"
    )
    private val CODE_BEFORE_WORD = Regex("(?i)\\b(\\d{4,8})\\b(\\s*(?:is your|is the)?\\s*(?:otp|verification code|code))")
    private val LONG_NUMBER = Regex("\\b\\d(?:[ -]?\\d){11,18}\\b")

    fun mask(text: String): String = text
        .replace(CODE_NEAR_WORD) { m -> m.groupValues[1] + m.groupValues[2] + "•".repeat(m.groupValues[3].length) }
        .replace(CODE_BEFORE_WORD) { m -> "•".repeat(m.groupValues[1].length) + m.groupValues[2] }
        .replace(LONG_NUMBER) { m -> "•••• " + m.value.filter(Char::isDigit).takeLast(4) }
}

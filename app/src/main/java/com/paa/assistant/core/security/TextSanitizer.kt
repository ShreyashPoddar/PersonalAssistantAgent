package com.paa.assistant.core.security

import java.text.Normalizer

data class Sanitized(val text: String, val flags: Set<String>)

/**
 * Cleans untrusted text (notifications, screens, SMS, email) before any rule or model sees it (P1-T10): hidden
 * characters out, NFKC, whitespace collapsed, length capped, instruction-like text flagged. Flagged text is still
 * read for tasks (it's data); the Guard already keeps chat-triggered actions from going outward.
 */
object TextSanitizer {
    private val HIDDEN = Regex("[\\u200B-\\u200F\\u2060-\\u2064\\uFEFF\\u00AD\\u202A-\\u202E\\u2066-\\u2069]")
    private val INSTRUCTION = listOf(
        Regex("ignore (all|previous|above|prior) (instructions|rules|messages)", RegexOption.IGNORE_CASE),
        Regex("\\b(system|assistant)\\s*:", RegexOption.IGNORE_CASE),
        Regex("\\bpaa\\s*[,:]", RegexOption.IGNORE_CASE),
        Regex("you are now", RegexOption.IGNORE_CASE),
        Regex("new instructions", RegexOption.IGNORE_CASE),
        Regex("disregard (the|your|all) ", RegexOption.IGNORE_CASE)
    )

    fun clean(raw: String, maxLen: Int = 2000): Sanitized {
        val flags = mutableSetOf<String>()
        var s = raw
        if (HIDDEN.containsMatchIn(s)) { s = HIDDEN.replace(s, ""); flags += "hidden_chars" }
        s = Normalizer.normalize(s, Normalizer.Form.NFKC)
        // Collapse spaces/tabs but keep line breaks (bursts are "Name: text" lines)
        s = s.lines().joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.trim()
        if (s.length > maxLen) { s = s.take(maxLen); flags += "truncated" }
        if (INSTRUCTION.any { it.containsMatchIn(s) }) flags += "instruction_like"
        return Sanitized(s, flags)
    }
}

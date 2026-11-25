package com.paa.assistant.core.skills

import android.net.Uri
import java.util.Locale

/**
 * "cheapest bus from Chennai to Hosur on Friday, morning, AC" → a travel search (P3-T5, read-only). Parsing is pure and
 * tested; the fare lookup is a web question (route + date only — no personal data), and booking stays with the owner.
 */
data class TravelQuery(val modes: List<String>, val from: String, val to: String, val whenPhrase: String?, val prefs: List<String>) {

    /** Question for the web-search model. */
    fun webQuestion(dateText: String?): String =
        "List the 3 best ${modes.joinToString("/")} options from $from to $to" + (dateText?.let { " on $it" } ?: "") +
            (if (prefs.isNotEmpty()) " (preferring ${prefs.joinToString(", ")})" else "") +
            ". For each: operator/train/airline, departure and arrival time, price in ₹, and where to book. Be brief."

    /** A search page the owner can book from (Google shows bus/train/flight results with booking links). */
    fun searchUrl(dateText: String?): String =
        "https://www.google.com/search?q=" + Uri.encode("${modes.first()} $from to $to" + (dateText?.let { " $it" } ?: ""))

    companion object {
        private val MODE = mapOf("bus" to "bus", "buses" to "bus", "train" to "train", "trains" to "train",
            "flight" to "flight", "flights" to "flight", "plane" to "flight")
        private val ROUTE = Regex(
            "\\b(bus|buses|train|trains|flight|flights|plane)(?:\\s+or\\s+(bus|train|flight))?\\b.*?\\bfrom\\s+([a-z][a-z ]{1,25}?)\\s+to\\s+([a-z][a-z ]{1,25}?)" +
                "(?:\\s+(?:on|for|this|next|kal|tomorrow|parso|aaj|today)\\b(.*))?$",
            RegexOption.IGNORE_CASE
        )
        private val HINDI_ROUTE = Regex(
            "^([a-z][a-z ]{1,25}?)\\s+se\\s+([a-z][a-z ]{1,25}?)\\s+(?:ki|ka|ke)?\\s*(bus|train|flight)\\b(.*)$", RegexOption.IGNORE_CASE
        )
        private val PREFS = listOf("cheapest", "morning", "evening", "night", "ac", "non ac", "sleeper", "fastest", "direct")

        fun parse(said: String): TravelQuery? {
            val s = said.trim().trimEnd('.', '?', '!').replace(Regex("^(oyee? pa|hey pa|pa)[, ]+", RegexOption.IGNORE_CASE), "")
            val lower = s.lowercase(Locale.ROOT)
            val prefs = PREFS.filter { Regex("\\b$it\\b").containsMatchIn(lower) }
            ROUTE.find(s)?.let { m ->
                val modes = listOfNotNull(MODE[m.groupValues[1].lowercase()], m.groupValues[2].takeIf { it.isNotEmpty() }?.lowercase()).distinct()
                val from = clean(m.groupValues[3]); val to = clean(m.groupValues[4])
                val whenText = Regex("\\b(today|tomorrow|tonight|aaj|kal|parso|this \\w+|next \\w+|on [\\w ]+|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\\d{1,2}(st|nd|rd|th)? \\w{3,9})\\b", RegexOption.IGNORE_CASE)
                    .find(s.substringAfter(m.groupValues[4]))?.value?.removePrefix("on ")?.trim()
                return TravelQuery(modes, from, to, whenText, prefs)
            }
            HINDI_ROUTE.find(s)?.let { m ->
                val whenText = Regex("\\b(aaj|kal|parso|today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", RegexOption.IGNORE_CASE)
                    .find(m.groupValues[4])?.value
                return TravelQuery(listOf(m.groupValues[3].lowercase()), clean(m.groupValues[1]), clean(m.groupValues[2]), whenText, prefs)
            }
            return null
        }

        private fun clean(place: String) = place.trim().split(Regex("\\s+"))
            .filterNot { it.lowercase() in setOf("on", "for", "this", "next", "kal", "the", "cheapest", "morning", "evening", "ac") }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}

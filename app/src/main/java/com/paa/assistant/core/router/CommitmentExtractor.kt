package com.paa.assistant.core.router

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One task found in a chat message. */
data class ExtractedTask(
    val title: String,
    val timestamp: Long?,
    val isRegistration: Boolean,
    val priority: Int = 1
)

/**
 * Rule-based (no AI) extraction of real tasks from a chat message. Used when the on-device
 * model isn't installed, and as a sanity check. Handles several tasks in one message:
 *
 *   "I'll call Rahul by 140 and submit the report at 315"
 *     → "Call Rahul" (1:40), "Submit the report" (3:15)
 *
 * Only commitments (outgoing: "I'll…", "I have to…", "karunga") or requests
 * (incoming: "can you…", "please…", "kar dena", your name) count. Past tense, negations
 * and questions are ignored.
 */
@Singleton
class CommitmentExtractor @Inject constructor(
    private val parser: LocalTaskParser
) {
    private val clauseSplit = Regex("(?<=[.!?;\\n])\\s+|,\\s+|\\s+(?:and then|and also|and|then|also|plus|aur|phir|fir)\\s+")

    private val actionVerb = Regex(
        "\\b(call|ring|submit|register|pay|send|finish|complete|do|meet|attend|order|buy|get|apply|book|email|mail|reply|" +
            "message|text|study|prepare|revise|review|fill|upload|join|pick up|drop|return|clean|cook|fix|write|read|practice|" +
            "go to|visit|check|collect|deposit|recharge|renew|print|schedule|share|bring|take|start|learn|watch|contact|" +
            "ping|wish|transfer|cancel|confirm|update|install|charge|wash|iron|pack)\\b"
    )

    private val outgoingCommitment = Regex(
        "\\b(i'?ll|i will|i'?m going to|i am going to|gonna|i have to|i've to|i need to|i must|i should|i gotta|gotta|" +
            "have to|need to|will|let me|remind me|i'?d better|karunga|karungi|karna hai|karni hai|karne hai|kar dunga|kar dungi)\\b"
    )
    private val incomingRequest = Regex(
        // Must be addressed to the user — a bare imperative ("Register now", "Get 20% off") is an ad, not a request
        "\\b(can you|could you|will you|would you|can u|could u|will u|you have to|you need to|you should|you must|" +
            "u have to|u need to|u should|don'?t forget|remember to|make sure you|tu|tum|aap|kar dena|kar do|bhej dena|" +
            "bhej do|le aana|la dena|dekh lena)\\b"
    )
    private val deadlineAnnouncement = Regex("\\b(deadline|due|submission|submit by|last date|closes|registrations? (?:close|end))\\b")

    private val deadlineOnly = Regex("\\b(last date|deadline|due|today|tonight|tomorrow|eod|end of day|by \\S+|before \\S+)\\b")

    private val negation =Regex("\\b(won'?t|will not|can'?t|cannot|couldn'?t|don'?t need|no need|not going to|nahi|nhi|mat)\\b")
    private val pastOrDone = Regex(
        "\\b(did|done|already|called|submitted|finished|completed|paid|sent|registered|ordered|booked|applied|" +
            "ho gaya|ho gya|kar diya|kar liya|kar li|kar di)\\b"
    )

    private val trailingFillers = Regex("(?:\\s+(?:bro|bhai|yaar|yar|ok|okay|pls|please|na|re|dude|man|then|also|now|asap|tho|though|ya|haan|by|at|before|till|until|around|on|for|to))+$")
    private val leadingJunk = Regex("^(?:(?:and|so|ok|okay|then|also|haan|ya|yes|bro|bhai|yaar)\\s+)+")

    /**
     * @param outgoing true for messages the user sent; false for messages received.
     * @param mentionedMe the user was named/@-mentioned (group chats).
     */
    fun extract(text: String, outgoing: Boolean, mentionedMe: Boolean): List<ExtractedTask> {
        val lowerAll = HinglishNormalizer.translateTimes(text.lowercase(Locale.getDefault()))
        val clauses = clauseSplit.split(lowerAll).map { it.trim() }.filter { it.isNotEmpty() }

        // Clauses without a verb continue the previous one ("call rahul and priya"), except
        // deadline-only phrases ("last date hai aaj") — those set the time, not the title
        val merged = mutableListOf<String>()
        val deadlineNotes = mutableListOf<String>()
        for (c in clauses) {
            when {
                actionVerb.containsMatchIn(c) || merged.isEmpty() -> merged += c
                deadlineOnly.containsMatchIn(c) -> deadlineNotes += c
                else -> merged[merged.size - 1] = merged.last() + " and " + c
            }
        }

        val messageIsRequest = !outgoing && (mentionedMe || incomingRequest.containsMatchIn(lowerAll))
        val messageIsCommitment = outgoing && outgoingCommitment.containsMatchIn(lowerAll)
        val results = mutableListOf<ExtractedTask>()

        for (clause in merged) {
            val verb = actionVerb.find(clause) ?: continue
            if (negation.containsMatchIn(clause)) continue
            if (pastOrDone.containsMatchIn(clause) && !outgoingCommitment.containsMatchIn(clause)) continue
            // "should I call him?" — a question, not a commitment
            if (outgoing && clause.endsWith("?") && !Regex("\\b(i'?ll|i will|i have to|i need to)\\b").containsMatchIn(clause)) continue

            val timestamp = parser.extractTimestamp(clause)
            val qualifies = when {
                outgoing -> outgoingCommitment.containsMatchIn(clause) ||
                    (messageIsCommitment && (timestamp != null || clause.startsWith(verb.value)))
                else -> messageIsRequest || incomingRequest.containsMatchIn(clause)
            }
            if (!qualifies) continue

            val title = cleanTitle(clause.substring(verb.range.first), text) ?: continue
            results += ExtractedTask(
                title = title,
                timestamp = timestamp,
                isRegistration = Regex("\\b(register|registration|hackathon)\\b").containsMatchIn(clause),
                priority = if (Regex("\\b(urgent|asap|important|jaldi)\\b").containsMatchIn(clause)) 2 else 1
            )
        }

        // One time mentioned for the whole message ("by 140 I'll call rahul and send notes") applies to all
        val sharedTime = parser.extractTimestamp(lowerAll)
            ?: deadlineNotes.firstNotNullOfOrNull { parser.extractTimestamp(it) }
        return results
            .map { if (it.timestamp == null && sharedTime != null) it.copy(timestamp = sharedTime) else it }
            .distinctBy { it.title.lowercase(Locale.getDefault()) }
    }

    private fun cleanTitle(raw: String, original: String): String? {
        var t = parser.stripTimeWords(raw)
            .replace(Regex("[?!.,;:]+"), " ")
            .replace(Regex("\\b(i'?ll|i will|i have to|i need to|have to|need to|gonna|karunga|karungi|karna hai|karni hai)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        t = leadingJunk.replace(t, "")
        t = trailingFillers.replace(t, "").trim()
        // A bare "do" says nothing about the work — not worth a task
        if (t.length < 2 || t == "do" || t == "do it") return null
        if (t.length > 60) t = t.take(60).substringBeforeLast(' ')
        return restoreCase(t, original).replaceFirstChar { it.uppercaseChar() }
    }

    /** Takes each word's casing from the original message ("rahul" → "Rahul", "dbms" → "DBMS"). */
    private fun restoreCase(title: String, original: String): String {
        val originals = original.split(Regex("\\s+")).map { it.trim(',', '.', '!', '?', ';', ':') }
            .associateBy { it.lowercase(Locale.getDefault()) }
        return title.split(" ").joinToString(" ") { originals[it] ?: it }
    }
}

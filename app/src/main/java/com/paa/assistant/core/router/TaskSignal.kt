package com.paa.assistant.core.router

import com.paa.assistant.core.profile.UserProfile

/**
 * Cheap check before the slow on-device model (~1 min per run on this phone): does a message
 * carry ANY sign of a task — a time, a request/promise verb, a link, the owner's name, a done-word?
 * Pure chatter ("haha", "kya scene", "Abe saale") skips the model. Errs on the side of "yes".
 */
object TaskSignal {
    private val time = Regex(
        "\\b(kal|aaj|parso|parson|tomorrow|today|tonight|morning|evening|subah|shaam|raat|baje|bje|pm|am|" +
            "deadline|last date|eod|by \\d|till|tak|before|pehle|monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
            "week|hafte|mahine|month|minutes?|mins?|hours?|ghante)\\b|\\b\\d{1,2}(:\\d{2})?\\s*(am|pm|baje)?\\b|\\b\\d{3,4}\\b"
    )
    private val action = Regex(
        "\\b(send|bhej\\w*|bhj\\w*|call|ring|pay|bhar\\w*|de\\s?dena|dedo|submit|bring|la\\s?dena|lana|le\\s?aana|" +
            "register|apply|book|order|buy|kharid\\w*|complete|finish|check|dekh\\s?lena|bata\\s?dena|batana|" +
            "remind|yaad|kar\\s?dena|kardo|kar\\s?do|karna\\s?hai|karni\\s?hai|karunga|karungi|dunga|dungi|krna|krdo|" +
            "meet|milte|milna|aa\\s?jana|aaja|reach|pahunch\\w*|confirm|share|upload|print|fill|ready|prepare|" +
            "please|pls|plz|zaroor|jaldi|urgent|asap|need|chahiye|must|have to|will|i'?ll|gonna)\\b"
    )
    private val done = Regex("\\b(done|ho\\s?gaya|ho\\s?gya|kar\\s?diya|kr\\s?diya|bhej\\s?diya|de\\s?diya|submitted|sent|paid|finished|hn|haan|ha|ok|okay|yes)\\b")
    private val link = Regex("https?://", RegexOption.IGNORE_CASE)

    /** @param hasOpenTasks the chat has open tasks — then a short "done"/"hn" reply counts too. */
    fun hasSignal(text: String, hasOpenTasks: Boolean = false): Boolean {
        val t = text.lowercase()
        return time.containsMatchIn(t) || action.containsMatchIn(t) || link.containsMatchIn(t) ||
            UserProfile.isMentioned(t) || (t.contains('?') && t.length > 12) ||
            (hasOpenTasks && done.containsMatchIn(t))
    }
}

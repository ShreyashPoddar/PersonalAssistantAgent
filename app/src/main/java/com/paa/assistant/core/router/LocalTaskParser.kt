package com.paa.assistant.core.router

import com.paa.assistant.core.profile.UserProfile
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result from the local NLP parser.
 *
 * @param intent     The classified intent type.
 * @param taskTitle  Extracted task or item title (null if not applicable).
 * @param timestamp  Resolved absolute UNIX timestamp in ms (null if not specified).
 * @param priority   Extracted priority level (0-3).
 * @param tags       Auto-detected category tags.
 * @param recurrenceRule Optional iCal RRULE string.
 * @param confidence Confidence score 0.0–1.0. If < 0.85, escalate to Gemini.
 * @param rawInput   Original user utterance, forwarded if escalating.
 */
data class LocalParseResult(
    val intent: LocalIntent,
    val taskTitle: String? = null,
    val timestamp: Long? = null,
    val priority: Int = 1,
    val tags: List<String> = emptyList(),
    val recurrenceRule: String? = null,
    val confidence: Float = 1.0f,
    val rawInput: String = "",
    /** Location-triggered reminder: "home", "office", … (resolved via SavedPlaces). */
    val locationName: String? = null,
    /** 1 = on arrival, 2 = on leaving. */
    val locationTransition: Int = 1,
    /** SEND_MESSAGE: who to message (spoken name); the text is in taskTitle. */
    val recipient: String? = null
)

enum class LocalIntent {
    CREATE_TASK,
    LIST_TASKS,
    COMPLETE_TASK,
    DELETE_TASK,
    QUERY_AGENDA,
    SET_TIMER,
    PLAN_TASKS,  // "how can I finish everything tonight?" → answered on-device
    SAVE_PLACE,  // "save this location as home"
    RESCHEDULE_TASK,  // "change that reminder to 10 pm", "no no, make it 12" → moves an existing task
    SEND_MESSAGE,  // "Riya ko bol do ki main late hu", "message Riya that I'm late" → WhatsApp after confirmation
    UNKNOWN  // triggers cloud escalation
}

/**
 * Deterministic, regex-based Natural Language Understanding engine.
 * Runs in <10ms, fully offline, zero API calls.
 *
 * Handles all routine scheduling and task management commands regardless of sentence structure:
 * - "at 11:02 p.m. remind me that I have to do an assignment of mm it"
 * - "remind me to call Mom tomorrow at 5 PM"
 * - "tomorrow at 9 am add task submit report"
 * - "in 15 minutes remind me to turn off stove"
 * - "set an alarm for 7:30 am"
 * - "mark grocery shopping as done"
 */
@Singleton
class LocalTaskParser @Inject constructor() {

    // "4 pm" / "4:30pm" / "at 4 pm" (groups 1-3), or "at 5" / "by 11:30" / "@ 9" (groups 4-5)
    private val timeRegex = Regex(
        "\\b(?:(?:at|by)\\s+|@\\s*)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b|(?:\\b(?:at|by)\\s+|@\\s*)(\\d{1,2})(?::(\\d{2}))?\\b"
    )

    // "can you", "can u", "could you", "pls", "hey paa", ... (any combination, repeated)
    private val politePrefix =
        "(?:(?:hey\\s+paa|ok\\s+paa|paa|can|could|would|will)\\s+(?:you|u)?\\s*|(?:please|pls|plz|kindly)\\s+|,\\s*)*"

    // "when I reach home", "when I get to the office", "when I leave college"
    private val locationRegex = Regex(
        "\\bwhen (?:i|we) (reach|get to|get|arrive at|arrive|am at|come|go to|leave|exit|head out of)\\s+(?:the |my |to )?(home|office|college|hostel|pg|gym|work|[a-z]+(?: [a-z]+)?)\\b"
    )
    private val shortlyRegex = Regex("\\b(shortly|in a bit|in a while|in some time|thodi der( mein)?)\\b")

    private fun matchesSavePlace(s: String): Boolean =
        Regex("\\b(save|set|mark|remember)\\b.*\\b(location|place|here|this)\\b.*\\bas (?:my )?[a-z]+").containsMatchIn(s) ||
            Regex("\\b(this is|i'?m at) my (home|office|college|hostel|pg|gym)\\b").containsMatchIn(s)

    private fun parseSavePlace(raw: String, norm: String): LocalParseResult {
        val name = Regex("\\bas (?:my )?([a-z]+)").find(norm)?.groupValues?.get(1)
            ?: Regex("\\bmy (home|office|college|hostel|pg|gym)\\b").find(norm)?.groupValues?.get(1)
        return LocalParseResult(LocalIntent.SAVE_PLACE, locationName = name, confidence = if (name != null) 0.95f else 0.5f, rawInput = raw)
    }

    private fun matchesPlan(s: String): Boolean =
        Regex("\\b(how|when) (can|do|should|will) i (finish|complete|do|manage|get through|fit)\\b").containsMatchIn(s) ||
            Regex("\\bplan (my|out my) (day|evening|night|tasks|tonight|today|tomorrow)\\b").containsMatchIn(s) ||
            Regex("\\b(make|give me|suggest) (a |me a )?(plan|schedule|timetable)\\b").containsMatchIn(s)

    /** "by tonight" / "today" → end of today; "tomorrow" → end of tomorrow; else null (all tasks). */
    private fun extractDayCutoff(s: String): Long? {
        val cal = Calendar.getInstance()
        when {
            s.contains("tomorrow") -> cal.add(Calendar.DAY_OF_YEAR, 1)
            s.contains("tonight") || s.contains("today") || s.contains("this evening") || s.contains("eod") -> Unit
            else -> return null
        }
        cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59)
        return cal.timeInMillis
    }

    // "message/text/whatsapp Riya (that) I'm late", "tell Riya that …", "send a message to Riya saying …"
    private val sendEn = Regex(
        "^(?:please |pls |can you |could you )?(?:send (?:a )?(?:message|msg|text|whatsapp) to|message|msg|text|whatsapp|tell) " +
            "([a-z][a-z.]*(?: [a-z][a-z.]*)?) (?:that |saying |to say |:\\s*)(.+)$", RegexOption.IGNORE_CASE
    )
    // "Riya ko bol do (ki) main late hu", "Riya ko message karo ki …", "Riya ko bata do …"
    private val sendHi = Regex(
        "^([a-z][a-z.]*(?: [a-z][a-z.]*)?) ko (?:bol|bata|keh|kah|message kar|msg kar|whatsapp kar|text kar)(?: do| de| dena| karo|o)? (?:ki |ke )?(.+)$",
        RegexOption.IGNORE_CASE
    )

    /** A message to send for the owner, or null. Uses the raw words: the text is sent as the owner said it. */
    fun parseSendMessage(raw: String): LocalParseResult? {
        val t = raw.trim().trimEnd('.', '!')
        val m = sendHi.find(t) ?: sendEn.find(t) ?: return null
        val who = m.groupValues[1].trim()
        val text = m.groupValues[2].trim()
        if (text.length < 2 || who.lowercase() in setOf("me", "mujhe", "myself", "paa")) return null
        return LocalParseResult(LocalIntent.SEND_MESSAGE, taskTitle = text.replaceFirstChar { it.uppercase() }, recipient = who,
            confidence = 0.95f, rawInput = raw)
    }

    fun parse(input: String): LocalParseResult {
        val trimmed = input.trim()
        parseSendMessage(trimmed)?.let { return it }
        val normalized = normalizeInput(trimmed)

        return when {
            matchesSavePlace(normalized) -> parseSavePlace(trimmed, normalized)
            matchesPlan(normalized)     -> LocalParseResult(LocalIntent.PLAN_TASKS, timestamp = extractDayCutoff(normalized), confidence = 0.95f, rawInput = trimmed)
            matchesReschedule(normalized) -> parseReschedule(trimmed, normalized)
            matchesCreate(normalized)   -> parseCreate(trimmed, normalized)
            matchesTimer(normalized)    -> parseTimer(trimmed, normalized)
            matchesComplete(normalized) -> parseComplete(trimmed, normalized)
            matchesDelete(normalized)   -> parseDelete(trimmed, normalized)
            matchesList(normalized)     -> LocalParseResult(LocalIntent.LIST_TASKS, timestamp = extractDayCutoff(normalized), confidence = 1.0f, rawInput = trimmed)
            matchesAgenda(normalized)   -> LocalParseResult(LocalIntent.QUERY_AGENDA, timestamp = extractDayCutoff(normalized), confidence = 1.0f, rawInput = trimmed)
            else                        -> LocalParseResult(LocalIntent.UNKNOWN, confidence = 0.0f, rawInput = trimmed)
        }
    }

    private fun normalizeInput(s: String): String {
        return s.lowercase(Locale.getDefault())
            .replace("p.m.", "pm")
            .replace("a.m.", "am")
            .replace("p. m.", "pm")
            .replace("a. m.", "am")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let(HinglishNormalizer::toEnglish)
    }

    // ── INTENT MATCHERS ─────────────────────────────────────────────────────

    private val rescheduleVerb = Regex("\\b(change|move|shift|reschedule|postpone|prepone|push|update|make it|set it|do it|keep it)\\b")
    private val correction = Regex("^(no+|nahi|nope|actually|wait|sorry|instead)\\b|\\binstead\\b|\\b(for|of) (that|it|this)\\b")

    /** A new time for an existing task — needs a time, plus a change verb or a correction ("no no …"). */
    /** "move it to 10" → "move it at 10": a bare number after "to" is a time when rescheduling. */
    private fun rescheduleTime(s: String): Long? =
        extractTimestamp(s) ?: extractTimestamp(s.replace(Regex("\\b(?:to|for|till) (\\d{1,2}(?::\\d{2})?)\\b"), "at $1"))

    private fun matchesReschedule(s: String): Boolean =
        rescheduleTime(s) != null && (rescheduleVerb.containsMatchIn(s) || correction.containsMatchIn(s)) &&
            !Regex("\\b(new task|another|also remind|add)\\b").containsMatchIn(s)

    private fun parseReschedule(raw: String, norm: String): LocalParseResult {
        // "change the time of DBMS assignment to 10" → hint "dbms assignment"; "that/it/this" → last task
        val hint = Regex("\\b(?:change|move|shift|reschedule|postpone|prepone|push|update)\\s+(?:the\\s+)?(?:time\\s+|timing\\s+|reminder\\s+)?(?:of\\s+|for\\s+)?(?:the\\s+)?(.+?)\\s+(?:to|at|for|till)\\b")
            .find(norm)?.groupValues?.get(1)?.trim()
            ?.replace(Regex("\\s+(task|reminder|wala|vala)$"), "")
            ?.takeUnless { it.matches(Regex("(that|it|this|the|my|that reminder|this reminder|the reminder|reminder|time|timing|that task|this task|its time|it's time)( reminder| task)?")) }
        return LocalParseResult(
            intent = LocalIntent.RESCHEDULE_TASK,
            taskTitle = hint,
            timestamp = rescheduleTime(norm),
            confidence = 0.95f,
            rawInput = raw
        )
    }

    private fun matchesCreate(s: String): Boolean {
        val triggers = listOf(
            "remind me",
            "reminder",
            "add task",
            "create task",
            "schedule",
            "don't forget",
            "dont forget",
            "note to self",
            "add a task",
            "create a task",
            "set a reminder",
            "add a reminder"
        )
        return triggers.any { s.contains(it) } || s.startsWith("note ")
    }

    private fun matchesComplete(s: String): Boolean {
        return (s.contains("mark") && (s.contains("done") || s.contains("complete") || s.contains("finish"))) ||
                s.contains("complete task") ||
                s.contains("done with") ||
                s.contains("finish task") ||
                s.contains("check off") ||
                s.contains("tick off") ||
                s.startsWith("complete ") ||
                s.startsWith("finish ")
    }

    private fun matchesDelete(s: String): Boolean {
        return s.contains("delete task") ||
                s.contains("remove task") ||
                s.contains("cancel task") ||
                s.contains("delete reminder") ||
                s.contains("remove reminder") ||
                s.contains("cancel reminder") ||
                s.startsWith("delete ") ||
                s.startsWith("remove ") ||
                s.startsWith("cancel ") ||
                s == "delete" || s == "remove" || s == "cancel"
    }

    private fun matchesList(s: String): Boolean {
        return (s.contains("show") && (s.contains("task") || s.contains("to-do") || s.contains("todo") || s.contains("list"))) ||
                (s.contains("what") && s.contains("task")) ||
                s.contains("list tasks") ||
                s.contains("my tasks") ||
                s.contains("pending tasks") ||
                s.startsWith("list ") ||
                s == "tasks" || s == "my tasks"
    }

    private fun matchesAgenda(s: String): Boolean {
        return s.contains("agenda") ||
                (s.contains("schedule") && (s.contains("today") || s.contains("tomorrow"))) ||
                (s.contains("what") && (s.contains("today") || s.contains("tomorrow"))) ||
                s.contains("what do i have") ||
                s.contains("what's on my plate") ||
                s.contains("my day")
    }

    private fun matchesTimer(s: String): Boolean {
        return s.contains("set alarm") ||
                s.contains("set an alarm") ||
                s.contains("set a timer") ||
                s.contains("set timer") ||
                s.contains("wake me") ||
                s.contains("alarm for") ||
                s.contains("timer for")
    }

    // ── PARSERS ──────────────────────────────────────────────────────────────

    private fun parseCreate(raw: String, norm: String): LocalParseResult {
        val timestamp = extractTimestamp(norm)
        val recurrence = extractRecurrence(norm)
        val title = extractTaskTitle(raw, norm)
        val priority = extractPriority(norm)
        val tags = extractTags(norm)

        // If we extracted a meaningful title, we're highly confident
        val confidence = if (title != null && title.length >= 2) 0.95f else 0.60f

        val place = locationRegex.find(norm)
        return LocalParseResult(
            intent = LocalIntent.CREATE_TASK,
            taskTitle = title ?: raw,
            timestamp = if (place != null) null else timestamp,
            priority = priority,
            tags = tags,
            recurrenceRule = recurrence,
            confidence = confidence,
            rawInput = raw,
            locationName = place?.groupValues?.get(2)?.trim(),
            locationTransition = if (place?.groupValues?.get(1)?.let { it.startsWith("leav") || it.startsWith("exit") } == true) 2 else 1
        )
    }

    private fun parseComplete(raw: String, norm: String): LocalParseResult {
        val title = extractAfterVerb(norm, listOf("mark", "complete", "done with", "finish", "check off", "tick off"))
            ?.replace(Regex("(as done|as complete|as finished|\\bdone\\b|\\bcomplete\\b|\\bfinished\\b)"), "")
            ?.trim()
            ?.capitalizeFirst()
        return LocalParseResult(
            intent = LocalIntent.COMPLETE_TASK,
            taskTitle = title,
            confidence = if (!title.isNullOrBlank()) 0.90f else 0.55f,
            rawInput = raw
        )
    }

    private fun parseDelete(raw: String, norm: String): LocalParseResult {
        val title = extractAfterVerb(norm, listOf("delete", "remove", "cancel"))
            ?.replace(Regex("^(the task|the reminder|the meeting|task|reminder|meeting)\\s*"), "")
            ?.trim()
            ?.capitalizeFirst()
        return LocalParseResult(
            intent = LocalIntent.DELETE_TASK,
            taskTitle = title,
            confidence = if (!title.isNullOrBlank()) 0.90f else 0.55f,
            rawInput = raw
        )
    }

    private fun parseTimer(raw: String, norm: String): LocalParseResult {
        val timestamp = extractTimestamp(norm)
        return LocalParseResult(
            intent = LocalIntent.SET_TIMER,
            taskTitle = "Alarm",
            timestamp = timestamp,
            confidence = if (timestamp != null) 0.95f else 0.50f,
            rawInput = raw
        )
    }

    private fun extractRecurrence(norm: String): String? {
        return when {
            norm.contains("every day") || norm.contains("daily") -> "RRULE:FREQ=DAILY"
            norm.contains("every week") || norm.contains("weekly") -> "RRULE:FREQ=WEEKLY"
            norm.contains("every month") || norm.contains("monthly") -> "RRULE:FREQ=MONTHLY"
            norm.contains("every monday") -> "RRULE:FREQ=WEEKLY;BYDAY=MO"
            norm.contains("every tuesday") -> "RRULE:FREQ=WEEKLY;BYDAY=TU"
            norm.contains("every wednesday") -> "RRULE:FREQ=WEEKLY;BYDAY=WE"
            norm.contains("every thursday") -> "RRULE:FREQ=WEEKLY;BYDAY=TH"
            norm.contains("every friday") -> "RRULE:FREQ=WEEKLY;BYDAY=FR"
            norm.contains("every saturday") -> "RRULE:FREQ=WEEKLY;BYDAY=SA"
            norm.contains("every sunday") -> "RRULE:FREQ=WEEKLY;BYDAY=SU"
            else -> null
        }
    }

    // ── DATETIME RESOLVER ────────────────────────────────────────────────────

    fun extractTimestamp(norm: String): Long? {
        val now = Calendar.getInstance()

        // 0. "shortly" / "in a bit" → 20 minutes
        if (shortlyRegex.containsMatchIn(norm)) {
            return System.currentTimeMillis() + UserProfile.SHORTLY_MINUTES * 60_000L
        }

        // 1. Relative: "in X minutes/hours"
        val minMatch = Regex("\\bin (\\d+)\\s*(?:minute|minutes|min|mins)\\b").find(norm)
        if (minMatch != null) {
            val mins = minMatch.groupValues[1].toLongOrNull() ?: return null
            return System.currentTimeMillis() + mins * 60_000L
        }

        val hrMatch = Regex("\\bin (\\d+)\\s*(?:hour|hours|hr|hrs)\\b").find(norm)
        if (hrMatch != null) {
            val hrs = hrMatch.groupValues[1].toLongOrNull() ?: return null
            return System.currentTimeMillis() + hrs * 3_600_000L
        }

        // 2. Named relative days
        if (norm.contains("tomorrow")) {
            // "day after tomorrow" (Hinglish "parso") contains "tomorrow" too
            val days = if (norm.contains("day after tomorrow")) 2 else 1
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days) }
            val time = extractTimeOfDay(norm, cal)
            return time ?: cal.apply {
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        if (norm.contains("tonight") || norm.contains("today evening") || norm.contains("this evening")) {
            val cal = Calendar.getInstance()
            val time = extractTimeOfDay(norm, cal)
            return time ?: cal.apply {
                set(Calendar.HOUR_OF_DAY, 20)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        if (norm.contains("this morning") || norm.contains("today morning")) {
            val cal = Calendar.getInstance()
            val time = extractTimeOfDay(norm, cal)
            return time ?: cal.apply {
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        if (norm.contains("this afternoon")) {
            val cal = Calendar.getInstance()
            val time = extractTimeOfDay(norm, cal)
            return time ?: cal.apply {
                set(Calendar.HOUR_OF_DAY, 14)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        // 3. Named day of week: "next monday", "on friday"
        val dayOfWeekRegex = Regex("(?:next |on )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)")
        val dowMatch = dayOfWeekRegex.find(norm)
        if (dowMatch != null) {
            val targetDay = when (dowMatch.groupValues[1]) {
                "monday" -> Calendar.MONDAY
                "tuesday" -> Calendar.TUESDAY
                "wednesday" -> Calendar.WEDNESDAY
                "thursday" -> Calendar.THURSDAY
                "friday" -> Calendar.FRIDAY
                "saturday" -> Calendar.SATURDAY
                "sunday" -> Calendar.SUNDAY
                else -> null
            }
            if (targetDay != null) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, 1) // Move past today
                while (cal.get(Calendar.DAY_OF_WEEK) != targetDay) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                val time = extractTimeOfDay(norm, cal)
                return time ?: cal.apply {
                    set(Calendar.HOUR_OF_DAY, 9)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
        }

        // 4. Time with no day word: "at 11:02 pm", "4 pm", "by 10:30", "by 140", "1145"
        val t = findTimeOfDay(norm) ?: return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val nowMs = System.currentTimeMillis()
        if (t.ambiguous) {
            // No am/pm and no day word: the next upcoming occurrence of that clock time
            // (at 11 AM, "315" = 3:15 PM today; at 10 PM, "140" = 1:40 AM tomorrow)
            val h = t.hour % 12
            for (dayOffset in 0..1) {
                // A whole hour 1–6 ("4 baje", "at 5") is afternoon/evening — nobody means 4 AM.
                // Compact times with minutes keep both ("140" late at night = 1:40 AM).
                val options = if (t.minute == 0 && h in 1..6) listOf(h + 12) else listOf(h, h + 12)
                for (candidate in options) {
                    val c = (cal.clone() as Calendar).apply {
                        add(Calendar.DAY_OF_YEAR, dayOffset)
                        set(Calendar.HOUR_OF_DAY, candidate); set(Calendar.MINUTE, t.minute)
                    }
                    if (c.timeInMillis > nowMs) return c.timeInMillis
                }
            }
        }
        cal.set(Calendar.HOUR_OF_DAY, t.hour)
        cal.set(Calendar.MINUTE, t.minute)
        // Already passed today and no specific day requested → same time tomorrow
        if (cal.timeInMillis <= nowMs && !norm.contains("today")) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** A time of day. [ambiguous] = no am/pm and nothing in the text says morning or evening. */
    private data class TimeOfDay(val hour: Int, val minute: Int, val ambiguous: Boolean)

    // "140", "by 315", "1145", "at 1530": 3–4 digits, last two are minutes
    private val compactTimeRegex = Regex(
        "(?<![\\d:./₹-])(?:\\b(at|by|till|until|before|around|after|@)\\s*)?(?<![\\d])(\\d{1,2})([0-5]\\d)(?![\\d:./%-])" +
            "(?!\\s*(?:rs|rupees|inr|marks|km|kms|kg|m\\b|mins?\\b|minutes|hrs?\\b|pages|words|people|points|th\\b|st\\b|nd\\b|rd\\b|mb|gb|followers|likes|steps|calories|views|members|students|questions))"
    )
    private val moneyBefore = Regex("(?:rs\\.?|₹|inr|room|no\\.?|#|flat|roll)\\s*$")

    /**
     * Finds the first valid time of day: "4 pm", "4:30pm", "at 5", "by 11", "@ 9 am", "by 140", "1145".
     * Invalid values (e.g. "44 pm", "at 25") are ignored rather than guessed.
     */
    private fun findTimeOfDay(norm: String): TimeOfDay? {
        val evening = Regex("\\b(tonight|evening|night|pm)\\b").containsMatchIn(norm)
        val morning = Regex("\\b(morning|am)\\b").containsMatchIn(norm)

        fun resolve(hour: Int, minute: Int): TimeOfDay? {
            if (minute !in 0..59) return null
            return when {
                hour in 13..23 || hour == 0 -> TimeOfDay(hour, minute, false)  // 24-hour clock
                hour !in 1..12 -> null
                evening && !morning -> TimeOfDay(if (hour == 12) 12 else hour + 12, minute, false)
                morning && !evening -> TimeOfDay(if (hour == 12) 0 else hour, minute, false)
                else -> TimeOfDay(hour, minute, true)
            }
        }

        for (match in timeRegex.findAll(norm)) {
            val g = match.groupValues
            if (g[1].isNotEmpty()) {
                var hour = g[1].toIntOrNull() ?: continue
                val minute = g[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
                if (hour !in 1..12 || minute !in 0..59) continue
                if (g[3] == "pm" && hour < 12) hour += 12
                if (g[3] == "am" && hour == 12) hour = 0
                return TimeOfDay(hour, minute, false)
            }
            val hour = g[4].toIntOrNull() ?: continue
            val minute = g[5].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            resolve(hour, minute)?.let { return it }
        }

        for (match in compactTimeRegex.findAll(norm)) {
            val hasPreposition = match.groupValues[1].isNotEmpty()
            if (moneyBefore.containsMatchIn(norm.substring(0, match.range.first))) continue
            val hour = match.groupValues[2].toInt()
            val minute = match.groupValues[3].toInt()
            // A bare 19xx/20xx is far more likely a year than a time
            if (!hasPreposition && match.groupValues[2].length == 2 && (hour == 19 || hour == 20)) continue
            resolve(hour, minute)?.let { return it }
        }
        return null
    }

    /** Sets the time on a day that was named explicitly ("tomorrow", "friday"). */
    private fun extractTimeOfDay(norm: String, cal: Calendar): Long? {
        val t = findTimeOfDay(norm) ?: return null
        // Explicit day but no am/pm: 1–6 → PM ("kal 5 baje" = 5 PM), 7–11 → AM, 12 → noon
        val hour = if (t.ambiguous) (if (t.hour in 1..6) t.hour + 12 else t.hour) else t.hour
        return cal.apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, t.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** Resolves a when-phrase copied from a message ("by 140", "kal 5 baje", "tonight") to a timestamp. */
    fun resolveWhen(phrase: String): Long? = extractTimestamp(normalizeInput(phrase))

    /** Removes time/day expressions from free text (used to clean task titles). */
    fun stripTimeWords(text: String): String = text
        .replace(compactTimeRegex, " ")
        .replace(timeRegex, " ")
        .replace(Regex("\\b(tomorrow|tonight|today|this morning|this afternoon|this evening|day after tomorrow|eod|end of day)\\b"), " ")
        .replace(Regex("\\bin (\\d+)\\s*(?:minute|minutes|min|mins|hour|hours|hr|hrs)\\b"), " ")
        .replace(shortlyRegex, " ")
        .replace(Regex("\\s+"), " ").trim()

    // ── ENTITY EXTRACTORS ────────────────────────────────────────────────────

    /** Maps a lowercase title back to the user's original casing ("mm it" → "MM IT") when it appears verbatim. */
    private fun restoreCase(raw: String, lowerTitle: String): String {
        val cased = raw.replace(Regex("\\s+"), " ").trim()
        val lower = cased.lowercase(Locale.getDefault())
        if (lower.length != cased.length) return lowerTitle
        val idx = lower.indexOf(lowerTitle)
        return if (idx >= 0) cased.substring(idx, idx + lowerTitle.length) else lowerTitle
    }

    private fun extractTaskTitle(raw: String, norm: String): String? {
        // Step 1: Strip out all temporal tokens from norm to isolate pure task text
        var textNoTime = norm
            .replace(Regex("[?.!]+$"), "")
            .replace(locationRegex, "")
            .replace(shortlyRegex, "")
            .replace(compactTimeRegex, "")
            .replace(timeRegex, "")
            // Drop unparseable time-like leftovers such as "44 pm" instead of keeping them in the title
            .replace(Regex("\\b\\d+(?::\\d+)?\\s*(am|pm)\\b"), "")
            .replace(Regex("\\bin (\\d+)\\s*(?:minute|minutes|min|mins|hour|hours|hr|hrs)\\b"), "")
            .replace(Regex("\\b(tomorrow|tonight|today|this morning|today morning|this afternoon|this evening|today evening)\\b"), "")
            .replace(Regex("\\b(?:next |on )?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Step 2: Strip lead-in command prefixes
        val prefixPatterns = listOf(
            Regex("^$politePrefix(?:remind me|set a reminder|add a reminder)\\s+(?:that\\s+i\\s+(?:have|need)\\s+to\\s+|that\\s+|to\\s+|about\\s+|for\\s+)?"),
            Regex("^$politePrefix(?:add task|create task|schedule|don't forget to|dont forget to|don't forget|dont forget|note to self|note)\\s+(?:that\\s+i\\s+(?:have|need)\\s+to\\s+|that\\s+|to\\s+|about\\s+|for\\s+)?"),
            Regex("^(?:reminder to|reminder that i have to|reminder that)\\s+")
        )

        for (regex in prefixPatterns) {
            val match = regex.find(textNoTime)
            if (match != null) {
                textNoTime = textNoTime.substring(match.range.last + 1).trim()
                break
            }
        }

        // Step 3: Strip dangling leading helper words
        textNoTime = textNoTime
            .replace(Regex("^(?:that i have to|that i need to|to|that|about|for)\\s+"), "")
            .trim()

        if (textNoTime.isBlank()) {
            return null
        }

        return restoreCase(raw, textNoTime).capitalizeFirst()
    }

    private fun extractAfterVerb(norm: String, verbs: List<String>): String? {
        for (verb in verbs) {
            val idx = norm.indexOf(verb)
            if (idx >= 0) {
                val after = norm.substring(idx + verb.length).trim()
                return after.ifEmpty { null }
            }
        }
        return null
    }

    private fun extractPriority(norm: String): Int {
        return when {
            norm.contains("urgent") || norm.contains("asap") || norm.contains("emergency") -> 3
            norm.contains("important") || norm.contains("critical") || norm.contains("high priority") -> 2
            norm.contains("low priority") || norm.contains("whenever") || norm.contains("sometime") -> 0
            else -> 1
        }
    }

    private fun extractTags(norm: String): List<String> {
        val tags = mutableListOf<String>()
        if (norm.contains("call") || norm.contains("meeting") || norm.contains("email") || norm.contains("client") || norm.contains("assignment") || norm.contains("homework") || norm.contains("project")) tags.add("#work")
        if (norm.contains("buy") || norm.contains("shop") || norm.contains("grocery") || norm.contains("store")) tags.add("#shopping")
        if (norm.contains("doctor") || norm.contains("dentist") || norm.contains("medicine") || norm.contains("hospital")) tags.add("#health")
        if (norm.contains("pay") || norm.contains("invoice") || norm.contains("bill") || norm.contains("bank") || norm.contains("tax")) tags.add("#finance")
        if (norm.contains("family") || norm.contains("mom") || norm.contains("dad") || norm.contains("wife") || norm.contains("kids")) tags.add("#personal")
        return tags
    }

    private fun String.capitalizeFirst(): String {
        return if (isNotEmpty()) {
            this[0].uppercaseChar() + substring(1)
        } else {
            this
        }
    }
}

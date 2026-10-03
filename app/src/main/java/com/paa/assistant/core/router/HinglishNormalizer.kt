package com.paa.assistant.core.router

/**
 * Rewrites common Hinglish commands into the English forms [LocalTaskParser] understands.
 * Instant and fully offline — no model involved.
 *
 *   "kal 5 baje senior ko call karna yaad dilana"  → "remind me to call tomorrow at 5 senior"
 *   "aaj raat 10 baje tak assignment submit karna hai, remind kar dena" → "remind me to submit tonight at 10 pm assignment"
 *   "assignment ho gaya"                            → "mark assignment as done"
 *   "aaj raat tak kya kaam hai"                      → "what tasks tonight"
 *
 * Input must already be lowercase.
 */
object HinglishNormalizer {

    private val hi = "(?<![a-z])"   // word start (works with ':' and digits nearby)
    private val end = "(?![a-z])"

    // ── Time ────────────────────────────────────────────────────────────────
    private val fractions = listOf(
        Regex("${hi}(?:sadhe|saadhe|saade)\\s+(\\d{1,2})$end") to { m: MatchResult -> "${m.groupValues[1]}:30" },
        Regex("${hi}(?:sava|savva|sawa)\\s+(\\d{1,2})$end") to { m: MatchResult -> "${m.groupValues[1]}:15" },
        Regex("${hi}paune\\s+(\\d{1,2})$end") to { m: MatchResult ->
            val h = m.groupValues[1].toInt(); "${if (h == 1) 12 else h - 1}:45"
        },
        Regex("${hi}dedh$end") to { _: MatchResult -> "1:30" },
        Regex("${hi}(?:dhai|dhaai|arhai)$end") to { _: MatchResult -> "2:30" }
    )

    private val partOfDay = mapOf(
        "subah" to "am", "savere" to "am",
        "dopahar" to "pm", "dopehar" to "pm",
        "shaam" to "pm", "sham" to "pm",
        "raat" to "pm"
    )
    private val pod = partOfDay.keys.joinToString("|")
    private val time = "(\\d{1,2}(?::\\d{2})?)"

    // "shaam 5 baje", "subah ko 7 baje", "5 baje shaam ko"
    private val podBeforeBaje = Regex("$hi($pod)\\s+(?:ko\\s+|mein\\s+|me\\s+)?$time\\s*(?:baje|bje)$end")
    private val bajeBeforePod = Regex("$time\\s*(?:baje|bje)\\s+($pod)(?:\\s+ko|\\s+mein|\\s+me)?$end")
    private val plainBaje = Regex("$time\\s*(?:baje|bje)$end")

    private val relative = listOf(
        Regex("(\\d+)\\s*(?:minute|minutes|min|mins|minat)\\s+(?:mein|me|main|baad)$end") to "in $1 minutes",
        Regex("(\\d+)\\s*(?:ghante|ghanta|ghanto|hour|hours)\\s+(?:mein|me|main|baad)$end") to "in $1 hours",
        Regex("${hi}(?:thodi|thoda)\\s+(?:der|time)\\s*(?:mein|me|main|baad)?$end") to "shortly"
    )

    // ── Days ────────────────────────────────────────────────────────────────
    private val days = listOf(
        "aaj raat" to "tonight", "aaj shaam" to "this evening", "aaj subah" to "this morning",
        "aaj dopahar" to "this afternoon", "kal subah" to "tomorrow morning", "kal shaam" to "tomorrow evening",
        "kal raat" to "tomorrow night", "parso" to "day after tomorrow",
        "kal" to "tomorrow", "aaj" to "today", "abhi" to "now",
        "somvar" to "monday", "mangalvar" to "tuesday", "budhvar" to "wednesday", "guruvar" to "thursday",
        "brihaspativar" to "thursday", "shukravar" to "friday", "shanivar" to "saturday",
        "ravivar" to "sunday", "itvaar" to "sunday", "raviwar" to "sunday"
    ).map { (h, e) -> Regex("$hi$h$end") to e }

    // ── Intents ─────────────────────────────────────────────────────────────
    private val createPhrases = Regex(
        "$hi(?:mujhe\\s+|muje\\s+)?(?:yaad\\s+(?:dila|dilaa)\\s*(?:na|do|dena|iyo|ana|dijiye|dijiyega|de)?|" +
            "remind\\s+kar(?:na|do|dena|iyo|a\\s+dena|\\s+dena|\\s+do)|" +
            "reminder\\s+(?:laga|lagaa|set\\s+kar|bana)\\s*(?:do|dena|de|na)?|" +
            "note\\s+kar\\s+(?:lo|le|do|lena))$end"
    )
    private val completePhrases = Regex("$hi(?:ho\\s+gaya|ho\\s+gayi|ho\\s+gya|kar\\s+liya|kar\\s+li|kar\\s+diya|kar\\s+di|khatam\\s+(?:ho\\s+gaya|kar\\s+diya)|done\\s+hai|pura\\s+ho\\s+gaya)$end")
    private val deletePhrases = Regex("$hi(?:hata\\s+(?:do|de|dena)|delete\\s+kar\\s+(?:do|de|dena)|cancel\\s+kar\\s+(?:do|de|dena)|mita\\s+(?:do|de)|remove\\s+kar\\s+(?:do|de))$end")
    private val planPhrases = Regex("$hi(?:kaise|kab)\\s+(?:sab\\s+)?(?:khatam|finish|complete|pura|poora)\\s*(?:karu|karoon|karun|karoon\\s+ga|kar\\s+paunga|karenge|karna\\s+hai)?$end")
    private val listPhrases = Regex("$hi(?:kya|kaun\\s*se|konse|kaunse|kitne)\\s+(?:kya\\s+)?(?:kaam|task|tasks|work)$end|${hi}kya\\s+kya\\s+(?:karna|hai)$end")

    // "senior ko call karna" → "call senior"; "groceries order karna hai" → "order groceries"
    private val karnaTitle = Regex("^(.*?)\\s*(?:ko\\s+)?([a-z]+)\\s+(?:karna|karni|karne|kar\\s+dena|kar\\s+lena|karo|kar\\s+do)$")

    private val fillers = Regex("$hi(?:mujhe|muje|please|plz|pls|zara|na|yaar|bhai)$end")
    private val trailingHai = Regex("\\s+(?:hai|he|h)$")

    /** Only translates Hinglish times and days ("kal 5 baje" → "tomorrow at 5"); keeps everything else. */
    fun translateTimes(input: String): String {
        var s = input
        for ((re, f) in fractions) s = re.replace(s, f)
        s = podBeforeBaje.replace(s) { m -> "at ${m.groupValues[2]} ${partOfDay.getValue(m.groupValues[1])}" }
        s = bajeBeforePod.replace(s) { m -> "at ${m.groupValues[1]} ${partOfDay.getValue(m.groupValues[2])}" }
        s = plainBaje.replace(s) { m -> "at ${m.groupValues[1]}" }
        for ((re, e) in relative) s = re.replace(s, e)
        for ((re, e) in days) s = re.replace(s, e)
        return s.replace(Regex("${hi}tak$end"), "")
    }

    fun toEnglish(input: String): String {
        var s = translateTimes(input)

        var prefix = ""
        var suffix = ""
        when {
            createPhrases.containsMatchIn(s) -> { s = createPhrases.replace(s, " "); prefix = "remind me to " }
            completePhrases.containsMatchIn(s) -> { s = completePhrases.replace(s, " "); prefix = "mark "; suffix = " as done" }
            deletePhrases.containsMatchIn(s) -> { s = deletePhrases.replace(s, " "); prefix = "delete " }
            planPhrases.containsMatchIn(s) -> { s = planPhrases.replace(s, " how can i finish ") }
            listPhrases.containsMatchIn(s) -> { s = listPhrases.replace(s, " ") ; prefix = "what tasks " }
            // No Hinglish command verb: keep only the time/day translations ("remind me kal 5 baje" still works)
            else -> return s.replace(Regex("\\s+"), " ").trim()
        }

        s = fillers.replace(s, " ")
            .replace(Regex("[,.!?]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        s = trailingHai.replace(s, "")
        karnaTitle.find(s)?.let { m -> s = "${m.groupValues[2]} ${m.groupValues[1]}".trim() }

        return (prefix + s + suffix).replace(Regex("\\s+"), " ").trim()
    }
}

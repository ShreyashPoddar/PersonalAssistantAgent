package com.paa.assistant.core.profile

import android.content.Context

/**
 * Facts about the phone's owner that chat detection relies on. Editable on the 🧠 card
 * (⚙️ About me); defaults below. Kept on-device; never sent to any cloud service.
 */
object UserProfile {
    private const val PREFS = "paa_user_profile"

    /** Names people use for the user in group chats (matched as whole words, case-insensitive). */
    @Volatile var names = listOf("shreyash", "shreyas")
        private set

    /** Graduation batch — internship posts for other batches are ignored. */
    @Volatile var GRADUATION_YEAR = 2028
        private set

    /** Deadline when someone in a group asks the user to do something without giving a time. */
    @Volatile var GROUP_REQUEST_DEFAULT_MINUTES = 60
        private set

    /** Alarms may ring only from ALARM_START_HOUR:00 to ALARM_END_HOUR:00; outside that they're silent. */
    @Volatile var ALARM_START_HOUR = 7
        private set
    @Volatile var ALARM_END_HOUR = 19
        private set

    /** "I'll call you shortly" / "in a bit" → reminder this many minutes later. */
    const val SHORTLY_MINUTES = 20

    /**
     * The only WhatsApp groups where hackathon/registration talk counts as a task. In any other
     * group it's a general announcement for all students. Personal chats always count.
     */
    @Volatile var hackathonGroups = listOf("hackstreet_Boys")
        private set

    /** Contacts that get "on a call, will call back" while the owner is on a call (opt-in, empty = off). */
    @Volatile var autoReplyContacts = emptyList<String>()
        private set

    @Volatile private var mentionRegex = buildMention()

    private fun buildMention() = Regex("(?:@|\\b)(${names.joinToString("|") { Regex.escape(it) }})\\b", RegexOption.IGNORE_CASE)

    /** Loads the owner's saved settings; call once at app start. */
    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("names", null)?.let { names = splitList(it).ifEmpty { names } }
        GRADUATION_YEAR = p.getInt("grad_year", GRADUATION_YEAR)
        GROUP_REQUEST_DEFAULT_MINUTES = p.getInt("group_minutes", GROUP_REQUEST_DEFAULT_MINUTES)
        ALARM_START_HOUR = p.getInt("alarm_start", ALARM_START_HOUR)
        ALARM_END_HOUR = p.getInt("alarm_end", ALARM_END_HOUR)
        p.getString("hackathon_groups", null)?.let { hackathonGroups = splitList(it) }
        autoReplyContacts = splitList(p.getString("auto_reply", "") ?: "")
        mentionRegex = buildMention()
    }

    fun save(context: Context, names: String, gradYear: Int, hackathonGroups: String, alarmStart: Int, alarmEnd: Int, groupMinutes: Int, autoReply: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("auto_reply", autoReply)
            .putString("names", names).putInt("grad_year", gradYear).putString("hackathon_groups", hackathonGroups)
            .putInt("alarm_start", alarmStart.coerceIn(0, 23)).putInt("alarm_end", alarmEnd.coerceIn(1, 24))
            .putInt("group_minutes", groupMinutes.coerceIn(5, 24 * 60)).apply()
        init(context)
    }

    private fun splitList(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    fun isHackathonGroup(chatName: String?): Boolean =
        chatName != null && hackathonGroups.any { key(it) == key(chatName) }

    fun isMentioned(text: String): Boolean = mentionRegex.containsMatchIn(text)
}

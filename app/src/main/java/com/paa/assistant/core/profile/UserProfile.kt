package com.paa.assistant.core.profile

/**
 * Facts about the phone's owner that chat detection relies on.
 * Kept on-device; never sent to any cloud service.
 */
object UserProfile {
    /** Names people use for the user in group chats (matched as whole words, case-insensitive). */
    val names = listOf("shreyash", "shreyas")

    /** Graduation batch — internship posts for other batches are ignored. */
    const val GRADUATION_YEAR = 2028

    /** Deadline when someone in a group asks the user to do something without giving a time. */
    const val GROUP_REQUEST_DEFAULT_MINUTES = 60

    /** Alarms may ring only from ALARM_START_HOUR:00 to ALARM_END_HOUR:00; outside that they're silent. */
    const val ALARM_START_HOUR = 7
    const val ALARM_END_HOUR = 19

    /** "I'll call you shortly" / "in a bit" → reminder this many minutes later. */
    const val SHORTLY_MINUTES = 20

    /**
     * The only WhatsApp groups where hackathon/registration talk counts as a task. In any other
     * group it's a general announcement for all students. Personal chats always count.
     */
    val hackathonGroups = listOf("hackstreet_Boys")

    private fun key(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    fun isHackathonGroup(chatName: String?): Boolean =
        chatName != null && hackathonGroups.any { key(it) == key(chatName) }

    private val mentionRegex = Regex("(?:@|\\b)(${names.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

    fun isMentioned(text: String): Boolean = mentionRegex.containsMatchIn(text)
}

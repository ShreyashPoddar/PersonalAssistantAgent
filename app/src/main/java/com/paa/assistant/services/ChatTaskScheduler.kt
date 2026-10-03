package com.paa.assistant.services

import android.content.Context
import com.paa.assistant.core.privacy.PrivacyGuard
import com.paa.assistant.core.reminders.ReminderNotifier
import com.paa.assistant.core.reminders.ReminderScheduler
import com.paa.assistant.data.models.TaskEntity
import com.paa.assistant.data.repository.TaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-schedules tasks detected in chats (WhatsApp / Telegram notifications and typed text).
 *
 *  - No deadline in the message → due at end of today (23:59), heads-up at 9 PM
 *  - With a deadline → heads-up 1 hour before, alarm at the deadline
 *  - Posts a "📌 Scheduled" notification with ↩ Undo
 *  - Never schedules the same task twice: if several people mention it, or it matches a pending
 *    task by meaning ("Register for SIH" ≈ "SIH registration"), it's skipped. If the new message
 *    gives an earlier deadline, the existing task is moved up instead.
 */
@Singleton
class ChatTaskScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TaskRepository
) {
    private val mutex = Mutex()

    companion object {
        private const val CHAT_EARLY_MINUTES = 60
        /** Tag on tasks whose deadline PAA guessed because none was said yet. */
        const val AUTO_DUE = "#autodue"
        fun withoutAutoDue(tags: String) = tags.split(",").filter { it.isNotBlank() && it != AUTO_DUE }.joinToString(",")
        private val STOPWORDS = setOf(
            "the", "a", "an", "my", "your", "for", "to", "of", "with", "and", "on", "in", "at", "it", "this", "that",
            "bro", "bhai", "please", "pls", "task", "ko", "ke", "ki", "ka", "liye", "se", "wala", "wali"
        )
        private val SYNONYMS = mapOf(
            "registration" to "register", "registering" to "register", "registered" to "register",
            "submission" to "submit", "submitting" to "submit", "calling" to "call", "ring" to "call",
            "payment" to "pay", "paying" to "pay", "ordering" to "order", "application" to "apply",
            "applying" to "apply", "ppt" to "presentation", "assignments" to "assignment"
        )

        fun keywords(title: String): Set<String> = title.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in STOPWORDS }
            .map { SYNONYMS[it] ?: it }
            .toSet()

        /** Same task if most keywords are shared ("Register for SIH" ≈ "SIH registration"). */
        fun isSameTask(a: String, b: String): Boolean {
            val ka = keywords(a)
            val kb = keywords(b)
            if (ka.isEmpty() || kb.isEmpty()) return a.equals(b, ignoreCase = true)
            if (distinct(ka, kb)) return false
            val shared = (ka intersect kb).size
            val jaccard = shared.toDouble() / (ka union kb).size
            val smaller = minOf(ka.size, kb.size)
            return jaccard >= 0.6 || (smaller >= 2 && shared == smaller)
        }

        /** Looser: shares ≥2 meaningful words and isn't [distinct] (for merging one chat's tasks). */
        fun sharesWork(a: String, b: String, ignore: Set<String> = emptySet()): Boolean {
            val ka = keywords(a) - ignore
            val kb = keywords(b) - ignore
            return !distinct(ka, kb) && ((ka intersect kb).count { k -> !k.any(Char::isDigit) } >= 2 || isSameTask(a, b))
        }

        /**
         * Each title has its own specific word the other lacks — "Send biology practical file" vs
         * "Send chemistry practical file", "Send notes to Riya" vs "Send notes to Kabir": different tasks.
         */
        private fun distinct(ka: Set<String>, kb: Set<String>): Boolean =
            (ka - kb - GENERIC).any { !it.any(Char::isDigit) } && (kb - ka - GENERIC).any { !it.any(Char::isDigit) }

        private val GENERIC = setOf(
            "send", "submit", "call", "pay", "order", "register", "apply", "complete", "finish", "do", "make", "bring",
            "check", "get", "buy", "share", "give", "take", "meet", "reply", "confirm", "back", "file", "assignment",
            "practical", "record", "report", "notes", "presentation", "project", "work", "link", "form", "fees", "bill",
            "before", "by", "till", "tomorrow", "today", "tonight", "pm", "am"
        )
    }

    /**
     * @param source shown to the user, e.g. "Rahul" or "typed message".
     * @param defaultDueMinutes deadline when none was given, as minutes from now; null = end of today.
     * @param earlyMinutes heads-up lead time; null = default for the deadline type.
     */
    suspend fun schedule(
        title: String,
        timestamp: Long?,
        priority: Int,
        tag: String,
        source: String,
        defaultDueMinutes: Int? = null,
        earlyMinutes: Int? = null,
        reason: String? = null,
        sourceMessage: String? = null
    ): Boolean {
        val now = System.currentTimeMillis()
        // Serialize so two notifications arriving together can't both create the task
        mutex.withLock {
            val existing = repository.getUpcomingTasks(200).firstOrNull { isSameTask(it.title, title) }
            if (existing != null) {
                // Already scheduled — only move it up if this message gives an earlier real deadline
                val oldDue = existing.dueTimestamp
                // A real deadline replaces a guessed one (end of day / +1 h) even when it's later
                if (timestamp != null && timestamp > now && (oldDue == null || timestamp < oldDue || AUTO_DUE in existing.tags)) {
                    repository.updateTask(existing.copy(dueTimestamp = timestamp, tags = withoutAutoDue(existing.tags)))
                }
                return false
            }
            create(title, timestamp, priority, tag, source, defaultDueMinutes, earlyMinutes, now, reason, sourceMessage)
        }
        return true
    }

    /** New deadline and/or clearer title for an open task, from a later message. */
    suspend fun update(task: com.paa.assistant.data.models.TaskEntity, due: Long?, title: String?): Boolean = mutex.withLock {
        val newDue = due?.takeIf { it > System.currentTimeMillis() }
        val newTitle = title?.trim()?.takeIf { it.length >= 3 && !it.equals(task.title, ignoreCase = true) }
        if (newDue == null && newTitle == null) return@withLock false
        repository.updateTask(
            task.copy(
                dueTimestamp = newDue ?: task.dueTimestamp,
                title = newTitle ?: task.title,
                tags = if (newDue != null) withoutAutoDue(task.tags) else task.tags
            )
        )
        true
    }

    private suspend fun create(
        title: String,
        timestamp: Long?,
        priority: Int,
        tag: String,
        source: String,
        defaultDueMinutes: Int?,
        earlyMinutes: Int?,
        now: Long,
        reason: String?,
        sourceMessage: String?
    ) {

        val isDefaultDeadline = timestamp == null || timestamp <= now
        val isEod = isDefaultDeadline && defaultDueMinutes == null
        val due = when {
            !isDefaultDeadline -> timestamp!!
            defaultDueMinutes != null -> now + defaultDueMinutes * 60_000L
            else -> ReminderScheduler.endOfToday()
        }
        val early = earlyMinutes ?: when {
            isEod -> ReminderScheduler.EOD_EARLY_MINUTES
            isDefaultDeadline -> 15  // short default deadline (e.g. group request in 1 hour)
            else -> CHAT_EARLY_MINUTES
        }

        val id = repository.createTask(
            TaskEntity(
                title = title,
                dueTimestamp = due,
                priority = priority,
                tags = if (isDefaultDeadline) "$tag,$AUTO_DUE" else tag,
                // Prefix marks the task as chat-derived → never sent to Gemini (see PrivacyGuard)
                sourceApp = "${PrivacyGuard.CHAT_SOURCE_PREFIX} ($source)",
                reminderMinutesBefore = early,
                sourceMessage = sourceMessage,
                aiReason = reason
            )
        )
        ReminderNotifier.showScheduled(context, id, title, due, source, isEod, reason)
    }
}

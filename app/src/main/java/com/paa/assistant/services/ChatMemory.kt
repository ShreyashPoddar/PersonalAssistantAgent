package com.paa.assistant.services

/**
 * Short-term chat memory for the pipeline (TASK_PIPELINE_V2 §3), pure so it's unit-tested:
 *  - carry-over: lines that gave nothing are kept per chat for [windowMs] (max [maxLines]) and read again with the
 *    chat's next burst, so a request sent in pieces is read together;
 *  - group follow-ups: who mentioned the owner in a chat, and when — their next messages within [windowMs] count as
 *    addressed to the owner.
 */
class ChatMemory(
    private val windowMs: Long = 30 * 60_000L,
    private val maxLines: Int = 8,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val carry = HashMap<String, MutableList<Pair<Long, String>>>()
    private val mentionedBy = HashMap<String, HashMap<String, Long>>()

    @Synchronized fun takeCarry(chat: String): List<String> {
        val t = now()
        carry[chat]?.removeAll { t - it.first > windowMs }
        return carry[chat].orEmpty().map { it.second }
    }

    /** Keeps [lines] (oldest first) for the chat; a line keeps the time it was first seen. */
    @Synchronized fun keepCarry(chat: String, lines: List<String>) {
        val t = now()
        val old = carry[chat].orEmpty().associate { it.second to it.first }
        carry[chat] = lines.distinct().takeLast(maxLines).map { (old[it] ?: t) to it }.toMutableList()
    }

    @Synchronized fun clearCarry(chat: String) { carry.remove(chat) }

    /** Records that [senders] mentioned the owner in [chat] now. */
    @Synchronized fun mentioned(chat: String, senders: List<String>) {
        val t = now()
        senders.forEach { mentionedBy.getOrPut(chat) { HashMap() }[it] = t }
    }

    /** True when any of [senders] mentioned the owner in [chat] within the window. */
    @Synchronized fun isFollowUp(chat: String, senders: List<String>): Boolean {
        val t = now()
        return senders.any { s -> mentionedBy[chat]?.get(s)?.let { t - it < windowMs } == true }
    }
}

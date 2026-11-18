package com.paa.assistant.services

import android.content.Context
import com.paa.assistant.core.ai.LocalLlm
import com.paa.assistant.core.links.HackathonLinkResolver
import com.paa.assistant.core.profile.UserProfile
import com.paa.assistant.core.router.CommitmentExtractor
import com.paa.assistant.core.router.LocalTaskParser
import com.paa.assistant.data.db.FeedbackDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Where a chat message came from. */
enum class ChatApp {
    WHATSAPP, TELEGRAM, GMAIL,
    /** Transcript of the user's own phone/WhatsApp call, captured after they said "Oyee PA". */
    CALL
}

/** One chat message, from a notification or read from the open chat screen. */
data class ChatMessage(
    val text: String,
    val app: ChatApp,
    val outgoing: Boolean,
    /** Person or group name. */
    val chatName: String?,
    /** Who wrote it (group messages). */
    val sender: String?,
    val isGroup: Boolean,
    /** Earlier messages visible on screen (oldest first), if the source could read them. */
    val recentMessages: List<String> = emptyList(),
    /** Several messages of one chat merged ("Speaker: text" per line), see [ChatMessageProcessor.process]. */
    val burst: Boolean = false
)

/**
 * The single pipeline every chat message goes through. Runs entirely on the phone.
 *
 *  1. Who is talking ([SenderKinds]): business accounts, bots and app notices never create tasks.
 *  2. Rules that don't need understanding: internship posts for other batches are dropped; hackathon
 *     links only from personal chats or [UserProfile.hackathonGroups]; other groups only when the owner is
 *     named, or a follow-up comes from someone who named them, or an open task came from that group.
 *  3. The on-device model reads the new lines with the chat so far. Lines that gave nothing are carried
 *     into the chat's next burst for 30 min, so a request sent in pieces is read together.
 *  4. [ChatTaskChecks] on every proposed task: advice isn't a task, details must be in the chat, titles
 *     never name the owner. Confident (≥ [AUTO_SCHEDULE]) → scheduled; unsure (≥ [ASK_USER]) → asked.
 *  5. Without a working model nothing is guessed (rule fallbacks made junk tasks): the chat is retried
 *     once 2 min later. See docs/build/TASK_PIPELINE_V2.md.
 */
@Singleton
class ChatMessageProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localLlm: LocalLlm,
    private val parser: LocalTaskParser,
    private val linkResolver: HackathonLinkResolver,
    private val scheduler: ChatTaskScheduler,
    private val feedbackDao: FeedbackDao,
    private val taskDao: com.paa.assistant.data.db.TaskDao,
    private val historyDao: com.paa.assistant.data.db.ChatHistoryDao
) {
    // Messages that arrive close together are read together: a task and its deadline are often
    // split over several messages ("notes bhej dena" … "kal tak" … "5 baje se pehle").
    private val bursts = HashMap<String, MutableList<ChatMessage>>()
    private val burstJobs = HashMap<String, kotlinx.coroutines.Job>()
    private val burstScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    // A request often arrives in pieces minutes apart ("UPI nahi ho raha" … QR … "jaldi kar, expire ho jayega"). Lines
    // that gave nothing are carried into the chat's next burst for 30 min, so the pieces are read together.
    private val carry = HashMap<String, MutableList<Pair<Long, String>>>()
    // Group: who mentioned the owner, and when — their follow-ups count as addressed to the owner for 30 min
    private val mentionedBy = HashMap<String, HashMap<String, Long>>()
    companion object {
        const val AUTO_SCHEDULE = 0.75f
        /** Details that appear only in LocalLlm's few-shot examples. */
        private val PROMPT_EXAMPLE_WORDS = listOf("ahana", "senthil", "dbms", "rahul", "ppt", "groceries", "wifi", "flatmates", "rohit", "aman", "riya", "dadaji")
        const val ASK_USER = 0.4f
        private const val HISTORY_PER_CHAT = 12
        /** Quiet time after the last message of a burst before it's read. */
        private const val BURST_MS = 20_000L
        private const val CARRY_MS = 30 * 60_000L
        private const val CARRY_LINES = 8
        private val DAY_WORDS = Regex("\\b(kal|aaj|parso|parson|tomorrow|today|tonight|raat|subah|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun|somvar|mangal|budh|guru|shukra|shani|ravi|next week|\\d{1,2}(st|nd|rd|th)|date)\\b")
        /** Same day said in Hinglish or English. */
        private val DAY_GROUPS = listOf(
            listOf("kal", "tomorrow"), listOf("aaj", "today", "tonight", "raat"), listOf("parso", "parson", "day after")
        )
        private val AFFIRMATIVE = Regex(
            "^(hn|hnn|haan|han|ha|haa|hanji|ji|yes|yep|yeah|ok|okay|k|done|confirm(ed)?|sure|theek hai|thik hai|ho gaya|kar diya|pakka)" +
                "( (bhai|bro|yaar|ji|sir|done|confirm))*[.!👍 ]*$",
            RegexOption.IGNORE_CASE
        )
        private val REACTION = Regex("^Reacted .+ to [\"“].*", RegexOption.DOT_MATCHES_ALL)
        private val TIME_WORDS = setOf("before", "by", "till", "until", "tomorrow", "today", "tonight", "pm", "am", "kal", "aaj", "baje", "tak", "pehle", "deadline")
    }

    private val registrationTalk = Regex("\\b(register|registration|hackathon|hackfest|ideathon|buildathon)\\b", RegexOption.IGNORE_CASE)
    private val internshipWords = Regex("\\b(internships?|hiring|off[- ]?campus|summer intern|apply now|job opening)\\b", RegexOption.IGNORE_CASE)
    private val batchYearsBefore = Regex("((?:20[2-3]\\d\\s*(?:,|/|&|and|or|-)?\\s*)+)(?:batch|grads?|graduates|passouts?|pass-?outs?)", RegexOption.IGNORE_CASE)
    private val batchYearsAfter = Regex("(?:batch(?:es)?|class|passout|pass-?out|graduating)\\s*(?:of|in|year|:)?\\s*((?:20[2-3]\\d\\s*(?:,|/|&|and|or|-)?\\s*)+)", RegexOption.IGNORE_CASE)
    private val year = Regex("20[2-3]\\d")
    // Messages with no letters or digits (emoji, "ok", "😂😂") can't hold a task
    private val trivial = Regex("^[\\p{So}\\p{Sk}\\p{P}\\s]*$|^(ok|okay|k|hmm+|haha+|lol|yes|no|ha+n?|nhi|thik hai|theek hai|👍)$", RegexOption.IGNORE_CASE)


    private fun hackathonAllowed(msg: ChatMessage): Boolean =
        msg.app != ChatApp.GMAIL && (!msg.isGroup || UserProfile.isHackathonGroup(msg.chatName))

    /** @return how many tasks were scheduled or asked about. */
    /** A task found but not yet scheduled (call pre-analysis). */
    data class Prepared(val title: String, val timestamp: Long?, val priority: Int, val tag: String, val reason: String?, val source: String, val text: String)

    /** Schedules tasks found earlier by a dry run. @return how many were new. */
    suspend fun commit(prepared: List<Prepared>): Int = prepared.count { p ->
        scheduler.schedule(title = p.title, timestamp = p.timestamp, priority = p.priority, tag = p.tag, source = p.source,
            reason = p.reason, sourceMessage = p.text).also { created ->
            DetectionLog.add("📞 call", p.text.take(60), (if (created) "📌 " else "already scheduled: ") + p.title)
        }
    }

    /**
     * @param dryRun if given, tasks are collected here instead of scheduled (nothing is logged as scheduled).
     * @return how many tasks were scheduled or asked about.
     */
    suspend fun process(msg: ChatMessage, dryRun: MutableList<Prepared>? = null): Int {
        // Calls and dry runs are already whole; chat messages wait for the rest of their burst
        if (dryRun != null || msg.app == ChatApp.CALL) return processNow(msg, dryRun)
        // Business accounts, bots and app texts never create tasks (most false tasks in real use came from them)
        val kind = SenderKinds.of(context, msg)
        if (kind == SenderKind.BUSINESS || kind == SenderKind.SYSTEM) {
            DetectionLog.add("← " + (msg.chatName ?: msg.app.name.lowercase(Locale.ROOT)), msg.text,
                "skipped: ${if (kind == SenderKind.SYSTEM) "app notice" else "business / bot account — not a saved contact"}")
            return 0
        }
        if (REACTION.matches(msg.text.trim())) return 0  // "Reacted 👍 to …"
        remember(msg, pending = true)  // saved right away, so nothing is lost if the app restarts mid-burst
        val key = chatKey(msg)
        synchronized(bursts) {
            bursts.getOrPut(key) { mutableListOf() } += msg
            burstJobs[key]?.cancel()  // still waiting → restart the quiet timer
            burstJobs[key] = burstScope.launch {
                kotlinx.coroutines.delay(BURST_MS)
                val batch = synchronized(bursts) { burstJobs.remove(key); bursts.remove(key) } ?: return@launch
                val lines = batch.map { "${who(it)}: ${it.text.trim()}" }
                val carried = takeCarry(key)
                val merged = if (carried.isEmpty()) merge(batch)
                    else merge(batch).copy(text = (carried + lines).joinToString("\n"), burst = true)
                var reachedModel = false
                val found = processNow(merged, null, remembered = true, onModel = { reachedModel = true })
                if (found == 0 && reachedModel) keepCarry(key, carried + lines) else clearCarry(key)
                runCatching { historyDao.markRead(key) }
            }
        }
        return 0
    }

    private fun takeCarry(key: String): List<String> = synchronized(carry) {
        val now = System.currentTimeMillis()
        carry[key]?.removeAll { now - it.first > CARRY_MS }
        carry[key].orEmpty().map { it.second }
    }

    private fun keepCarry(key: String, lines: List<String>) = synchronized(carry) {
        val now = System.currentTimeMillis()
        val old = carry[key].orEmpty().associate { it.second to it.first }
        carry[key] = lines.distinct().takeLast(CARRY_LINES).map { (old[it] ?: now) to it }.toMutableList()
    }

    private fun clearCarry(key: String) = synchronized(carry) { carry.remove(key) }

    /** Senders of "Name: text" lines (or the message's own sender). */
    private fun senders(msg: ChatMessage): List<String> =
        if (msg.burst) msg.text.lines().mapNotNull { l -> l.substringBefore(": ", "").takeIf { it.isNotBlank() && it != "You" } }
        else listOfNotNull(msg.sender)

    /**
     * The model sometimes lists one piece of work twice ("Send DBMS assignment before 5 PM" and
     * "Send DBMS assignment to Riya" for "…bhej dena / kal tak / 5 baje se pehle"): merge them, joining
     * their time words so "kal tak" + "5 baje se pehle" becomes tomorrow 5 PM.
     */
    private fun mergeSameWork(tasks: List<com.paa.assistant.core.ai.LlmTask>, people: List<String>): List<com.paa.assistant.core.ai.LlmTask> {
        val out = mutableListOf<com.paa.assistant.core.ai.LlmTask>()
        for (t in tasks) {
            val ignore = TIME_WORDS + people.flatMap { ChatTaskScheduler.keywords(it) }
            val i = out.indexOfFirst { o -> ChatTaskScheduler.sharesWork(o.title, t.title, ignore) }
            if (i < 0) { out += t; continue }
            val o = out[i]
            val named = listOf(o, t).firstOrNull { c -> people.any { c.title.contains(it, ignoreCase = true) } }
            out[i] = o.copy(
                title = (named ?: listOf(o, t).minBy { c -> c.title.count(Char::isDigit) * 100 + c.title.length }).title,
                whenPhrase = listOfNotNull(o.whenPhrase, t.whenPhrase).distinct().joinToString(" ").ifBlank { null },
                confidence = maxOf(o.confidence, t.confidence),
                reason = o.reason ?: t.reason
            )
        }
        return out
    }

    /**
     * "4 baje tak" for a task due Monday means Monday 4 PM, not the next 4 o'clock: a time without a
     * day word keeps the task's own day.
     */
    private fun anchoredDue(phrase: String, currentDue: Long?): Long? {
        val resolved = parser.resolveWhen(phrase) ?: return null
        if (currentDue == null || DAY_WORDS.containsMatchIn(phrase.lowercase(Locale.ROOT))) return resolved
        val time = java.util.Calendar.getInstance().apply { timeInMillis = resolved }
        val anchored = java.util.Calendar.getInstance().apply {
            timeInMillis = currentDue
            set(java.util.Calendar.HOUR_OF_DAY, time.get(java.util.Calendar.HOUR_OF_DAY))
            set(java.util.Calendar.MINUTE, time.get(java.util.Calendar.MINUTE))
        }.timeInMillis
        return if (anchored > System.currentTimeMillis()) anchored else resolved
    }

    /**
     * The model sometimes invents time words — e.g. "kal 5 baje" copied from its own instructions when
     * the chat said "4 baje tak". Every number and day word it gives must appear in what was really said;
     * otherwise the newest message's own words are used.
     */
    private fun groundedWhen(phrase: String?, said: String, newest: String): String? {
        phrase ?: return null
        val p = phrase.lowercase(Locale.ROOT)
        val s = said.lowercase(Locale.ROOT)
        val sDigits = s.filter(Char::isDigit)
        val numbersOk = Regex("\\d+").findAll(p).all { m ->
            Regex("(?<!\\d)${m.value}(?!\\d)").containsMatchIn(s)
        } || p.filter(Char::isDigit).let { it.isNotEmpty() && it in sDigits }
        val daysOk = DAY_GROUPS.all { group ->
            group.none { Regex("\\b$it\\b").containsMatchIn(p) } || group.any { Regex("\\b$it\\b").containsMatchIn(s) }
        }
        if (numbersOk && daysOk) return phrase
        return newest.takeIf { parser.resolveWhen(it) != null }
    }

    /** One message as is; several as "Speaker: text" lines. */
    private fun merge(batch: List<ChatMessage>): ChatMessage {
        if (batch.size == 1) return batch[0]
        val last = batch.last()
        return last.copy(
            text = batch.joinToString("\n") { "${who(it)}: ${it.text.trim()}" },
            outgoing = batch.any { it.outgoing },
            sender = batch.lastOrNull { !it.outgoing }?.sender ?: last.sender,
            recentMessages = batch.first().recentMessages,
            burst = true
        )
    }

    private fun who(m: ChatMessage) = if (m.outgoing) "You" else (m.sender ?: m.chatName ?: "Them")

    private suspend fun processNow(
        msg: ChatMessage, dryRun: MutableList<Prepared>?, remembered: Boolean = false,
        onModel: (() -> Unit)? = null, attempt: Int = 0
    ): Int {
        var found = 0
        val text = msg.text.trim()
        // Open tasks from this chat: even a short "hn bhai" / "done" can finish one of them
        // When the owner writes, any open task may be the one they say they finished ("DBMS submit ho gaya")
        val chatTasks = msg.chatName?.let { chat ->
            taskDao.getPendingChatTasks().filter { "($chat)" in it.sourceApp || " in $chat)" in it.sourceApp || " to $chat)" in it.sourceApp }
        }.orEmpty()
        // A bare "ok"/"hn bhai" can only finish a task from this same chat
        if (text.length < 2 || (trivial.matches(text) && chatTasks.isEmpty())) { if (!remembered) remember(msg); return found }
        val openTasks = (chatTasks + if (msg.outgoing) taskDao.getUpcomingTasks(8) else emptyList()).distinctBy { it.id }.take(8)
        val source = describeSource(msg)
        val allowHackathon = hackathonAllowed(msg)
        val logSource = (if (msg.outgoing) "→ " else "← ") + (msg.chatName ?: msg.app.name.lowercase(Locale.ROOT))
        val log = { result: String -> DetectionLog.add(logSource, text, result) }
        // Written before any work: if the app dies while processing, this line stays without a result
        log("⏳ checking…")

        // Conversation context = what we've seen in this chat + whatever the screen showed
        // (the burst's own lines are already saved — leave them out of "earlier")
        val ownLines = if (msg.burst) text.lines().toSet() else setOf("${who(msg)}: ${text.take(200)}")
        val conversation = (chatHistory(msg).filterNot { it in ownLines } + msg.recentMessages).distinct().takeLast(HISTORY_PER_CHAT)
        if (!remembered) remember(msg)

        if (isInternshipForOtherBatch(text)) { log("skipped: internship for another batch"); return found }

        // Hackathon links: only where hackathons are really discussed (never from job-post chats)
        if (allowHackathon && !internshipWords.containsMatchIn(text)) {
            for (url in linkResolver.extractUrls(text).take(3)) {
                val hackathon = linkResolver.resolve(url) ?: continue
                val created = scheduler.schedule(
                    title = "Register for ${hackathon.name}",
                    timestamp = hackathon.deadline,
                    priority = 2,
                    tag = "#hackathon",
                    source = source,
                    earlyMinutes = if (hackathon.deadline != null) 24 * 60 else null,
                    reason = "A hackathon link was shared in ${msg.chatName ?: "a personal chat"}.",
                    sourceMessage = text
                )
                found++
                log(if (created) "📌 Register for ${hackathon.name}" else "already scheduled: Register for ${hackathon.name}")
            }
        }

        val mentioned = UserProfile.isMentioned(text)
        val key = chatKey(msg)
        val now = System.currentTimeMillis()
        // Follow-ups from someone who mentioned the owner in the last 30 min are addressed to the owner too
        val followUp = msg.isGroup && synchronized(mentionedBy) {
            if (mentioned) senders(msg).forEach { mentionedBy.getOrPut(key) { HashMap() }[it] = now }
            senders(msg).any { s -> mentionedBy[key]?.get(s)?.let { now - it < CARRY_MS } == true }
        }
        // (finishing an open group task is judged from the owner's own replies, not everyone's chatter)
        if (msg.isGroup && !msg.outgoing && !mentioned && !followUp && chatTasks.isEmpty() && !UserProfile.isHackathonGroup(msg.chatName)) {
            log("skipped: group message not addressed to you"); return found
        }

        // No keyword pre-filter: it dropped short follow-ups ("kar de", "expire ho jayega") that complete a request
        onModel?.invoke()
        if (localLlm.isAvailable()) log("🧠 asking local AI…")
        // The owner's past ✓/✗ are NOT shown to the model: a 1B model copied their titles into unrelated chats
        val learned = emptyList<Pair<String, String?>>()
        val verdict = localLlm.extractTasks(
            text,
            LocalLlm.ChatContext(
                outgoing = msg.outgoing,
                chatName = msg.chatName,
                sender = msg.sender,
                isGroup = msg.isGroup,
                recentMessages = conversation,
                learnedExamples = learned,
                isCall = msg.app == ChatApp.CALL,
                openTasks = openTasks.map { t ->
                    t.title + (t.dueTimestamp?.let { due ->
                        " (due " + java.text.SimpleDateFormat("EEE d MMM h:mm a", Locale.ENGLISH).format(java.util.Date(due)) +
                            (if (ChatTaskScheduler.AUTO_DUE in t.tags) ", guessed — no deadline said yet" else "") + ")"
                    } ?: "")
                },
                burst = msg.burst
            )
        )

        // Later messages that change an open task (new deadline, clearer title)
        verdict?.updates?.forEach { u ->
            val t = openTasks.getOrNull(u.index) ?: return@forEach
            val due = groundedWhen(u.whenPhrase, (listOf(text) + conversation).joinToString("\n"), text)?.let { anchoredDue(it, t.dueTimestamp) }
            if (scheduler.update(t, due, u.title)) {
                log("🔁 updated: ${u.title ?: t.title}" + (due?.let { " → " + java.text.SimpleDateFormat("EEE h:mm a", Locale.ENGLISH).format(java.util.Date(it)) } ?: ""))
            }
        }
        // Finished tasks: mark complete and stop their alarms
        val finished = verdict?.done?.mapNotNull { openTasks.getOrNull(it) }.orEmpty().toMutableList()
        // Safety net the model misses: a short "yes" from the owner answering a question that became a
        // task in this chat ("10 final na??" → "Hn bhai") settles it
        if (finished.isEmpty() && msg.outgoing && AFFIRMATIVE.matches(text.trim())) {
            chatTasks.filter { it.sourceMessage?.contains('?') == true && System.currentTimeMillis() - it.createdAt < 2 * 3_600_000L }
                .maxByOrNull { it.createdAt }?.let { finished += it }
        }
        finished.forEach { t ->
            taskDao.updateStatus(t.id, "COMPLETED")
            com.paa.assistant.core.reminders.ReminderScheduler.cancel(context, t.id)
            com.paa.assistant.core.reminders.ReminderNotifier.showAutoCompleted(context, t.id, t.title, msg.chatName ?: "?")
            log("✅ marked done: ${t.title}")
        }

        val candidates: List<Candidate> = if (verdict != null) {
            if (verdict.tasks.isEmpty()) { log("no task (AI): ${verdict.noTaskReason ?: "nothing for you to do"}"); return found }
            // Small models sometimes copy a task from the prompt's examples instead of reading the message
            val seen = (listOfNotNull(text, msg.chatName, msg.sender) + conversation).joinToString(" ").lowercase(Locale.ROOT)
            val (copied, real) = verdict.tasks.partition { t ->
                val said = t.title.lowercase(Locale.ROOT)  // title only: the reason text may mention example names harmlessly
                PROMPT_EXAMPLE_WORDS.any { it in said && it !in seen } ||
                    // ...or from the owner's own past ✓/✗ examples, when none of its words are in this chat
                    learned.any { (_, title) -> title != null && ChatTaskScheduler.isSameTask(title, t.title) } &&
                    ChatTaskScheduler.keywords(t.title).none { it in seen }
            }
            copied.forEach { log("ignored \"${it.title}\": copied from the AI's examples, not in this chat") }
            if (real.isEmpty()) return found
            val said = (listOf(text) + conversation).joinToString("\n")
            mergeSameWork(real, listOfNotNull(msg.chatName, msg.sender)).map {
                val wp = groundedWhen(it.whenPhrase, said, text)
                Candidate(it.title, wp?.let(parser::resolveWhen), it.priority, it.isRegistration, it.confidence, it.reason, "AI", wp)
            }
        } else {
            // No rule fallback: it turned the owner's own questions into "promises". Retry once a little later.
            if (!localLlm.isAvailable()) { log("skipped: install the local AI to understand messages"); return found }
            if (dryRun == null && attempt == 0) {
                log("❌ local AI failed: ${localLlm.lastError ?: "unknown error"} — retrying in 2 min")
                burstScope.launch { kotlinx.coroutines.delay(120_000L); processNow(msg, null, remembered = true, attempt = 1) }
            } else log("❌ local AI failed again: ${localLlm.lastError ?: "unknown error"} — try 🔬 Test local AI")
            return found
        }

        val saidAll = (listOf(text, msg.chatName.orEmpty(), msg.sender.orEmpty()) + conversation).joinToString("\n")
        val requester = if (msg.outgoing) msg.chatName else (msg.sender ?: msg.chatName)
        for (raw in candidates) {
            if (ChatTaskChecks.isAdvice(text, raw.title)) { log("ignored \"${raw.title}\": advice or a wish, not a task"); continue }
            if (!ChatTaskChecks.grounded(raw.title, saidAll)) { log("ignored \"${raw.title}\": has details that aren't in this chat"); continue }
            // "Send ppt to <owner>" is reversed: fix "to <owner>" → "to <requester>", else only ask
            val fixedTitle = ChatTaskChecks.fixOwnerName(raw.title, UserProfile.names, requester)
            val c = if (fixedTitle != null) raw.copy(title = fixedTitle) else raw.copy(confidence = minOf(raw.confidence, 0.6f))
            if ((c.isRegistration || registrationTalk.containsMatchIn(c.title)) && !allowHackathon) {
                log("skipped \"${c.title}\": hackathon talk outside allowed chats"); continue
            }
            val title = withChatHint(c.title, msg)
            // The model sometimes re-lists an open task instead of updating it ("4 baje tak bhi chalega")
            val same = if (dryRun == null) openTasks.firstOrNull { o ->
                ChatTaskScheduler.sharesWork(o.title, c.title, TIME_WORDS + listOfNotNull(msg.chatName, msg.sender).flatMap { ChatTaskScheduler.keywords(it) })
            } else null
            if (same != null) {
                val due = c.whenPhrase?.let { anchoredDue(it, same.dueTimestamp) }
                if (due != null && scheduler.update(same, due, null)) {
                    log("🔁 updated: ${same.title} → " + java.text.SimpleDateFormat("EEE d MMM h:mm a", Locale.ENGLISH).format(java.util.Date(due)) +
                        " (from \"${c.whenPhrase}\")")
                } else log("already scheduled: ${same.title}")
                found++
                continue
            }
            if (dryRun != null) {
                if (c.confidence >= ASK_USER) { dryRun += Prepared(title, c.timestamp, c.priority, categorize(c.title), c.reason, source, text); found++ }
                continue
            }
            when {
                c.confidence >= AUTO_SCHEDULE -> {
                    val created = scheduler.schedule(
                        title = title,
                        timestamp = c.timestamp,
                        priority = c.priority,
                        tag = categorize(c.title),
                        source = source,
                        reason = c.reason,
                        sourceMessage = text
                    )
                    found++
                    val dueText = c.timestamp?.let {
                        " · due " + java.text.SimpleDateFormat("EEE d MMM h:mm a", Locale.ENGLISH).format(java.util.Date(it)) +
                            (c.whenPhrase?.let { w -> " (from \"$w\")" } ?: "")
                    } ?: " · no deadline (none was said)"
                    log((if (created) "📌 $title" else "already scheduled: $title") + dueText + " (${c.engine}) — ${c.reason ?: ""}")
                }
                c.confidence >= ASK_USER -> {
                    TaskSuggestions.ask(
                        context,
                        TaskSuggestions.Suggestion(
                            title = title,
                            timestamp = c.timestamp,
                            priority = c.priority,
                            tag = categorize(c.title),
                            source = source,
                            defaultDueMinutes = null,
                            reason = c.reason,
                            message = text
                        )
                    )
                    found++
                    log("❓ asked you: $title (${(c.confidence * 100).toInt()}% sure) — ${c.reason ?: ""}")
                }
                else -> log("ignored (${(c.confidence * 100).toInt()}% sure): $title — ${c.reason ?: ""}")
            }
        }
        return found
    }

    private data class Candidate(
        val title: String,
        val timestamp: Long?,
        val priority: Int,
        val isRegistration: Boolean,
        val confidence: Float,
        val reason: String?,
        val engine: String,
        /** The model's own time words, to re-anchor them on an existing task's day. */
        val whenPhrase: String? = null
    )

    /** The user's latest verdicts (✓ / ✗ / Undo) as examples for the model. */
    private suspend fun learnedExamples(): List<Pair<String, String?>> =
        (feedbackDao.recent(isTask = true, limit = 4).map { it.message to it.title } +
            feedbackDao.recent(isTask = false, limit = 4).map { it.message to null })
            .take(6)  // stable order (newest first): the same chat gives the same prompt

    private fun chatKey(msg: ChatMessage) = "${msg.app}|${msg.chatName ?: msg.sender ?: "?"}"

    /** Saved conversation of this chat, oldest first (encrypted DB, survives restarts). */
    private suspend fun chatHistory(msg: ChatMessage): List<String> =
        runCatching { historyDao.recent(chatKey(msg), HISTORY_PER_CHAT + 10).reversed() }.getOrDefault(emptyList())

    private suspend fun remember(msg: ChatMessage, pending: Boolean = false) {
        val key = chatKey(msg)
        val lines = if (msg.burst) msg.text.lines() else listOf("${who(msg)}: ${msg.text.trim().take(200)}")
        runCatching {
            lines.forEach {
                historyDao.insert(com.paa.assistant.data.models.ChatLineEntity(chatKey = key, line = it.take(220), pending = pending, isGroup = msg.isGroup))
            }
            historyDao.trim(key)
        }
    }

    init {
        // Bursts cut short by the app being killed: read them now (recent ones only)
        burstScope.launch {
            kotlinx.coroutines.delay(5_000)
            val lines = runCatching { historyDao.pending() }.getOrDefault(emptyList())
            for ((key, chatLines) in lines.groupBy { it.chatKey }) {
                if (synchronized(bursts) { key in bursts }) continue
                val fresh = chatLines.filter { System.currentTimeMillis() - it.createdAt < 15 * 60_000L }
                val app = ChatApp.entries.firstOrNull { key.startsWith(it.name + "|") }
                if (fresh.isNotEmpty() && app != null) {
                    val chat = key.substringAfter("|").takeIf { it != "?" }
                    val msgs = fresh.map { l ->
                        val mine = l.line.startsWith("You: ")
                        ChatMessage(
                            text = l.line.substringAfter(": "), app = app, outgoing = mine, chatName = chat,
                            sender = if (mine) null else l.line.substringBefore(": "), isGroup = l.isGroup
                        )
                    }
                    DetectionLog.add("system", chat ?: "chat", "↻ reading ${msgs.size} message(s) left unread by a restart")
                    processNow(merge(msgs), null, remembered = true)
                }
                runCatching { historyDao.markRead(key) }
            }
        }
    }

    /** "Submit it" says nothing on its own — add which chat it came from. */
    private fun withChatHint(title: String, msg: ChatMessage): String {
        val vague = Regex("^\\S+(?: \\S+)? (it|that|this|them|those|these)$", RegexOption.IGNORE_CASE).matches(title.trim())
        return if (vague && msg.chatName != null) "$title (chat with ${msg.chatName})" else title
    }

    private fun describeSource(msg: ChatMessage): String = when {
        msg.outgoing -> msg.chatName?.let { "your message to $it" } ?: "your message"
        msg.isGroup -> listOfNotNull(msg.sender, msg.chatName).joinToString(" in ").ifBlank { "a group" }
        else -> msg.chatName ?: msg.sender ?: msg.app.name.lowercase(Locale.ROOT)
    }

    private fun categorize(title: String): String {
        val lower = title.lowercase(Locale.getDefault())
        return when {
            registrationTalk.containsMatchIn(lower) -> "#hackathon"
            Regex("\\b(assignment|homework|exam|quiz|lab|submit|study)\\b").containsMatchIn(lower) -> "#study"
            Regex("\\b(call|ring|meet)\\b").containsMatchIn(lower) -> "#calls"
            internshipWords.containsMatchIn(lower) || lower.startsWith("apply") -> "#career"
            else -> "#chat"
        }
    }

    /** Internship posts naming batches, none of which is the user's, are skipped. */
    private fun isInternshipForOtherBatch(text: String): Boolean {
        if (!internshipWords.containsMatchIn(text)) return false
        val years = (batchYearsBefore.findAll(text) + batchYearsAfter.findAll(text))
            .flatMap { year.findAll(it.groupValues[1]) }
            .map { it.value.toInt() }.toSet()
        return years.isNotEmpty() && UserProfile.GRADUATION_YEAR !in years
    }
}

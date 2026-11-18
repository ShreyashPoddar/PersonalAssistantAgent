package com.paa.assistant.services

import android.content.Context
import android.provider.ContactsContract
import com.paa.assistant.core.messaging.ContactResolver
import java.util.Locale

/**
 * Who a chat is with, decided before any model runs. Business accounts, bots and channels made most of the false
 * tasks in real use (travel promos, delivery OTPs, bank promos, receipts): they never create tasks on their own.
 */
enum class SenderKind { PERSON, UNKNOWN_NUMBER, BUSINESS, JOB_CHANNEL, GROUP, SYSTEM }

object SenderKinds {
    /** Chats that post job / internship openings for the owner (they go through the batch filter). */
    private val JOB_CHAT = Regex("\\b(jobs?|internships?|placements?|careers?|hiring|opportunit\\w*)\\b", RegexOption.IGNORE_CASE)
    private val PHONE = Regex("^[+\\d][\\d\\s()-]{6,}$")
    private val SYSTEM_CHATS = setOf("whatsapp", "telegram", "whatsapp business")

    @Volatile private var names: Set<String> = emptySet()
    /** Debug builds only (DebugInjectReceiver): made-up chat names used by tests count as people. */
    val testPeople: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    @Volatile private var loadedAt = 0L

    /** Pure part, unit-tested: [contactNames] are lowercase saved-contact names. */
    fun classify(chatName: String?, isGroup: Boolean, isCall: Boolean, contactNames: Set<String>, contactsReadable: Boolean): SenderKind {
        val chat = chatName?.trim().orEmpty()
        val lc = chat.lowercase(Locale.ROOT)
        return when {
            isCall -> SenderKind.PERSON
            lc in SYSTEM_CHATS -> SenderKind.SYSTEM
            isGroup -> SenderKind.GROUP
            chat.isEmpty() -> SenderKind.UNKNOWN_NUMBER
            PHONE.matches(chat) || chat.startsWith("~") -> SenderKind.UNKNOWN_NUMBER
            !contactsReadable -> SenderKind.PERSON  // can't tell without the Contacts permission: keep old behaviour
            lc in contactNames -> SenderKind.PERSON
            JOB_CHAT.containsMatchIn(chat) -> SenderKind.JOB_CHANNEL
            else -> SenderKind.BUSINESS  // a name WhatsApp shows that isn't a saved contact = business / channel
        }
    }

    fun of(context: Context, msg: ChatMessage): SenderKind {
        val readable = ContactResolver.hasPermission(context)
        if (readable && System.currentTimeMillis() - loadedAt > 10 * 60_000L) reload(context)
        return classify(msg.chatName, msg.isGroup, msg.app == ChatApp.CALL, names + testPeople, readable)
    }

    private fun reload(context: Context) {
        names = runCatching {
            val out = HashSet<String>()
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out += it.trim().lowercase(Locale.ROOT) } }
            out
        }.getOrDefault(names)
        loadedAt = System.currentTimeMillis()
    }
}

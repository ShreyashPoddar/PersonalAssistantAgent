package com.paa.assistant.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

/**
 * ChatTaskAccessibilityService — covers what notifications can't:
 *
 *  1. Messages the user SENDS (WhatsApp / Telegram): processed once, when sent — not while typing.
 *  2. Messages RECEIVED while that WhatsApp chat is open (WhatsApp posts no notification then):
 *     read from the chat screen, only if timestamped within the last few minutes.
 *
 * Everything goes to [ChatMessageProcessor] and stays on the phone. Only these WhatsApp views are
 * read: the chat header name, message text, message time, sender name in groups, and the
 * sent-tick marker. The service is limited to WhatsApp/Telegram in accessibility_service_config.xml.
 */
@AndroidEntryPoint
class ChatTaskAccessibilityService : AccessibilityService() {

    @Inject lateinit var processor: ChatMessageProcessor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Outgoing: the text currently in the message box
    private var draft = ""
    private var draftPackage = ""

    // Incoming (open chat): what has been seen already
    private val seen = object : LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 500
    }
    private var openChat: String? = null
    private var lastScan = 0L

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        DetectionLog.init(this)
        DetectionLog.add("system", "Accessibility", "✅ connected — sent messages & open chats will be read")
        // Bring the "Oyee PA" listener back if it was on (best effort; Android may require opening the app)
        com.paa.assistant.services.WakeListenerService.startIfEnabled(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val app = when (pkg) {
            "com.whatsapp", "com.whatsapp.w4b" -> ChatApp.WHATSAPP
            "org.telegram.messenger" -> ChatApp.TELEGRAM
            else -> return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.joinToString(" ")?.trim().orEmpty()
                // Box cleared in one go after holding text → the message was sent
                // (deleting with backspace shrinks it one character at a time instead)
                // …but only if the text then shows up as a bubble (select-all + delete also clears it)
                if (text.isEmpty() && draft.length >= 3 && event.beforeText?.length?.let { it >= 2 } != false) {
                    val sent = draft
                    val sentPkg = pkg
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (visibleMessages(sentPkg).takeLast(3).any { it == sent.trim() }) onSent(sent, app)
                    }, 1_500)
                }
                draft = text
                draftPackage = pkg
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val id = event.source?.viewIdResourceName.orEmpty()
                val desc = event.contentDescription?.toString().orEmpty()
                if ((id.endsWith(":id/send") || desc.equals("Send", ignoreCase = true)) && draft.length >= 3 && draftPackage == pkg) {
                    onSent(draft, app)
                    draft = ""
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (app == ChatApp.WHATSAPP) scanOpenChat(pkg)
            }
        }
    }

    private fun onSent(text: String, app: ChatApp) {
        val chat = currentChatName()
        val key = "${chat}|${text.trim()}"
        if (seen.put(key, true) != null) return
        val context = visibleMessages(draftPackage).filter { it != text.trim() }.takeLast(4)
        val msg = ChatMessage(text, app, outgoing = true, chatName = chat, sender = null, isGroup = currentChatIsGroup(), recentMessages = context)
        scope.launch { processor.process(msg) }
    }

    /** Texts of the message bubbles currently on screen, oldest first (context for "it"/"that"). */
    private fun visibleMessages(pkg: String): List<String> = try {
        rootInActiveWindow?.findAccessibilityNodeInfosByViewId("$pkg:id/message_text")
            ?.mapNotNull { it.text?.toString()?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    /** Reads new incoming messages from the open WhatsApp chat (no notification is posted for them). */
    private fun scanOpenChat(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastScan < 800) return
        lastScan = now

        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        val chat = findText(root, "$pkg:id/conversation_contact_name") ?: return  // not in a chat
        val bubbles = root.findAccessibilityNodeInfosByViewId("$pkg:id/message_text") ?: return
        val firstLook = chat != openChat
        openChat = chat
        // On opening a chat, messages that arrived since it was last open are new to PAA — e.g. a
        // locked chat, whose notifications hide the text. Older ones are history.
        val prefs = getSharedPreferences("chat_last_open", MODE_PRIVATE)
        val since = if (firstLook) maxOf(prefs.getLong(chat, 0L), now - 12 * 3_600_000L) else now - 3 * 60_000L
        prefs.edit().putLong(chat, now).apply()
        val isGroup = root.findAccessibilityNodeInfosByViewId("$pkg:id/name_in_group_tv")?.isNotEmpty() == true

        val allTexts = bubbles.mapNotNull { it.text?.toString()?.trim() }
        for (node in bubbles.takeLast(if (firstLook) 10 else 6)) {
            val text = node.text?.toString()?.trim() ?: continue
            val bubble = node.parent?.parent ?: node.parent ?: continue
            val key = "$chat|$text"
            if (seen.containsKey(key)) continue
            seen[key] = true
            // Sent-tick marker = our own message (handled by onSent)
            if (bubble.findAccessibilityNodeInfosByViewId("$pkg:id/status")?.isNotEmpty() == true) continue
            // Only messages that just arrived
            val time = findText(bubble, "$pkg:id/date")?.let(::parseClockTime) ?: continue
            if (time < since - 60_000L || time - now > 60_000L) continue

            val sender = if (isGroup) findText(bubble, "$pkg:id/name_in_group_tv") else chat
            val context = allTexts.takeWhile { it != text }.takeLast(4)
            val msg = ChatMessage(text, ChatApp.WHATSAPP, outgoing = false, chatName = chat, sender = sender, isGroup = isGroup, recentMessages = context)
            scope.launch { processor.process(msg) }
        }
    }

    private fun findText(node: AccessibilityNodeInfo, id: String): String? = try {
        node.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** Name of the open chat, read ONLY from the conversation header view. */
    private fun currentChatName(): String? = try {
        rootInActiveWindow?.let { root ->
            CHAT_HEADER_IDS.firstNotNullOfOrNull { id -> findText(root, id) }
        }
    } catch (e: Exception) {
        null
    }

    private fun currentChatIsGroup(): Boolean = try {
        rootInActiveWindow?.let { root ->
            GROUP_SENDER_IDS.any { root.findAccessibilityNodeInfosByViewId(it)?.isNotEmpty() == true }
        } ?: false
    } catch (e: Exception) {
        false
    }

    /** "10:32 pm" / "22:32" (today) → timestamp. */
    private fun parseClockTime(s: String): Long? {
        val clean = s.replace(' ', ' ').trim()
        for (pattern in listOf("h:mm a", "hh:mm a", "HH:mm")) {
            try {
                val parsed = SimpleDateFormat(pattern, Locale.ENGLISH).parse(clean) ?: continue
                val t = Calendar.getInstance().apply { time = parsed }
                return Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, t.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, t.get(Calendar.MINUTE))
                    set(Calendar.SECOND, 0)
                }.timeInMillis
            } catch (_: Exception) {}
        }
        return null
    }

    companion object {
        private val CHAT_HEADER_IDS = listOf(
            "com.whatsapp:id/conversation_contact_name",
            "com.whatsapp.w4b:id/conversation_contact_name"
        )
        private val GROUP_SENDER_IDS = listOf(
            "com.whatsapp:id/name_in_group_tv",
            "com.whatsapp.w4b:id/name_in_group_tv"
        )
    }
}

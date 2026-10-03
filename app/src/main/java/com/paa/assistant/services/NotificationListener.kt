package com.paa.assistant.services

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * NotificationListener — reads incoming WhatsApp / Telegram / Gmail notifications and hands each
 * NEW message to [ChatMessageProcessor] (on-device only).
 *
 * Note: WhatsApp posts no notification for the chat that is currently open on screen; those
 * messages are read by [ChatTaskAccessibilityService] instead.
 */
@AndroidEntryPoint
class NotificationListener : NotificationListenerService() {

    @Inject lateinit var processor: ChatMessageProcessor
    @Inject lateinit var callRecordings: CallRecordingProcessor

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Newest message time already processed, per conversation (chat apps re-post old messages).
     * Saved, so a restart doesn't re-read the last 10 minutes; keys are hashed (no chat names on disk).
     */
    private val lastSeen by lazy { getSharedPreferences("notif_last_seen", MODE_PRIVATE) }
    private fun seenKey(key: String) = key.hashCode().toString()

    // Gmail is not watched: almost all of it is promotions/newsletters, not personal tasks
    private val apps = mapOf(
        "com.whatsapp" to ChatApp.WHATSAPP,
        "com.whatsapp.w4b" to ChatApp.WHATSAPP,
        "org.telegram.messenger" to ChatApp.TELEGRAM
    )

    /** App-generated notification texts that aren't messages from a person. */
    private val systemText = Regex(
        "^(\\d+ (new )?messages?( from \\d+ chats)?|\\d+ missed (voice |video )?calls?|missed (voice |video )?call|" +
            "(ongoing|incoming) (voice |video )?call|calling…?|ringing…?|checking for new messages|" +
            "whatsapp web is currently active|backup in progress.*|this message was deleted|" +
            "(📷|🎥|🎤|📄|🖼️|🎵)?\\s*(photo|video|voice message|audio|document|sticker|gif)( \\(\\d+:\\d+\\))?)$",
        RegexOption.IGNORE_CASE
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        DetectionLog.init(this)
        DetectionLog.add("system", "Notification access", "✅ connected — incoming messages will be read")
        // This service stays bound by Android, so it's the reliable place to watch for call recordings
        callRecordings.watch(scope)
        scope.launch { callRecordings.scanNew() }
    }

    override fun onListenerDisconnected() {
        instance = null
        DetectionLog.add("system", "Notification access", "❌ disconnected by Android (battery saver?)")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val app = apps[sbn.packageName] ?: return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return  // "5 messages from 2 chats"
        // Calls, "WhatsApp Web is active", backups, uploads… are not messages
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.category != null && notification.category != Notification.CATEGORY_MESSAGE) {
            DetectionLog.add("system", sbn.packageName, "ignored notification (category: ${notification.category})")
            return
        }

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || conversationTitle != null
        val chatName = cleanChatName(conversationTitle ?: title)
        val key = "${sbn.packageName}|${chatName ?: sbn.key}"

        // The owner's own replies (from the notification or another device) are in the list too
        val selfName = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
            ?: (extras.get(Notification.EXTRA_MESSAGING_PERSON) as? android.app.Person)?.name?.toString()
        val messages = readMessagingStyle(extras)
        val newMessages = if (messages.isNotEmpty()) {
            val since = synchronized(lastSeen) { lastSeen.getLong(seenKey(key), System.currentTimeMillis() - 10 * 60_000L) }
            messages.filter { it.time > since }.also { fresh ->
                fresh.maxOfOrNull { it.time }?.let { t -> synchronized(lastSeen) { lastSeen.edit().putLong(seenKey(key), t).apply() } }
            }
        } else {
            // Apps without MessagingStyle (e.g. Gmail): one message = the notification text
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString() ?: return
            listOf(Msg(body, title, sbn.postTime, fromSelf = false))
        }

        for (m in newMessages) {
            if (m.text.isBlank() || systemText.containsMatchIn(m.text.trim())) continue
            val mine = m.fromSelf || (selfName != null && m.sender == selfName)
            val msg = ChatMessage(
                text = m.text,
                app = app,
                outgoing = mine,
                chatName = chatName,
                sender = if (mine) null else if (isGroup) m.sender else chatName,
                isGroup = isGroup
            )
            scope.launch { processor.process(msg) }
        }
    }

    /** [fromSelf]: MessagingStyle marks the owner's own messages with no sender. */
    private data class Msg(val text: String, val sender: String?, val time: Long, val fromSelf: Boolean)

    /** Reads Notification.MessagingStyle messages (text, sender, time) from the extras. */
    private fun readMessagingStyle(extras: Bundle): List<Msg> {
        @Suppress("DEPRECATION")
        val parcels: Array<Parcelable> = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        return parcels.mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            val person = b.get("sender_person") as? android.app.Person
            val sender = b.getCharSequence("sender")?.toString() ?: person?.name?.toString()
            Msg(text, sender, b.getLong("time"), fromSelf = b.getCharSequence("sender") == null && person == null)
        }
    }

    companion object {
        /** The connected listener, for replying through a chat's notification (WhatsAppSender). */
        @Volatile var instance: NotificationListener? = null
    }

    /** "hackstreet_Boys (3 messages)" → "hackstreet_Boys"; "Group: Rahul" → "Group". */
    private fun cleanChatName(name: String?): String? = name
        ?.replace(Regex("\\s*\\(\\d+ (?:new )?messages?\\)\\s*$"), "")
        ?.substringBefore(": ")
        ?.trim()
        ?.ifBlank { null }
}

package com.paa.assistant.core.skills

import android.content.Context
import com.paa.assistant.core.agent.Action
import com.paa.assistant.core.agent.ActionResult
import com.paa.assistant.core.agent.Skill
import com.paa.assistant.core.messaging.Contact
import com.paa.assistant.core.messaging.WhatsAppSender
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WhatsApp messages. `contactId` is the contact's E.164 number; blank = no number known (a chat seen only in a
 * notification), so the reply goes through that chat's notification.
 */
@Singleton
class MessageSkill @Inject constructor(@ApplicationContext private val ctx: Context) : Skill<Action.SendMessage> {

    override suspend fun run(action: Action.SendMessage): ActionResult {
        if (action.app != "WhatsApp") return ActionResult(false, "Only WhatsApp for now.")
        if (action.contactId.isBlank()) {
            val auto = action.body == WhatsAppSender.AUTO_REPLY_TEXT
            val sent = WhatsAppSender.replyInChat(
                ctx, action.contactName, action.body,
                if (auto) "📤 auto-reply (you were on a call)" else "📤 sent by PAA"
            )
            return if (sent) ActionResult(true, "📤 Sent to ${action.contactName}.") else ActionResult(false, "Couldn't send it.")
        }
        val contact = Contact(action.contactName, action.contactId)
        return when (WhatsAppSender.send(ctx, contact, action.body)) {
            WhatsAppSender.Result.SENT -> ActionResult(true, "📤 Sent to ${contact.name}.")
            WhatsAppSender.Result.OPENED_FOR_TAP ->
                ActionResult(true, "Your phone is locked — tap the notification to send it to ${contact.name}.")
            WhatsAppSender.Result.LIMIT_REACHED ->
                ActionResult(false, "I've already sent ${WhatsAppSender.DAILY_LIMIT} messages today — that's the daily limit.")
            WhatsAppSender.Result.FAILED -> ActionResult(false, "Couldn't send it.")
        }
    }
}

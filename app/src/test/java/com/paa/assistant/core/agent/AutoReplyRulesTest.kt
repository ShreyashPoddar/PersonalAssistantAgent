package com.paa.assistant.core.agent

import com.paa.assistant.core.messaging.WhatsAppSender
import com.paa.assistant.data.models.ContactRuleEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoReplyRulesTest {

    private fun autoReply(chat: String) = Action.SendMessage("WhatsApp", "", chat, WhatsAppSender.AUTO_REPLY_TEXT)

    @Test
    fun autoReplyDeniedWithoutRule() {
        val v = Guard.decide(autoReply("Papa"), CommandSource.SYSTEM_EVENT, false, false, false, true, null)
        assertEquals(Verdict.Deny("Messages only on the owner's command"), v)
        val noFlag = ContactRuleEntity("papa", outward = "AUTO", messagedBefore = true)
        assertEquals(v, Guard.decide(autoReply("Papa"), CommandSource.SYSTEM_EVENT, false, false, false, true, noFlag))
    }

    @Test
    fun settingSyncsIntoRules() = runBlocking {
        val rules = FakeRules()
        rules.upsert(ContactRuleEntity("riya", outward = "AUTO", messagedBefore = true, autoReplyDuringCall = true))
        AutoReplyRules.sync(rules, listOf(" Papa ", "Mummy"))
        assertEquals(true, rules.map["papa"]?.autoReplyDuringCall)
        assertEquals(true, rules.map["mummy"]?.autoReplyDuringCall)
        // Removed from the setting: flag off, the rest of the rule kept
        assertEquals(false, rules.map["riya"]?.autoReplyDuringCall)
        assertEquals("AUTO", rules.map["riya"]?.outward)
        val v = Guard.decide(autoReply("Papa"), CommandSource.SYSTEM_EVENT, false, false, false, true, rules.map["papa"])
        assertEquals(Verdict.Allow, v)
    }
}

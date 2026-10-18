package com.paa.assistant.core.agent

import com.paa.assistant.core.agent.CommandSource.OWNER_UI
import com.paa.assistant.core.agent.CommandSource.OWNER_VOICE
import com.paa.assistant.core.agent.CommandSource.SCHEDULE
import com.paa.assistant.core.agent.CommandSource.SYSTEM_EVENT
import com.paa.assistant.data.models.ContactRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GuardTest {

    private val msg = Action.SendMessage("WhatsApp", "id1", "Ahana", "hi")
    private val sms = Action.SendSms("+911234567890", "Ahana", "hi")
    private val alarm = Action.SetAlarm(0L, "Wake up")
    private val open = Action.OpenApp("com.whatsapp", "WhatsApp")
    private val order = Action.PlaceOrder("Swiggy", "1 biryani", 12345)
    private val sos = Action.Sos("fall")

    private fun rule(outward: String = "PREVIEW", before: Boolean = true, autoReply: Boolean = false) =
        ContactRuleEntity("ahana", outward = outward, messagedBefore = before, autoReplyDuringCall = autoReply)

    private data class Case(
        val name: String, val a: Action, val s: CommandSource, val expected: Verdict,
        val pre: Boolean = false, val lockdown: Boolean = false, val paused: Boolean = false,
        val voiceLock: Boolean = true, val rule: ContactRuleEntity? = null
    )

    private val allow = Verdict.Allow
    private val tap = Verdict.Confirm(biometric = false)
    private val finger = Verdict.Confirm(biometric = true)

    private val cases = listOf(
        // 1. SOS
        Case("SOS from voice", sos, OWNER_VOICE, allow),
        Case("SOS from UI even when paused + lockdown", sos, OWNER_UI, allow, paused = true, lockdown = true),
        Case("SOS from SYSTEM_EVENT denied", sos, SYSTEM_EVENT, Verdict.Deny("SOS only from the owner")),
        Case("SOS from SCHEDULE denied", sos, SCHEDULE, Verdict.Deny("SOS only from the owner")),
        // 2. paused
        Case("paused blocks alarm", alarm, OWNER_UI, Verdict.Deny("PAA is paused"), paused = true),
        Case("paused blocks open app", open, OWNER_UI, Verdict.Deny("PAA is paused"), paused = true),
        // 3. lockdown
        Case("lockdown blocks message", msg, OWNER_UI, Verdict.Deny("Lockdown is on"), lockdown = true, rule = rule("AUTO")),
        Case("lockdown blocks order", order, OWNER_UI, Verdict.Deny("Lockdown is on"), lockdown = true),
        Case("lockdown allows alarm", alarm, OWNER_UI, allow, lockdown = true),
        // 4/5. READ / PRIVATE_REVERSIBLE
        Case("open app from chat", open, SYSTEM_EVENT, allow),
        Case("alarm from chat", alarm, SYSTEM_EVENT, allow),
        Case("alarm from schedule", alarm, SCHEDULE, allow),
        // 6. OUTWARD
        Case("chat-triggered message denied", msg, SYSTEM_EVENT, Verdict.Deny("Messages only on the owner's command"), rule = rule("AUTO")),
        Case("call auto-reply allowed", msg, SYSTEM_EVENT, allow, rule = rule(autoReply = true)),
        Case("auto-reply flag does not cover SMS", sms, SYSTEM_EVENT, Verdict.Deny("Messages only on the owner's command"), rule = rule(autoReply = true)),
        Case("first message, no rule", msg, OWNER_UI, finger),
        Case("first message, rule not messaged before", msg, OWNER_UI, finger, rule = rule("AUTO", before = false)),
        Case("ALWAYS_ASK", msg, OWNER_UI, finger, pre = true, rule = rule("ALWAYS_ASK")),
        Case("AUTO", msg, OWNER_UI, allow, rule = rule("AUTO")),
        Case("PREVIEW not pre-confirmed", msg, OWNER_UI, tap, rule = rule("PREVIEW")),
        Case("PREVIEW pre-confirmed", msg, SCHEDULE, allow, pre = true, rule = rule("PREVIEW")),
        Case("voice, no voice lock, AUTO -> fingerprint", msg, OWNER_VOICE, finger, voiceLock = false, rule = rule("AUTO")),
        Case("voice, no voice lock, PREVIEW -> fingerprint", msg, OWNER_VOICE, finger, voiceLock = false, rule = rule()),
        Case("voice with voice lock, AUTO", msg, OWNER_VOICE, allow, rule = rule("AUTO")),
        Case("voice with voice lock, PREVIEW", msg, OWNER_VOICE, tap, rule = rule()),
        // 7. MONEY
        Case("order from UI", order, OWNER_UI, finger),
        Case("order pre-confirmed still fingerprint", order, OWNER_VOICE, finger, pre = true),
        Case("order from schedule denied", order, SCHEDULE, Verdict.Deny("Payments only on the owner's command")),
        Case("order from chat denied", order, SYSTEM_EVENT, Verdict.Deny("Payments only on the owner's command")),
    )

    @Test
    fun policyTable() {
        for (c in cases) {
            assertEquals(c.name, c.expected, Guard.decide(c.a, c.s, c.pre, c.lockdown, c.paused, c.voiceLock, c.rule))
        }
    }

    @Test
    fun contactKeyIsNormalised() {
        assertEquals("ahana", Guard.contactKey(Action.SendMessage("WhatsApp", "id", "  Ahana ", "x")))
        assertEquals("+911234567890", Guard.contactKey(sms))
        assertEquals(null, Guard.contactKey(alarm))
    }
}

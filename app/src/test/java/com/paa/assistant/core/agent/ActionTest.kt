package com.paa.assistant.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTest {

    @Test
    fun everyActionHasItsDocumentedTier() {
        val expected = listOf(
            Action.OpenApp("com.whatsapp", "WhatsApp") to RiskTier.READ,
            Action.SetAlarm(0L, "Wake up") to RiskTier.PRIVATE_REVERSIBLE,
            Action.SendMessage("WhatsApp", "id1", "Ahana", "hi") to RiskTier.OUTWARD,
            Action.SendSms("+911234567890", "Ahana", "hi") to RiskTier.OUTWARD,
            Action.SendEmail("a@b.com", "Report", "body") to RiskTier.OUTWARD,
            Action.PlaceCall("+911234567890", "Ahana") to RiskTier.OUTWARD,
            Action.PlaceOrder("Swiggy", "1 biryani", 12345) to RiskTier.MONEY,
            Action.Sos("fall") to RiskTier.SOS,
        )
        for ((action, tier) in expected) assertEquals(action::class.simpleName, tier, action.tier)
        assertEquals(expected.size, expected.map { kind(it.first) }.toSet().size)
    }

    /** Exhaustive `when`: a new Action subclass fails to compile here until it is added to the tier table. */
    private fun kind(a: Action): Int = when (a) {
        is Action.OpenApp -> 0
        is Action.SetAlarm -> 1
        is Action.SendMessage -> 2
        is Action.SendSms -> 3
        is Action.SendEmail -> 4
        is Action.PlaceCall -> 5
        is Action.PlaceOrder -> 6
        is Action.Sos -> 7
    }

    @Test
    fun sendMessageSummaryShowsContactAndBody() {
        val s = Action.SendMessage("WhatsApp", "id1", "Ahana", "send the ppt by 3").summary()
        assertTrue(s, s.contains("Ahana"))
        assertTrue(s, s.contains("send the ppt by 3"))
    }

    @Test
    fun placeOrderSummaryShowsRupees() {
        val s = Action.PlaceOrder("Swiggy", "1 biryani", 12345).summary()
        assertTrue(s, s.contains("₹123.45"))
    }
}

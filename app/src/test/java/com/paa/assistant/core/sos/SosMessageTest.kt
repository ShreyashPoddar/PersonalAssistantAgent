package com.paa.assistant.core.sos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosMessageTest {
    private val at = 1_700_000_000_000L

    @Test fun withLocation() {
        val t = SosMessage.text("Shreyash", 12.823456, 80.044321, 25f, at, drill = false)
        assertTrue(t, t.startsWith("🆘 SOS from Shreyash."))
        assertTrue(t, "https://maps.google.com/?q=12.823456,80.044321" in t)
        assertTrue(t, "(±25 m)" in t)
    }

    @Test fun withoutLocationAndDrill() {
        val t = SosMessage.text("Shreyash", null, null, null, at, drill = true)
        assertTrue(t, t.startsWith("TEST — ignore: "))
        assertTrue(t, "Location unavailable" in t)
    }

    @Test fun triggers() {
        for (s in listOf("SOS", "emergency", "help me", "bachao!", "Oyee PA, madad karo")) assertTrue(s, SosMessage.isTrigger(s))
        for (s in listOf("help me with the assignment", "sos ka matlab kya hai", "emergency meeting kal 5 baje", "help Riya with notes"))
            assertFalse(s, SosMessage.isTrigger(s))
    }
}

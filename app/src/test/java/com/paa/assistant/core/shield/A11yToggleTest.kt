package com.paa.assistant.core.shield

import org.junit.Assert.assertEquals
import org.junit.Test

class A11yToggleTest {

    private val me = "com.paa.assistant/com.paa.assistant.services.ChatTaskAccessibilityService"
    private val gram = "com.grammarly.android.keyboard/com.grammarly.widget.service.GrammarlyAccessibilityService"

    @Test
    fun turnOnAppendsAndKeepsOthers() {
        assertEquals("$gram:${me}", A11yToggle.editList(gram, me, true))
    }

    @Test
    fun turnOnFromEmpty() {
        assertEquals(me, A11yToggle.editList(null, me, true))
        assertEquals(me, A11yToggle.editList("", me, true))
    }

    @Test
    fun turnOffRemovesShortAndLongForms() {
        assertEquals(gram, A11yToggle.editList("com.paa.assistant/.services.ChatTaskAccessibilityService:$gram", me, false))
        assertEquals(gram, A11yToggle.editList("$gram:${me}", me, false))
    }

    @Test
    fun containsBothForms() {
        assertEquals(true, A11yToggle.contains("$gram:com.paa.assistant/.services.ChatTaskAccessibilityService", me))
        assertEquals(false, A11yToggle.contains(gram, me))
    }

    @Test
    fun turnOnTwiceDoesNotDuplicate() {
        val once = A11yToggle.editList(gram, me, true)
        assertEquals(once, A11yToggle.editList(once, me, true))
    }

    @Test
    fun voicePhrases() {
        assertEquals(false, A11yToggle.voiceCommand("Stop reading my screen"))
        assertEquals(false, A11yToggle.voiceCommand("oyee pa, banking mode"))
        assertEquals(false, A11yToggle.voiceCommand("screen reading off."))
        assertEquals(true, A11yToggle.voiceCommand("Screen reading on"))
        assertEquals(true, A11yToggle.voiceCommand("banking mode off"))
        assertEquals(null, A11yToggle.voiceCommand("message riya about banking mode tomorrow"))
    }
}

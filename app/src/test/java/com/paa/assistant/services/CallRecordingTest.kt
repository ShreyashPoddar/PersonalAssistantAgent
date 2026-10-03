package com.paa.assistant.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRecordingTest {
    @Test fun `contact from iQOO file names`() {
        assertEquals("Alo", CallRecordingProcessor.contactOf("Alo 2026-10-02 19-46-01.m4a"))
        assertEquals("Zomato Order Support", CallRecordingProcessor.contactOf("Zomato Order Support  2025-06-14 20-44-50.m4a"))
    }

    @Test fun `long transcripts split into overlapping pieces`() {
        val t = (1..200).joinToString(" ") { "वाक्य नंबर $it है." }
        val p = CallRecordingProcessor.pieces(t)
        assertTrue(p.size > 1)
        assertTrue(p.all { it.length <= 1500 })
        assertTrue(p.joinToString(" ").contains("नंबर 200 है"))
    }
}

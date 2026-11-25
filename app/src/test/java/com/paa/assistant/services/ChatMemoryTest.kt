package com.paa.assistant.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryTest {
    private var clock = 0L
    private val min = 60_000L
    private val m = ChatMemory(windowMs = 30 * min, maxLines = 3, now = { clock })

    @Test fun piecesAreCarriedThenExpire() {
        m.keepCarry("stuti", listOf("Stuti: UPI nahi chal raha", "Stuti: QR bhejti hu"))
        clock = 10 * min
        assertEquals(listOf("Stuti: UPI nahi chal raha", "Stuti: QR bhejti hu"), m.takeCarry("stuti"))
        clock = 31 * min
        assertTrue(m.takeCarry("stuti").isEmpty())
    }

    @Test fun keepsFirstSeenTimeAndCaps() {
        m.keepCarry("c", listOf("a"))
        clock = 20 * min
        m.keepCarry("c", listOf("a", "b"))       // "a" keeps its time 0
        clock = 35 * min
        assertEquals(listOf("b"), m.takeCarry("c"))
        m.keepCarry("c", listOf("1", "2", "3", "4"))
        assertEquals(listOf("2", "3", "4"), m.takeCarry("c"))
    }

    @Test fun clearedAfterATask() {
        m.keepCarry("c", listOf("x"))
        m.clearCarry("c")
        assertTrue(m.takeCarry("c").isEmpty())
        assertTrue(m.takeCarry("other").isEmpty())
    }

    @Test fun groupFollowUps() {
        m.mentioned("flatmates", listOf("Arpit"))
        clock = 5 * min
        assertTrue(m.isFollowUp("flatmates", listOf("Arpit")))
        assertFalse(m.isFollowUp("flatmates", listOf("Suryansh")))
        assertFalse(m.isFollowUp("goa", listOf("Arpit")))
        clock = 31 * min
        assertFalse(m.isFollowUp("flatmates", listOf("Arpit")))
    }
}

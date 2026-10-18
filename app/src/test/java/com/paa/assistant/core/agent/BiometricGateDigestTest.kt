package com.paa.assistant.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricGateDigestTest {

    private fun d(a: Action, id: String = "req-1") = BiometricGate.sha256(BiometricGate.digestInput(a, id)).toList()

    @Test
    fun sameActionSameDigest() {
        assertEquals(d(Action.PlaceOrder("Swiggy", "1 biryani", 100)), d(Action.PlaceOrder("Swiggy", "1 biryani", 100)))
    }

    @Test
    fun amountChangesDigest() {
        assertNotEquals(d(Action.PlaceOrder("Swiggy", "1 biryani", 100)), d(Action.PlaceOrder("Swiggy", "1 biryani", 10000)))
    }

    @Test
    fun bodyChangesDigest() {
        assertNotEquals(
            d(Action.SendMessage("WhatsApp", "id", "Ahana", "hi")),
            d(Action.SendMessage("WhatsApp", "id", "Ahana", "hi!"))
        )
    }

    @Test
    fun requestIdChangesDigest() {
        val a = Action.Sos("fall")
        assertNotEquals(d(a, "req-1"), d(a, "req-2"))
    }

    @Test
    fun actionTypeIsPartOfInput() {
        assertTrue(BiometricGate.digestInput(Action.Sos("x"), "r").startsWith("r|Sos|"))
    }
}

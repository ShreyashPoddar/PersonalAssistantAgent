package com.paa.assistant.core.money

import com.paa.assistant.core.agent.Action
import com.paa.assistant.data.models.SavedOrderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedOrdersTest {
    private val order = Action.PlaceOrder("Swiggy", "2 idli, 1 filter coffee", 180_00L, "com.phonepe.app")

    @Test fun parsesSaveCommands() {
        assertEquals("breakfast", SavedOrders.parseSave("Save this as my usual breakfast"))
        assertEquals("breakfast", SavedOrders.parseSave("please remember that order as the usual breakfast order."))
        assertEquals("usual", SavedOrders.parseSave("save it as my usual"))
        assertNull(SavedOrders.parseSave("save this photo to drive"))
    }

    @Test fun parsesReorderCommands() {
        assertEquals("breakfast", SavedOrders.parseReorder("Order my usual breakfast"))
        assertEquals("usual", SavedOrders.parseReorder("order my usual"))
        assertEquals("lunch", SavedOrders.parseReorder("can you get me my usual lunch again please"))
        assertNull(SavedOrders.parseReorder("order a pizza"))
        assertNull(SavedOrders.parseReorder("what is my usual breakfast"))
    }

    @Test fun saveRefusesOrdersThatCannotBeHandedOff() {
        assertNull(SavedOrders.save(order.copy(amountPaise = 0), "x", 1L))
        assertNull(SavedOrders.save(order.copy(cartSummary = " "), "x", 1L))
        assertNull(SavedOrders.save(order.copy(amountPaise = PaymentHandoff.MAX_PAISE + 1), "x", 1L))
        assertEquals("breakfast", SavedOrders.save(order, " My Breakfast! ", 1L)!!.label)
    }

    @Test fun lookupExactThenSingleThenNear() {
        val b = SavedOrders.save(order, "breakfast", 1L)!!
        val l = SavedOrders.save(order.copy(cartSummary = "thali"), "office lunch", 2L)!!
        assertEquals(b, SavedOrders.find(listOf(b, l), "breakfast"))
        assertEquals(l, SavedOrders.find(listOf(b, l), "lunch"))
        assertNull(SavedOrders.find(listOf(b, l), "usual"))       // two saved, "my usual" is ambiguous
        assertEquals(b, SavedOrders.find(listOf(b), "usual"))     // only one saved → that one
        assertNull(SavedOrders.find(listOf(b, l), "dinner"))
        assertTrue(SavedOrders.notFound(listOf(b, l), "dinner").contains("breakfast"))
        assertTrue(SavedOrders.notFound(emptyList(), "dinner").contains("save this as my usual dinner"))
    }

    @Test fun rebuildKeepsTheCartAndSaysThePriceIsOld() {
        val saved = SavedOrders.save(order, "breakfast", 1L)!!
        val again = SavedOrders.rebuild(saved)
        assertEquals("Swiggy", again.app)
        assertEquals(180_00L, again.amountPaise)
        assertEquals("com.phonepe.app", again.upiApp)
        assertTrue(again.cartSummary.startsWith("2 idli, 1 filter coffee"))
        assertTrue(again.cartSummary.contains("last time's price"))
        // Round trip through the DB row keeps every field.
        assertEquals(saved, SavedOrderEntity.of(saved).toSaved())
    }
}

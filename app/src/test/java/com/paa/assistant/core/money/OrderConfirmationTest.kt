package com.paa.assistant.core.money

import com.paa.assistant.core.agent.Action
import com.paa.assistant.core.money.OrderConfirmation.Outcome
import com.paa.assistant.data.db.AuditDao
import com.paa.assistant.data.models.AuditEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderConfirmationTest {
    private val order = Action.PlaceOrder("Swiggy", "2 masala dosa", 24_900L, "com.phonepe.app")
    private val t0 = 1_000_000L
    private val pending = OrderConfirmation.pendingFor(order, t0)

    @Test
    fun watchesMerchantAndUpiApp() {
        assertEquals(setOf("in.swiggy.android", "com.phonepe.app"), pending.packages)
        assertEquals(setOf("com.example.shop"), OrderConfirmation.pendingFor(order.copy(app = "com.example.shop", upiApp = null), t0).packages)
        assertTrue(OrderConfirmation.pendingFor(order.copy(app = "Some Shop", upiApp = null), t0).packages.isEmpty())
    }

    @Test
    fun classifiesOutcomes() {
        assertEquals(Outcome.PAID, OrderConfirmation.classify("Order placed! Your food is being prepared"))
        assertEquals(Outcome.PAID, OrderConfirmation.classify("Paid ₹249 to Swiggy"))
        assertEquals(Outcome.FAILED, OrderConfirmation.classify("Payment failed. Any amount debited will be refunded"))
        assertEquals(Outcome.FAILED, OrderConfirmation.classify("Payment not successful"))
        assertEquals(Outcome.PENDING, OrderConfirmation.classify("Payment pending with your bank"))
        assertNull(OrderConfirmation.classify("50% off on your next order"))
    }

    @Test
    fun readsAmounts() {
        assertEquals(listOf(24_900L), OrderConfirmation.amountsPaise("Paid ₹249 to Swiggy"))
        assertEquals(listOf(1_23_456_50L), OrderConfirmation.amountsPaise("Rs. 1,23,456.5 paid"))
        assertEquals(listOf(9_900L), OrderConfirmation.amountsPaise("INR 99.00 debited"))
    }

    @Test
    fun matchNeedsAppWindowAndAmount() {
        assertEquals(Outcome.PAID, OrderConfirmation.match(pending, "com.phonepe.app", "Paid ₹249 to Swiggy", t0 + 60_000))
        assertEquals(Outcome.PAID, OrderConfirmation.match(pending, "in.swiggy.android", "Order confirmed", t0 + 60_000))
        assertEquals(Outcome.PAID, OrderConfirmation.match(pending, "com.phonepe.app", "Paid ₹249.50", t0 + 1))  // within ₹1
        assertNull(OrderConfirmation.match(pending, "com.whatsapp", "Order confirmed", t0 + 60_000))
        assertNull(OrderConfirmation.match(pending, "com.phonepe.app", "Paid ₹999 to Zomato", t0 + 60_000))
        assertNull(OrderConfirmation.match(pending, "com.phonepe.app", "Paid ₹249", t0 - 1))
        assertNull(OrderConfirmation.match(pending, "com.phonepe.app", "Paid ₹249", t0 + OrderConfirmation.WINDOW_MS + 1))
    }

    private class FakeAudit : AuditDao {
        val entries = mutableListOf<AuditEntryEntity>()
        override suspend fun insert(e: AuditEntryEntity): Long { entries += e; return entries.size.toLong() }
        override fun recent(limit: Int): Flow<List<AuditEntryEntity>> = flowOf(entries.toList())
        override suspend fun deleteOlderThan(time: Long) { entries.removeAll { it.at < time } }
    }

    @Test
    fun trackerLogsFromTypedFieldsAndStopsAfterFinalWord() = runBlocking {
        val audit = FakeAudit()
        var now = t0
        val tracker = OrderConfirmationTracker(audit) { now }
        assertFalse(tracker.watching("com.phonepe.app"))
        tracker.expect(order)
        assertTrue(tracker.watching("com.phonepe.app"))
        assertFalse(tracker.watching("com.whatsapp"))

        now += 1_000
        assertEquals(Outcome.PENDING, tracker.onNotification("com.phonepe.app", "Payment of ₹249 pending", now))
        assertTrue(tracker.watching("com.phonepe.app"))
        now += 1_000
        assertEquals(Outcome.PAID, tracker.onNotification("com.phonepe.app", "Secret note: paid ₹249 to Swiggy", now))
        assertFalse(tracker.watching("com.phonepe.app"))
        assertNull(tracker.onNotification("com.phonepe.app", "Paid ₹249", now))

        assertEquals(listOf("PENDING", "PAID"), audit.entries.map { it.verdict })
        assertTrue(audit.entries.last().ok)
        assertEquals("Order paid: Swiggy, ₹249", audit.entries.last().resultMessage)
        assertTrue(audit.entries.none { "Secret" in it.summary || "Secret" in it.resultMessage })
    }

    @Test
    fun trackerForgetsAfterWindow() {
        var now = t0
        val tracker = OrderConfirmationTracker(FakeAudit()) { now }
        tracker.expect(order)
        now += OrderConfirmation.WINDOW_MS + 1
        assertFalse(tracker.watching("com.phonepe.app"))
    }
}

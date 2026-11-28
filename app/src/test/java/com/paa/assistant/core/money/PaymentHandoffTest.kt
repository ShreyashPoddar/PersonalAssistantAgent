package com.paa.assistant.core.money

import com.paa.assistant.core.agent.Action
import com.paa.assistant.core.agent.ActionResult
import com.paa.assistant.core.agent.CommandSource
import com.paa.assistant.core.agent.Executor
import com.paa.assistant.core.agent.FakeRules
import com.paa.assistant.core.agent.Guard
import com.paa.assistant.core.agent.Skill
import com.paa.assistant.core.agent.Verdict
import com.paa.assistant.data.db.AuditDao
import com.paa.assistant.data.models.AuditEntryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentHandoffTest {
    private val order = Action.PlaceOrder("Swiggy", "1 biryani, 1 coke", 34950)
    private val gpay = "com.google.android.apps.nbu.paisa.user"

    @Test
    fun rupeesUseIndianGrouping() {
        assertEquals("₹349.50", PaymentHandoff.rupees(34950))
        assertEquals("₹500", PaymentHandoff.rupees(50000))
        assertEquals("₹1,23,456.07", PaymentHandoff.rupees(12345607))
        assertEquals("₹0.05", PaymentHandoff.rupees(5))
    }

    @Test
    fun sheetShowsSummaryAmountAndPinNote() {
        val s = PaymentHandoff.sheetText(order)
        assertTrue(s.contains("Swiggy: 1 biryani, 1 coke"))
        assertTrue(s.contains("₹349.50"))
        assertTrue(s.endsWith(PaymentHandoff.PIN_NOTE))
    }

    @Test
    fun opensOnlyKnownInstalledUpiApps() {
        val ok = PaymentHandoff.plan(order, gpay) { true }
        assertTrue(ok is PaymentHandoff.Step.OpenUpi)
        assertEquals("Google Pay", (ok as PaymentHandoff.Step.OpenUpi).label)
        assertTrue(ok.message.contains("₹349.50"))
        assertTrue(PaymentHandoff.plan(order, "com.axis.mobile") { true } is PaymentHandoff.Step.Refuse)
        assertTrue(PaymentHandoff.plan(order, gpay) { false } is PaymentHandoff.Step.Refuse)
    }

    @Test
    fun noUpiAppStaysInMerchant() {
        val s = PaymentHandoff.plan(order, null) { true }
        assertTrue(s is PaymentHandoff.Step.StayInMerchant)
        assertTrue((s as PaymentHandoff.Step.StayInMerchant).message.contains(PaymentHandoff.PIN_NOTE))
    }

    @Test
    fun refusesBadAmountsAndEmptyCarts() {
        assertTrue(PaymentHandoff.plan(order.copy(amountPaise = 0), null) { true } is PaymentHandoff.Step.Refuse)
        assertTrue(PaymentHandoff.plan(order.copy(amountPaise = -100), null) { true } is PaymentHandoff.Step.Refuse)
        assertTrue(PaymentHandoff.plan(order.copy(amountPaise = PaymentHandoff.MAX_PAISE + 1), null) { true } is PaymentHandoff.Step.Refuse)
        assertTrue(PaymentHandoff.plan(order.copy(cartSummary = " "), null) { true } is PaymentHandoff.Step.Refuse)
    }

    // --- reachability: the hand-off only runs for an owner command confirmed with a fingerprint ---

    private class FakeAudit : AuditDao {
        val entries = mutableListOf<AuditEntryEntity>()
        override suspend fun insert(e: AuditEntryEntity): Long { entries += e; return entries.size.toLong() }
        override fun recent(limit: Int): Flow<List<AuditEntryEntity>> = flowOf(entries.toList())
        override suspend fun deleteOlderThan(time: Long) { entries.removeAll { it.at < time } }
    }

    private class CountingSkill : Skill<Action> {
        var runs = 0
        override suspend fun run(action: Action): ActionResult { runs++; return ActionResult(true, "handed off") }
    }

    private fun run(source: CommandSource, approve: Boolean, lockdown: Boolean = false): Triple<Boolean, Int, Boolean?> {
        val skill = CountingSkill()
        var askedBiometric: Boolean? = null
        val ex = Executor(
            check = { a, s, pre, voice -> Guard.decide(a, s, pre, lockdown, false, voice, null) },
            ask = { _, _, bio -> askedBiometric = bio; approve },
            skillFor = { skill }, audit = FakeAudit(), rules = FakeRules(), log = { _, _, _ -> }, now = { 1L }
        )
        val r = runBlocking { ex.submit(order, source, preConfirmed = true, voiceVerified = true) }
        return Triple(r.ok, skill.runs, askedBiometric)
    }

    @Test
    fun ownerOrderNeedsFingerprintEvenWhenPreConfirmed() {
        val (ok, runs, bio) = run(CommandSource.OWNER_UI, approve = true)
        assertTrue(ok); assertEquals(1, runs); assertEquals(true, bio)
    }

    @Test
    fun cancelledOrNonOwnerOrLockdownNeverHandsOff() {
        assertEquals(0, run(CommandSource.OWNER_VOICE, approve = false).second)
        for (s in listOf(CommandSource.SCHEDULE, CommandSource.SYSTEM_EVENT)) {
            val (ok, runs, bio) = run(s, approve = true)
            assertFalse(ok); assertEquals(0, runs); assertEquals(null, bio)
        }
        assertEquals(0, run(CommandSource.OWNER_UI, approve = true, lockdown = true).second)
    }

    @Test
    fun verdictForOrdersIsAlwaysBiometric() {
        assertEquals(Verdict.Confirm(biometric = true), Guard.decide(order, CommandSource.OWNER_VOICE, true, false, false, true, null, trustAll = true))
    }
    @Test
    fun capWarnsOnlyAboveTheLimit() {
        val cap = PaymentHandoff.DEFAULT_CAP_PAISE
        assertEquals(null, PaymentHandoff.capWarning(cap, cap))
        assertFalse(PaymentHandoff.overCap(34950, cap))
        assertTrue(PaymentHandoff.overCap(cap + 1, cap))
        assertEquals(
            "₹2,500 is above your ₹2,000 limit. Check the amount, then confirm again.",
            PaymentHandoff.capWarning(2_500_00, cap)
        )
    }

    @Test
    fun capCanBeRaisedButNeverTurnedOff() {
        assertFalse(PaymentHandoff.overCap(4_000_00, 5_000_00))
        assertTrue(PaymentHandoff.overCap(5_000_01, 5_000_00))
        // 0 / negative falls back to the default instead of meaning "no cap".
        assertTrue(PaymentHandoff.overCap(2_000_01, 0))
        assertTrue(PaymentHandoff.overCap(2_000_01, -1))
    }

    @Test
    fun capTypedInRupees() {
        assertEquals(5_000_00L, PaymentHandoff.parseCapRupees("5000"))
        assertEquals(5_000_00L, PaymentHandoff.parseCapRupees(" ₹5,000 "))
        assertEquals(5_000_00L, PaymentHandoff.parseCapRupees("5,000 rs"))
        assertEquals(1_00_000_00L, PaymentHandoff.parseCapRupees("1,00,000"))
        assertEquals(null, PaymentHandoff.parseCapRupees("0"))
        assertEquals(null, PaymentHandoff.parseCapRupees("50"))
        assertEquals(null, PaymentHandoff.parseCapRupees("200000"))
        assertEquals(null, PaymentHandoff.parseCapRupees("12.5"))
        assertEquals(null, PaymentHandoff.parseCapRupees("-500"))
        assertEquals(null, PaymentHandoff.parseCapRupees(""))
    }
}

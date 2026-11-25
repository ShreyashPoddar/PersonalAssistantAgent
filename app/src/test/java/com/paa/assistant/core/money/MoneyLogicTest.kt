package com.paa.assistant.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MoneyLogicTest {
    private val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 20, 12, 0, 0) }.timeInMillis
    private val day = 86_400_000L
    private fun t(id: String, rs: Long, dir: String = "DEBIT", cp: String? = null, ch: String = "UPI", at: Long = now - day) =
        Txn(id, at, rs * 100, dir, ch, cp, null, null, "AXIS", "SMS")

    // ── Categorizer ──
    @Test fun keywordFamilies() {
        assertEquals("Food", Categorizer.categorize(t("a", 1, cp = "SWIGGY"), emptyMap()))
        assertEquals("Groceries", Categorizer.categorize(t("a", 1, cp = "Blinkit"), emptyMap()))
        assertEquals("Travel", Categorizer.categorize(t("a", 1, cp = "IRCTC WEB"), emptyMap()))
        assertEquals("Shopping", Categorizer.categorize(t("a", 1, cp = "MYNTRA DESIGNS"), emptyMap()))
        assertEquals("Bills", Categorizer.categorize(t("a", 1, cp = "Jio Prepaid Recharge"), emptyMap()))
        assertEquals("Cash", Categorizer.categorize(t("a", 1, ch = "ATM"), emptyMap()))
        assertEquals("Transfer", Categorizer.categorize(t("a", 1, cp = "riya.k99@okaxis"), emptyMap()))
        assertEquals("Transfer", Categorizer.categorize(t("a", 1, cp = "Suryansh Pandey"), emptyMap()))
        assertEquals("Uncategorised", Categorizer.categorize(t("a", 1, cp = "XYZ ENTERPRISES PVT LTD"), emptyMap()))
    }

    @Test fun ownerRuleWins() {
        val rules = mapOf(Categorizer.key("riya.k99@okaxis") to "Rent")
        assertEquals("Rent", Categorizer.categorize(t("a", 1, cp = "riya.k99@okaxis"), rules))
        assertEquals(Categorizer.key("SWIGGY ORDER 12345"), Categorizer.key("Swiggy order 999"))
    }

    // ── Ledger ──
    @Test fun creditSuggestsSettling() {
        val iou = Iou(1, "Rahul", 50000, "OWED_TO_ME", "dinner", now - 3 * day)
        assertEquals(iou, Ledger.match(t("c", 500, "CREDIT", "rahul.m@ybl", at = now), listOf(iou)))
        assertNull(Ledger.match(t("c", 500, "DEBIT", "rahul.m@ybl", at = now), listOf(iou)))   // wrong direction
        assertNull(Ledger.match(t("c", 499, "CREDIT", "rahul.m@ybl", at = now), listOf(iou)))  // wrong amount
        assertNull(Ledger.match(t("c", 500, "CREDIT", "rahul.m@ybl", at = now + 10 * day), listOf(iou)))  // too late
        assertNull(Ledger.match(t("c", 500, "CREDIT", "priya@okhdfc", at = now), listOf(iou)))  // someone else
    }

    @Test fun notes() {
        assertEquals(Triple("Rahul", 50000L, "OWED_TO_ME"), Ledger.parseNote("note that Rahul owes me 500 for dinner"))
        assertEquals(Triple("Riya", 20000L, "I_OWE"), Ledger.parseNote("I owe Riya 200"))
        assertEquals(Triple("Arpit", 150000L, "OWED_TO_ME"), Ledger.parseNote("Oyee PA, Arpit se 1,500 lene hai"))
        assertNull(Ledger.parseNote("send 500 to Rahul"))
    }

    // ── Questions ──
    private val txns = listOf(
        t("1", 245, cp = "SWIGGY"), t("2", 800, cp = "Blinkit"), t("3", 1500, cp = "IRCTC"),
        t("4", 600, "CREDIT", "arpit@ybl"), t("5", 9999, cp = "MYNTRA", at = now - 40 * day)
    )
    private val cats = txns.associate { it.id to Categorizer.categorize(it, emptyMap()) }

    @Test fun spendingThisMonthAndByCategory() {
        val a = MoneyQuery.answer("how much did I spend this month", txns, cats, emptyList(), now)!!
        assertTrue(a, "₹2,545" in a)  // 245 + 800 + 1500 (credit and October excluded)
        val f = MoneyQuery.answer("food pe kitna kharcha hua", txns, cats, emptyList(), now)!!
        assertTrue(f, "₹245" in f)
    }

    @Test fun biggestAndOwed() {
        assertTrue(MoneyQuery.answer("biggest expense this month", txns, cats, emptyList(), now)!!.contains("IRCTC"))
        val ious = listOf(Iou(1, "Rahul", 50000, "OWED_TO_ME", "dinner", now - day))
        assertTrue(MoneyQuery.answer("did Rahul pay me back", txns, cats, ious, now)!!.contains("Rahul owes you ₹500"))
        assertNotNull(MoneyQuery.answer("who owes me money", txns, cats, ious, now))
    }

    @Test fun detection() {
        assertTrue(MoneyQuery.isMoneyQuestion("kitna kharcha hua is hafte"))
        assertFalse(MoneyQuery.isMoneyQuestion("send 500 to Rahul"))
        assertFalse(MoneyQuery.isMoneyQuestion("remind me to call mom"))
        assertFalse(MoneyQuery.isMoneyQuestion("kal paise bhejne hai yaad dilana"))
    }
}

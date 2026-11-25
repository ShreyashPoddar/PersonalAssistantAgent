package com.paa.assistant.core.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic messages only (invented amounts, VPAs and refs, shaped like common Indian bank alerts). */
class BankSmsParserTest {
    private val at = 1_763_000_000_000L
    private fun p(t: String, s: String = "AX-AXISBK", src: String = "SMS") = BankSmsParser.parse(t, s, at, src)

    @Test fun upiDebit() {
        val x = p("INR 245.00 debited from A/c no. XX1234 on 12-11-26 for UPI/P2M/312345678901/SWIGGY. UPI Ref 312345678901. Not you? Call 18001035577")!!
        assertEquals(24500L, x.amountPaise); assertEquals("DEBIT", x.direction); assertEquals("UPI", x.channel)
        assertEquals("312345678901", x.refId); assertEquals("AXIS", x.bank)
    }

    @Test fun upiDebitToVpa() {
        val x = p("Rs.1,500.00 debited from your A/c XX9876 to VPA riya.k99@okaxis on 05-11-26. UPI Ref No 998877665544. Avl Bal Rs.12,340.50")!!
        assertEquals(150000L, x.amountPaise); assertEquals("riya.k99@okaxis", x.counterparty); assertEquals(1234050L, x.balancePaise)
    }

    @Test fun credit() {
        val x = p("INR 600.00 credited to A/c no. XX1234 on 03-11-26 by UPI from arpit.s@ybl. UPI Ref 445566778899")!!
        assertEquals("CREDIT", x.direction); assertEquals(60000L, x.amountPaise); assertEquals("arpit.s@ybl", x.counterparty)
    }

    @Test fun card() {
        val x = p("Spent INR 799.00 on Axis Bank Card no. XX4321 at MYNTRA DESIGNS on 10-11-26. Avl Limit: INR 45,201.00", "VM-AXISBK")!!
        assertEquals("CARD", x.channel); assertEquals("MYNTRA DESIGNS", x.counterparty); assertEquals(79900L, x.amountPaise)
    }

    @Test fun atmAndNeft() {
        assertEquals("ATM", p("INR 2,000.00 withdrawn at ATM AXIS CHENNAI on 01-11-26 from A/c XX1234. Avl Bal INR 8,000.00")!!.channel)
        val n = p("INR 7,500.00 debited from A/c XX1234 by NEFT to SURYANSH PANDEY on 06-11-26. Ref NEFT123456789", "JD-HDFCBK")!!
        assertEquals("NEFT", n.channel); assertEquals("HDFC", n.bank)
    }

    @Test fun upiAppNotification() {
        val x = p("Paid ₹120 to Chai Point", "com.google.android.apps.nbu.paisa.user", "NOTIF")!!
        assertEquals(12000L, x.amountPaise); assertEquals("DEBIT", x.direction); assertEquals("Chai Point", x.counterparty)
        assertEquals("UPI", x.bank)
    }

    @Test fun upiAppWordings() {
        val a = p("Payment of ₹245 to Swiggy successful", "com.phonepe.app", "NOTIF")!!
        assertEquals("DEBIT", a.direction); assertEquals(24500L, a.amountPaise); assertEquals("Swiggy", a.counterparty)
        val b = p("Received ₹500 from Rahul Mehta", "com.google.android.apps.nbu.paisa.user", "NOTIF")!!
        assertEquals("CREDIT", b.direction); assertEquals("Rahul Mehta", b.counterparty)
        assertNull(p("You won ₹10 cashback on your payment", "com.google.android.apps.nbu.paisa.user", "NOTIF"))
    }

    @Test fun notTransactions() {
        assertNull(p("123456 is your OTP for a transaction of INR 2,000.00 at AMAZON. Do not share."))
        assertNull(p("Get up to Rs.50,000 pre-approved personal loan! Apply now"))
        assertNull(p("Transaction of INR 450.00 declined at ZOMATO due to insufficient balance"))
        assertNull(p("riya.k99@okaxis has requested money INR 300.00 on Google Pay"))
        assertNull(p("Your credit card payment of INR 3,456 is due on 15-11-26. Minimum amount due INR 500"))
        assertNull(p("Dear customer, your KYC is pending. Visit branch."))
    }

    @Test fun sameRefSameIdAcrossSmsAndNotification() {
        val a = p("INR 245.00 debited from A/c XX1234 for UPI/SWIGGY. UPI Ref 312345678901")!!
        val b = BankSmsParser.parse("Paid ₹245.00 to Swiggy. UPI Ref 312345678901", "com.phonepe.app", at + 30_000, "NOTIF")!!
        assertEquals(a.id, b.id)
    }

    @Test fun bankFromSender() {
        assertEquals("AXIS", BankSmsParser.bankOf("AD-AXISBK"))
        assertEquals("SBI", BankSmsParser.bankOf("VK-SBIUPI-S"))
        assertNull(BankSmsParser.bankOf("VM-AMAZON"))
    }
}

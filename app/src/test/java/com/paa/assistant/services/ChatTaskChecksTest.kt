package com.paa.assistant.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases paraphrase real false tasks from Oct–Nov 2026 (docs/build/TASK_PIPELINE_V2.md). */
class ChatTaskChecksTest {
    private val owners = listOf("shreyash", "shreyas")

    @Test fun adviceIsNotATask() {
        assertTrue(ChatTaskChecks.isAdvice("tum abhi tak kyo jaag rahe ho so jao health ka dhyan rakho", "Confirm health awareness"))
        assertTrue(ChatTaskChecks.isAdvice("take care of your health beta", "Take care of health"))
        assertTrue(ChatTaskChecks.isAdvice("khana kha lena time pe", "Eat food on time"))
        // A real request that happens to contain a care word stays
        assertFalse(ChatTaskChecks.isAdvice("health insurance form kal tak bhar dena", "Fill health insurance form"))
        assertFalse(ChatTaskChecks.isAdvice("notes bhej dena", "Send notes"))
    }

    @Test fun ownerNameInTitleIsFixedOrFlagged() {
        assertEquals("Send VLSI project topic to Suryansh",
            ChatTaskChecks.fixOwnerName("Send VLSI project topic to Shreyash", owners, "Suryansh Pandey SRM"))
        assertEquals("Send final PPT to Riya", ChatTaskChecks.fixOwnerName("Send final PPT to Shreyash", owners, "Riya"))
        // Not a "to <owner>" pattern → unsure (null), so it is only asked
        assertNull(ChatTaskChecks.fixOwnerName("Give Shreyash 7500 to cook", owners, "Suryansh"))
        assertNull(ChatTaskChecks.fixOwnerName("Rate Shreyash's journey", owners, "redBus"))
        assertEquals("Send notes to Riya", ChatTaskChecks.fixOwnerName("Send notes to Riya", owners, "Riya"))
    }

    @Test fun titlesMustComeFromTheChat() {
        // A title copied from an earlier task onto an unrelated chat
        assertFalse(ChatTaskChecks.grounded("Confirm SoHo Inn OMR booking", "Karthik: haa asap"))
        assertFalse(ChatTaskChecks.grounded("Submit report by 10 AM", "report kal 11 baje tak bhej dena"))
        assertTrue(ChatTaskChecks.grounded("Send VLSI mini project to Ranjan", "Ranjan ECE: Could you please send me your vlsi mini project"))
        assertTrue(ChatTaskChecks.grounded("Send 1500 to Riyan", "Riyan Kothari ECE: Bhai 1500 bhejde"))
        // One-letter typo of a name in the chat is fine
        assertTrue(ChatTaskChecks.grounded("Call Bhomi at 3", "Bhoomi Shukla ECE: 3 bje call kar dena"))
        assertTrue(ChatTaskChecks.grounded("Submit lab record by Monday", "lab record monday tak submit karna"))
    }

    @Test fun codesAreMasked() {
        assertEquals("Please share OTP •••••• with the agent", ChatTaskChecks.mask("Please share OTP 136841 with the agent"))
        assertEquals("•••••• is your verification code", ChatTaskChecks.mask("482910 is your verification code"))
        assertTrue("4111" !in ChatTaskChecks.mask("card 4111 1111 1111 1234 charged").substringBefore("1234"))
        assertEquals("send 500 by 4", ChatTaskChecks.mask("send 500 by 4"))
        assertEquals("call me at 1145", ChatTaskChecks.mask("call me at 1145"))
    }

    @Test fun senderKinds() {
        val contacts = setOf("praveen srm", "shreya di")
        fun k(chat: String?, group: Boolean = false) = SenderKinds.classify(chat, group, false, contacts, true)
        assertEquals(SenderKind.PERSON, k("Praveen SRM"))
        assertEquals(SenderKind.BUSINESS, k("Goibibo"))
        assertEquals(SenderKind.BUSINESS, k("Eureka Forbes"))
        assertEquals(SenderKind.UNKNOWN_NUMBER, k("+91 98402 93516"))
        assertEquals(SenderKind.JOB_CHANNEL, k("Krishan Kumar - Jobs & Internships Updates"))
        assertEquals(SenderKind.GROUP, k("GOA", group = true))
        assertEquals(SenderKind.SYSTEM, k("WhatsApp"))
        // Without the Contacts permission nobody is called a business
        assertEquals(SenderKind.PERSON, SenderKinds.classify("Goibibo", false, false, emptySet(), false))
    }
}

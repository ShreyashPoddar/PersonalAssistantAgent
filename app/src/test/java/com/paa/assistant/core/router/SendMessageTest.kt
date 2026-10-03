package com.paa.assistant.core.router

import com.paa.assistant.core.messaging.Contact
import com.paa.assistant.core.messaging.ContactResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendMessageTest {
    private val parser = LocalTaskParser()

    @Test fun `hinglish and english requests`() {
        parser.parseSendMessage("Riya ko bol do ki main 10 min late hu")!!.let {
            assertEquals("Riya", it.recipient); assertEquals("Main 10 min late hu", it.taskTitle)
        }
        parser.parseSendMessage("message Aman that I'll reach by 6")!!.let {
            assertEquals("Aman", it.recipient); assertEquals("I'll reach by 6", it.taskTitle)
        }
        parser.parseSendMessage("Papa ko message karo ki khana kha liya")!!.let { assertEquals("Papa", it.recipient) }
        assertEquals(LocalIntent.SEND_MESSAGE, parser.parse("tell Rohit that the wifi is paid").intent)
    }

    @Test fun `not a message request`() {
        assertNull(parser.parseSendMessage("remind me to call mom at 5 pm"))
        assertNull(parser.parseSendMessage("tell me a joke"))
        assertEquals(LocalIntent.CREATE_TASK, parser.parse("remind me to tell Riya about the notes at 6 pm").intent)
    }

    @Test fun `contact ranking and numbers`() {
        val people = listOf(Contact("Riya ECE", "+911"), Contact("Riya", "+912"), Contact("Priyanka", "+913"))
        assertEquals("Riya", ContactResolver.rank(people, "riya").single().name)
        assertEquals("Riya ECE", ContactResolver.rank(people, "riya ece").single().name)
        assertEquals("+919876543210", ContactResolver.toE164("098765 43210"))
        assertEquals("+919876543210", ContactResolver.toE164("+91 98765-43210"))
    }

    @Test fun `call me reminders are tagged`() {
        org.junit.Assert.assertTrue("#callme" in parser.parse("call me at 5 pm to remind me about the project").tags)
        org.junit.Assert.assertTrue("#callme" in parser.parse("remind me by call at 6 pm to submit the form").tags)
        org.junit.Assert.assertTrue("#callme" !in parser.parse("remind me to call mom at 5 pm").tags)
    }
}

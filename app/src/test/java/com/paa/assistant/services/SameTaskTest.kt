package com.paa.assistant.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameTaskTest {
    @Test fun `same work in other words`() {
        assertTrue(ChatTaskScheduler.isSameTask("Register for SIH", "SIH registration"))
        assertTrue(ChatTaskScheduler.sharesWork("Send biology practical file", "Submit biology practical file"))
        assertTrue(ChatTaskScheduler.sharesWork("Send DBMS assignment before 5 PM", "Send DBMS assignment to Riya", setOf("riya")))
    }

    @Test fun `different subject or person is a different task`() {
        assertFalse(ChatTaskScheduler.isSameTask("Send biology practical file", "Send chemistry practical file"))
        assertFalse(ChatTaskScheduler.sharesWork("Send biology practical file", "Send chemistry practical file"))
        assertFalse(ChatTaskScheduler.isSameTask("Send notes to Riya", "Send notes to Kabir"))
        assertFalse(ChatTaskScheduler.sharesWork("Send physics assignment", "Send biology practical file"))
    }
}

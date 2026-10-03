package com.paa.assistant.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class RescheduleTest {
    private val parser = LocalTaskParser()
    private fun hour(ts: Long?) = Calendar.getInstance().apply { timeInMillis = ts!! }.get(Calendar.HOUR_OF_DAY)

    @Test fun `change that reminder moves the last task`() {
        val r = parser.parse("change the timing of that reminder to 10 pm")
        assertEquals(LocalIntent.RESCHEDULE_TASK, r.intent)
        assertNull(r.taskTitle)
        assertEquals(22, hour(r.timestamp))
    }

    @Test fun `correction without a verb`() {
        val r = parser.parse("no no for that remind me at 12 am")
        assertEquals(LocalIntent.RESCHEDULE_TASK, r.intent)
        assertNull(r.taskTitle)
        assertNotNull(r.timestamp)
    }

    @Test fun `named task`() {
        val r = parser.parse("move the dbms assignment to 5 pm")
        assertEquals(LocalIntent.RESCHEDULE_TASK, r.intent)
        assertEquals("dbms assignment", r.taskTitle)
    }

    @Test fun `normal reminders still create tasks`() {
        assertEquals(LocalIntent.CREATE_TASK, parser.parse("remind me to call mom at 5 pm").intent)
        assertEquals(LocalIntent.CREATE_TASK, parser.parse("remind me to check the github repository at 6 pm").intent)
    }

    @Test fun `bare time after to, and task word dropped`() {
        val r = parser.parse("reschedule the check github repo task to 10")
        assertEquals(LocalIntent.RESCHEDULE_TASK, r.intent)
        assertEquals("check github repo", r.taskTitle)
        assertNotNull(r.timestamp)
    }

    @Test fun `combined time words from several messages`() {
        val ts = parser.resolveWhen("parso tak 11 baje se pehle")!!
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        val today = Calendar.getInstance()
        assertEquals(2, ((c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)) + 365) % 365)
        assertEquals(11, c.get(Calendar.HOUR_OF_DAY))
        val t2 = Calendar.getInstance().apply { timeInMillis = parser.resolveWhen("kal tak 5 baje se pehle")!! }
        assertEquals(17, t2.get(Calendar.HOUR_OF_DAY))
    }
}

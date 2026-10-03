package com.paa.assistant.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class LocalTaskParserTest {
    private val parser = LocalTaskParser()

    private fun hourMinute(ts: Long?): Pair<Int, Int>? = ts?.let {
        val c = Calendar.getInstance().apply { timeInMillis = it }
        c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
    }

    @Test fun `casual prefix and valid time`() {
        val r = parser.parse("Can u remind me to do my math assignment at 4 pm?")
        assertEquals(LocalIntent.CREATE_TASK, r.intent)
        assertEquals("Do my math assignment", r.taskTitle)
        assertEquals(16 to 0, hourMinute(r.timestamp))
    }

    @Test fun `time without at`() {
        val r = parser.parse("remind me to call mom 4:30pm")
        assertEquals("Call mom", r.taskTitle)
        assertEquals(16 to 30, hourMinute(r.timestamp))
    }

    @Test fun `invalid time is ignored not guessed`() {
        val r = parser.parse("Can u remind me to do my math assignment 44 pm?")
        assertEquals("Do my math assignment", r.taskTitle)
        assertNull(r.timestamp)
    }

    @Test fun `by time tonight is pm`() {
        val r = parser.parse("remind me to submit MM IT assignment by 11 tonight")
        assertEquals("Submit MM IT assignment", r.taskTitle)
        assertEquals(23 to 0, hourMinute(r.timestamp))
    }

    @Test fun `tomorrow with time`() {
        val r = parser.parse("pls remind me tomorrow at 9:15 am to pay rent")
        assertEquals("Pay rent", r.taskTitle)
        val expected = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val actual = Calendar.getInstance().apply { timeInMillis = r.timestamp!! }
        assertEquals(expected.get(Calendar.DAY_OF_YEAR), actual.get(Calendar.DAY_OF_YEAR))
        assertEquals(9 to 15, hourMinute(r.timestamp))
    }

    @Test fun `shortly means twenty minutes`() {
        val r = parser.parse("remind me to call Rahul shortly")
        assertEquals("Call Rahul", r.taskTitle)
        val mins = (r.timestamp!! - System.currentTimeMillis()) / 60_000.0
        assert(mins in 19.0..21.0) { "expected ~20 min, got $mins" }
    }

    @Test fun `location reminder`() {
        val r = parser.parse("remind me to take out the trash when I reach home")
        assertEquals(LocalIntent.CREATE_TASK, r.intent)
        assertEquals("Take out the trash", r.taskTitle)
        assertEquals("home", r.locationName)
        assertEquals(1, r.locationTransition)
        assertNull(r.timestamp)
    }

    @Test fun `leaving a place`() {
        val r = parser.parse("remind me to lock the door when I leave the office")
        assertEquals("office", r.locationName)
        assertEquals(2, r.locationTransition)
    }

    @Test fun `save place`() {
        val r = parser.parse("save my current location as home")
        assertEquals(LocalIntent.SAVE_PLACE, r.intent)
        assertEquals("home", r.locationName)
    }

    @Test fun `plan tonight`() {
        val r = parser.parse("how can I finish all my tasks by tonight?")
        assertEquals(LocalIntent.PLAN_TASKS, r.intent)
        assert(r.timestamp != null)
    }

    @Test fun `list tasks by tonight`() {
        val r = parser.parse("what tasks do I have by tonight")
        assertEquals(LocalIntent.LIST_TASKS, r.intent)
        assertEquals(23 to 59, hourMinute(r.timestamp))
    }

    @Test fun `could you please prefix`() {
        val r = parser.parse("could you please remind me to buy milk")
        assertEquals("Buy milk", r.taskTitle)
    }
}

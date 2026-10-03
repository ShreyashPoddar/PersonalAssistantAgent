package com.paa.assistant.core.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RingWindowTest {
    private fun at(dayOffset: Int, h: Int, m: Int = 0): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun `window is 7am to 7pm`() {
        assertTrue(ReminderScheduler.isRingTime(at(0, 7)))
        assertTrue(ReminderScheduler.isRingTime(at(0, 18, 59)))
        assertFalse(ReminderScheduler.isRingTime(at(0, 19)))
        assertFalse(ReminderScheduler.isRingTime(at(0, 6, 59)))
        assertFalse(ReminderScheduler.isRingTime(at(0, 23, 59)))
    }

    @Test fun `EOD heads-up moves from 9pm to 6-30pm`() =
        assertEquals(at(1, 18, 30), ReminderScheduler.latestRingableAtOrBefore(at(1, 21)))

    @Test fun `early morning heads-up moves to previous evening`() =
        assertEquals(at(0, 18, 30), ReminderScheduler.latestRingableAtOrBefore(at(1, 1, 40)))

    @Test fun `daytime stays as is`() =
        assertEquals(at(1, 15, 15), ReminderScheduler.latestRingableAtOrBefore(at(1, 15, 15)))

    @Test fun `overnight deadline catches up at 7am`() {
        assertEquals(at(2, 7), ReminderScheduler.nextWindowStart(at(1, 23, 59)))
        assertEquals(at(1, 7), ReminderScheduler.nextWindowStart(at(1, 1, 40)))
    }
}

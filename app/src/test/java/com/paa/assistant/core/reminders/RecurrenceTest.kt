package com.paa.assistant.core.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class RecurrenceTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 9) = Calendar.getInstance().apply {
        clear(); set(y, m - 1, d, h, 0)
    }.timeInMillis
    private val past = at(2020, 1, 1)

    @Test fun daily() = assertEquals(at(2026, 10, 6), Recurrence.next("RRULE:FREQ=DAILY", at(2026, 10, 5), past))

    @Test fun `weekly on mon and wed from a tuesday`() {
        // 6 Oct 2026 is a Tuesday
        assertEquals(at(2026, 10, 7), Recurrence.next("RRULE:FREQ=WEEKLY;BYDAY=MO,WE", at(2026, 10, 6), past))
        assertEquals(at(2026, 10, 12), Recurrence.next("FREQ=WEEKLY;BYDAY=MO,WE", at(2026, 10, 7), past))
    }

    @Test fun `plain weekly and interval`() {
        assertEquals(at(2026, 10, 12), Recurrence.next("RRULE:FREQ=WEEKLY", at(2026, 10, 5), past))
        assertEquals(at(2026, 10, 7), Recurrence.next("RRULE:FREQ=DAILY;INTERVAL=2", at(2026, 10, 5), past))
    }

    @Test fun `monthly on the 31st skips short months`() =
        assertEquals(at(2026, 12, 31), Recurrence.next("RRULE:FREQ=MONTHLY;BYMONTHDAY=31", at(2026, 10, 31), past))

    @Test fun `long missed task jumps past now`() =
        assertEquals(at(2026, 10, 6), Recurrence.next("RRULE:FREQ=DAILY", at(2026, 9, 1), at(2026, 10, 5, 12)))

    @Test fun unknown() = assertNull(Recurrence.next("RRULE:FREQ=YEARLY", at(2026, 10, 5), past))
}

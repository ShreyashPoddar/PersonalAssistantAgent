package com.paa.assistant.core.shield

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevOptionsReminderTest {
    private val h = 3_600_000L

    @Test fun remindsOncePerDayWhenOnAndNotMuted() {
        assertTrue(DevOptionsReminder.shouldRemind(on = true, muted = false, lastAt = 0, now = 100 * h))
        assertFalse(DevOptionsReminder.shouldRemind(on = true, muted = false, lastAt = 90 * h, now = 100 * h))
        assertTrue(DevOptionsReminder.shouldRemind(on = true, muted = false, lastAt = 80 * h, now = 100 * h))
    }

    @Test fun quietWhenOffOrMuted() {
        assertFalse(DevOptionsReminder.shouldRemind(on = false, muted = false, lastAt = 0, now = 100 * h))
        assertFalse(DevOptionsReminder.shouldRemind(on = true, muted = true, lastAt = 0, now = 100 * h))
    }
}

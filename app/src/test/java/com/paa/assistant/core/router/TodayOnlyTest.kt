package com.paa.assistant.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/** "aaj" / "today" / "eod" without a time used to give no deadline (real follow-up "daal ka split daal dena aaj"). */
class TodayOnlyTest {
    private val p = LocalTaskParser()
    private fun at(h: Int, m: Int = 0) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun morningGivesSixPm() {
        assertEquals(at(18), p.todayOnly("aaj", at(10)))
        assertEquals(at(18), p.todayOnly("aaj tak", at(15, 59)))
        assertEquals(at(18), p.todayOnly("Today.", at(9)))
        assertEquals(at(18), p.todayOnly("by eod", at(11)))
    }

    @Test fun eveningGivesTwoHoursCappedAtEleven() {
        assertEquals(at(19), p.todayOnly("aaj", at(17)))
        assertEquals(at(23), p.todayOnly("aaj", at(22)))
        assertNull(p.todayOnly("aaj", at(23, 30)))
    }

    @Test fun onlyBareToday() {
        assertNull(p.todayOnly("aaj 5 baje", at(10)))
        assertNull(p.todayOnly("kal", at(10)))
    }
}

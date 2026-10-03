package com.paa.assistant.core.router

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class HinglishTest {
    private val parser = LocalTaskParser()

    private fun cal(ts: Long?) = Calendar.getInstance().apply { timeInMillis = ts!! }
    private fun isTomorrow(ts: Long?) =
        cal(ts).get(Calendar.DAY_OF_YEAR) == Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.get(Calendar.DAY_OF_YEAR)

    @Test fun `kal 5 baje call karna yaad dilana`() {
        val r = parser.parse("kal 5 baje senior ko call karna yaad dilana")
        assertEquals(LocalIntent.CREATE_TASK, r.intent)
        assertEquals("Call senior", r.taskTitle)
        assert(isTomorrow(r.timestamp))
        assertEquals(17, cal(r.timestamp).get(Calendar.HOUR_OF_DAY))
    }

    @Test fun `aaj raat 10 baje tak submit`() {
        val r = parser.parse("aaj raat 10 baje tak assignment submit karna hai, remind kar dena")
        assertEquals(LocalIntent.CREATE_TASK, r.intent)
        assertEquals("Submit assignment", r.taskTitle)
        assertEquals(22, cal(r.timestamp).get(Calendar.HOUR_OF_DAY))
    }

    @Test fun `subah sadhe 7 baje`() {
        val r = parser.parse("kal subah sadhe 7 baje gym jaana yaad dilana")
        assert(isTomorrow(r.timestamp))
        assertEquals(7, cal(r.timestamp).get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal(r.timestamp).get(Calendar.MINUTE))
    }

    @Test fun `thodi der mein`() {
        val r = parser.parse("thodi der mein mummy ko call karna yaad dila dena")
        assertEquals("Call mummy", r.taskTitle)
        val mins = (r.timestamp!! - System.currentTimeMillis()) / 60_000.0
        assert(mins in 19.0..21.0)
    }

    @Test fun `ho gaya marks done`() {
        val r = parser.parse("assignment ho gaya")
        assertEquals(LocalIntent.COMPLETE_TASK, r.intent)
        assertEquals("Assignment", r.taskTitle)
    }

    @Test fun `hata do deletes`() {
        val r = parser.parse("gym wala task hata do")
        assertEquals(LocalIntent.DELETE_TASK, r.intent)
    }

    @Test fun `kya kaam hai tonight`() {
        val r = parser.parse("aaj raat tak kya kaam hai")
        assertEquals(LocalIntent.LIST_TASKS, r.intent)
        assertEquals(23, cal(r.timestamp).get(Calendar.HOUR_OF_DAY))
    }

    @Test fun `kaise khatam karu`() {
        val r = parser.parse("aaj raat tak sab kaise khatam karu")
        assertEquals(LocalIntent.PLAN_TASKS, r.intent)
    }

    @Test fun `english still works`() {
        val r = parser.parse("remind me to call mom tomorrow at 5 pm")
        assertEquals("Call mom", r.taskTitle)
    }
}

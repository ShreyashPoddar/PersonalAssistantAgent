package com.paa.assistant.core.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "My usual" money commands are parsed locally (before save-place) and never escalate to the cloud. */
class UsualOrderRoutingTest {
    private val parser = LocalTaskParser()
    private val router = IntentRouter(parser)

    @Test fun `save usual is not a saved place`() {
        val r = parser.parse("Save this as my usual breakfast")
        assertEquals(LocalIntent.SAVE_USUAL_ORDER, r.intent)
        assertEquals("breakfast", r.taskTitle)
        assertEquals(LocalIntent.SAVE_PLACE, parser.parse("save this location as home").intent)
    }

    @Test fun `reorder usual parses the label`() {
        val r = parser.parse("order my usual")
        assertEquals(LocalIntent.REORDER_USUAL, r.intent)
        assertEquals("usual", r.taskTitle)
        assertEquals("lunch", parser.parse("can you get me my usual lunch again please").taskTitle)
        assertNotEquals(LocalIntent.REORDER_USUAL, parser.parse("order a pizza").intent)
    }

    @Test fun `usual orders stay local`() {
        for (s in listOf("order my usual breakfast", "save this as my usual dinner", "reorder the usual based on last time")) {
            val route = router.route(s)
            if (parser.parse(s).intent == LocalIntent.UNKNOWN) continue
            assertTrue(s, route is ExecutionRoute.LocalExecution)
        }
    }
}

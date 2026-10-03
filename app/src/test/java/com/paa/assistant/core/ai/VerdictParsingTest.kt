package com.paa.assistant.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictParsingTest {

    @Test fun `tasks with confidence and reason, wrapped in model chatter`() {
        val raw = """
            Sure! Here is the JSON:
            ```json
            {"tasks": [{"title": "Submit assignment to Senthil", "when": "last date hai aaj", "priority": 2,
              "registration": false, "confidence": 0.85, "reason": "You said you must submit it today."}], "reason": ""}
            ```
        """.trimIndent()
        val v = LocalLlm.parseVerdict(raw)
        assertEquals(1, v.tasks.size)
        val t = v.tasks[0]
        assertEquals("Submit assignment to Senthil", t.title)
        assertEquals("last date hai aaj", t.whenPhrase)
        assertEquals(0.85f, t.confidence, 0.001f)
        assertEquals("You said you must submit it today.", t.reason)
        assertNull(v.noTaskReason)
    }

    @Test fun `no task with reason`() {
        val v = LocalLlm.parseVerdict("""{"tasks": [], "reason": "An advertisement, not addressed to you."}""")
        assertTrue(v.tasks.isEmpty())
        assertEquals("An advertisement, not addressed to you.", v.noTaskReason)
    }

    @Test fun `null when and missing confidence get defaults`() {
        val v = LocalLlm.parseVerdict("""{"tasks": [{"title": "Order groceries", "when": null}]}""")
        assertNull(v.tasks[0].whenPhrase)
        assertEquals(0.6f, v.tasks[0].confidence, 0.001f)
    }

    @Test(expected = IllegalStateException::class)
    fun `garbage throws so the caller can fall back`() {
        LocalLlm.parseVerdict("I don't know")
    }

    @Test fun `stray brace after title, as seen on the emulator`() {
        val v = LocalLlm.parseVerdict(
            """{"tasks": [{"title": "Call me"}, "when": "by 5", "priority": 1, "registration": false, "confidence": 0.9, "reason": "Rahul asked for a call back."}], "reason": ""}"""
        )
        assertEquals("Call me", v.tasks.single().title)
        assertEquals("by 5", v.tasks.single().whenPhrase)
    }

    @Test fun `output cut off before the closing brackets`() {
        val v = LocalLlm.parseVerdict(
            """{"tasks": [{"title": "Send the ppt", "when": "at 315", "priority": 1, "registration": false, "confidence": 0.9, "reason": "You promised."}"""
        )
        assertEquals("Send the ppt", v.tasks.single().title)
    }

    @Test fun `done lists 1-based open task numbers`() {
        val v = LocalLlm.parseVerdict("""{"tasks": [], "reason": "a confirmation", "done": [1, 3, 0]}""")
        assertEquals(listOf(0, 2), v.done)
    }

    @Test fun `updates to open tasks`() {
        val v = LocalLlm.parseVerdict("""{"tasks": [], "reason": "", "done": [], "update": [{"n": 2, "when": "7 baje tak", "title": null}, {"n": 0, "when": "x"}]}""")
        assertEquals(1, v.updates.size)
        assertEquals(1, v.updates[0].index)
        assertEquals("7 baje tak", v.updates[0].whenPhrase)
        assertNull(v.updates[0].title)
    }
}

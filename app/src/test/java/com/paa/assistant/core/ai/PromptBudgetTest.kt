package com.paa.assistant.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetTest {
    private val ctx = LocalLlm.ChatContext(
        outgoing = false, chatName = "Riya", sender = "Riya", isGroup = false,
        recentMessages = (1..12).map { "Riya: earlier line number $it about the project" },
        learnedExamples = listOf("learned example message one" to "Do it", "learned two" to null),
        openTasks = (1..8).map { "Open task $it" }
    )
    // ~1 token per 4 chars, like the real tokenizer for English
    private val count = { s: String -> s.length / 4 }

    @Test fun `fits unchanged when small`() {
        val p = LocalLlm.fitPrompt("notes bhej dena", ctx) { 10 }
        assertTrue(p.contains("learned example message one"))
        assertTrue(p.contains("earlier line number 12 "))
    }

    @Test fun `drops learned examples first, then old lines`() {
        val full = LocalLlm.buildChatPrompt("notes bhej dena", ctx)
        // Scale so the full prompt is 50 tokens over budget
        val k = (LocalLlm.PROMPT_BUDGET + 50).toDouble() / full.length
        val p = LocalLlm.fitPrompt("notes bhej dena", ctx) { s -> (s.length * k).toInt() }
        assertFalse(p.contains("learned example message one"))
        assertTrue(count(p) <= count(full))
    }

    @Test fun `long call transcript is cut to fit`() {
        val long = "baat ".repeat(2000)
        val p = LocalLlm.fitPrompt(long, ctx.copy(isCall = true), count)
        assertTrue(count(p) <= LocalLlm.PROMPT_BUDGET)
        assertEquals(true, p.contains("baat"))
    }
}

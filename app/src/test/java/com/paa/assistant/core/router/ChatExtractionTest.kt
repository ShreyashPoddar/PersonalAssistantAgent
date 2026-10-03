package com.paa.assistant.core.router

import com.paa.assistant.services.ChatTaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ChatExtractionTest {
    private val parser = LocalTaskParser()
    private val extractor = CommitmentExtractor(parser)

    /** The next upcoming occurrence of h:m on a 12-hour clock (AM or PM, whichever comes first). */
    private fun nextUpcoming(h: Int, m: Int): Long {
        val now = System.currentTimeMillis()
        for (day in 0..1) for (hour in listOf(h % 12, h % 12 + 12)) {
            val c = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, day); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, m)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis > now) return c.timeInMillis
        }
        error("unreachable")
    }

    // ── Compact times ────────────────────────────────────────────────────────

    @Test fun `by 140 is the next 1-40`() = assertEquals(nextUpcoming(1, 40), parser.resolveWhen("by 140"))
    @Test fun `315 is the next 3-15`() = assertEquals(nextUpcoming(3, 15), parser.resolveWhen("315 i have to submit"))
    @Test fun `1145 is the next 11-45`() = assertEquals(nextUpcoming(11, 45), parser.resolveWhen("1145 i'll have to finish it"))

    @Test fun `1145 tonight is PM`() {
        val c = Calendar.getInstance().apply { timeInMillis = parser.resolveWhen("1145 tonight")!! }
        assertEquals(23, c.get(Calendar.HOUR_OF_DAY)); assertEquals(45, c.get(Calendar.MINUTE))
    }

    @Test fun `morning 730 is AM`() {
        val c = Calendar.getInstance().apply { timeInMillis = parser.resolveWhen("tomorrow morning 730")!! }
        assertEquals(7, c.get(Calendar.HOUR_OF_DAY)); assertEquals(30, c.get(Calendar.MINUTE))
    }

    @Test fun `24 hour 1530`() {
        val c = Calendar.getInstance().apply { timeInMillis = parser.resolveWhen("at 1530")!! }
        assertEquals(15, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun `numbers that are not times`() {
        assertEquals(null, parser.resolveWhen("i got 450 marks"))
        assertEquals(null, parser.resolveWhen("it costs rs 250"))
        assertEquals(null, parser.resolveWhen("batch of 2028"))
        assertEquals(null, parser.resolveWhen("room 315"))
    }

    // ── Extraction ───────────────────────────────────────────────────────────

    @Test fun `two tasks in one sent message`() {
        val tasks = extractor.extract("ok bro I'll call Rahul by 140 and send the ppt at 315", outgoing = true, mentionedMe = false)
        assertEquals(listOf("Call Rahul", "Send the ppt"), tasks.map { it.title })
        assertEquals(nextUpcoming(1, 40), tasks[0].timestamp)
        assertEquals(nextUpcoming(3, 15), tasks[1].timestamp)
    }

    @Test fun `title is the work not the whole message`() {
        val tasks = extractor.extract("1145 I'll have to finish the DBMS assignment yaar", outgoing = true, mentionedMe = false)
        assertEquals(listOf("Finish the DBMS assignment"), tasks.map { it.title })
    }

    @Test fun `past tense is not a task`() {
        assertTrue(extractor.extract("I already submitted the assignment", outgoing = true, mentionedMe = false).isEmpty())
        assertTrue(extractor.extract("called him yesterday", outgoing = true, mentionedMe = false).isEmpty())
    }

    @Test fun `negation is not a task`() {
        assertTrue(extractor.extract("I won't call him today", outgoing = true, mentionedMe = false).isEmpty())
    }

    @Test fun `question is not a commitment`() {
        assertTrue(extractor.extract("should I call Priya?", outgoing = true, mentionedMe = false).isEmpty())
    }

    @Test fun `chit chat is not a task`() {
        assertTrue(extractor.extract("haha did you see the match yesterday", outgoing = true, mentionedMe = false).isEmpty())
        assertTrue(extractor.extract("nice, see you", outgoing = false, mentionedMe = false).isEmpty())
    }

    @Test fun `incoming request`() {
        val tasks = extractor.extract("can you send me the notes by 9 pm", outgoing = false, mentionedMe = false)
        assertEquals(listOf("Send me the notes"), tasks.map { it.title })
    }

    @Test fun `group mention with two requests`() {
        val tasks = extractor.extract("@Shreyash order groceries and pay the wifi bill", outgoing = false, mentionedMe = true)
        assertEquals(listOf("Order groceries", "Pay the wifi bill"), tasks.map { it.title })
    }

    @Test fun `hinglish commitment`() {
        val tasks = extractor.extract("kal 5 baje senior ko call karunga", outgoing = true, mentionedMe = false)
        assertEquals(1, tasks.size)
        assertTrue(tasks[0].title.lowercase().startsWith("call"))
    }

    @Test fun `screenshot message - submit today, last date hai aaj`() {
        val tasks = extractor.extract("I too need to submit it today, last date hai aaj", outgoing = true, mentionedMe = false)
        println("EXTRACTED: $tasks")
        assertEquals(listOf("Submit it"), tasks.map { it.title })
    }

    @Test fun `not tasks from that chat`() {
        assertTrue(extractor.extract("Wo dekh lo tum bb", outgoing = true, mentionedMe = false).isEmpty())
        assertTrue(extractor.extract("Hn it's alr bb", outgoing = true, mentionedMe = false).isEmpty())
        assertTrue(extractor.extract("Hardcopy ho gaya na", outgoing = false, mentionedMe = false).isEmpty())
    }

    @Test fun `promos and system texts are not tasks`() {
        for (t in listOf(
            "Register now for the biggest hackathon of the year", "Get 20% off on your ride",
            "Join our evergrowing unstoppable community", "Don't wait for weekend rush",
            "Ongoing voice call", "Missed voice call", "Put a story"
        )) assertTrue(t, extractor.extract(t, outgoing = false, mentionedMe = false).isEmpty())
    }

    // ── Duplicates ───────────────────────────────────────────────────────────

    @Test fun `same task from different people`() {
        assertTrue(ChatTaskScheduler.isSameTask("Register for SIH", "SIH registration"))
        assertTrue(ChatTaskScheduler.isSameTask("Submit DBMS assignment", "submit the dbms assignment"))
        assertFalse(ChatTaskScheduler.isSameTask("Call Rahul", "Call Priya"))
        assertFalse(ChatTaskScheduler.isSameTask("Submit OS assignment", "Submit DBMS assignment"))
    }
}

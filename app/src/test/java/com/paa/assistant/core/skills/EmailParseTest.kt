package com.paa.assistant.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmailParseTest {
    @Test fun parses() {
        val (to, subject, body) = EmailSkill.parse("write an email to prof.k@srmist.edu.in saying I'll join the meeting at 4")!!
        assertEquals("prof.k@srmist.edu.in", to)
        assertEquals("I'll join the meeting at 4", body)
        assertEquals("I'll join the meeting at 4", subject)
        assertEquals("a@b.co", EmailSkill.parse("send mail to a@b.co about the lab record")!!.first)
    }

    @Test fun ignoresOthers() {
        assertNull(EmailSkill.parse("check my email"))
        assertNull(EmailSkill.parse("message Riya that I'm late"))
    }
}

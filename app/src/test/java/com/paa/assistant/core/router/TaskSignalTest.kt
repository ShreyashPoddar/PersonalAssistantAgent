package com.paa.assistant.core.router

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSignalTest {
    @Test fun `real tasks always have a signal`() {
        for (m in listOf(
            "kal 10 baje tak report bhej dena aur fees bhi bhar dena",
            "@shreyash bring the projector at 1145",
            "bhai 4 baje tak 500 rupay bhej dena",
            "sir ne bola hai kal 9 baje tak lab record submit karna hai",
            "bro english ka essay bhej dena", "parso tak", "11 baje se pehle",
            "sorry yaar, 4 baje tak bhi chalega",
            "please call me back by 5 pm",
            "ok bro I'll call you by 140 and send the ppt at 315",
            "bhai portfolio link bhjde",
            "register for this hackathon https://unstop.com/hackathons/x-123456",
            "10 final na ??",
            "notes de dena yaar",
            "kal milte hai library mein",
            "form fill kar dena",
            "Pls call",
            "assignment ready rakhna",
        )) assertTrue(m, TaskSignal.hasSignal(m))
    }

    @Test fun `done replies count only where a task is open`() {
        assertTrue(TaskSignal.hasSignal("Hn bhai", hasOpenTasks = true))
        assertTrue(TaskSignal.hasSignal("ho gaya", hasOpenTasks = true))
        assertFalse(TaskSignal.hasSignal("Hn bhai", hasOpenTasks = false))
    }

    @Test fun `chatter has none`() {
        for (m in listOf(
            "haha", "😂😂", "kya scene hai", "Samosa party ke baju mei", "Abe saale", "Teri mlc",
            "Sale phone utha nahi raha", "lol same", "hmm", "Tere bhi toh hai", "nice yaar",
            "Jo last time gaya tha", "Tere bdy k din", "bhai mast", "sahi hai",
            "👾 Sent a GIF", "Yahan se",
        )) assertFalse(m, TaskSignal.hasSignal(m))
    }
}

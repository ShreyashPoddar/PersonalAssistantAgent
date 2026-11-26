package com.paa.assistant.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TravelQueryTest {
    @Test fun english() {
        val q = TravelQuery.parse("cheapest bus from Chennai to Hosur on Friday, morning AC")!!
        assertEquals(listOf("bus"), q.modes); assertEquals("Chennai", q.from); assertEquals("Hosur", q.to)
        assertEquals("Friday", q.whenPhrase)
        assertTrue(q.prefs.containsAll(listOf("cheapest", "morning", "ac")))
    }

    @Test fun twoModesAndMultiWordCities() {
        val q = TravelQuery.parse("Oyee PA, train or flight from New Delhi to Navi Mumbai tomorrow")!!
        assertEquals(listOf("train", "flight"), q.modes); assertEquals("New Delhi", q.from); assertEquals("Navi Mumbai", q.to)
        assertEquals("tomorrow", q.whenPhrase)
    }

    @Test fun hinglish() {
        val q = TravelQuery.parse("Chennai se Bangalore ki bus kal")!!
        assertEquals(listOf("bus"), q.modes); assertEquals("Chennai", q.from); assertEquals("Bangalore", q.to); assertEquals("kal", q.whenPhrase)
    }

    @Test fun notTravel() {
        assertNull(TravelQuery.parse("remind me to book the bus tickets"))
        assertNull(TravelQuery.parse("message Riya that the bus is late"))
        assertNull(TravelQuery.parse("kal bus from Chennai to Hosur book karna yaad dilana"))
        assertNull(TravelQuery.parse("remind me to check the train from Chennai to Delhi"))
    }

    @Test fun questionHasNoPersonalData() {
        val q = TravelQuery.parse("bus from Chennai to Hosur on Friday")!!
        val w = q.webQuestion("Fri 28 Nov")
        assertTrue(w, "Chennai" in w && "Hosur" in w && "Fri 28 Nov" in w)
    }
}

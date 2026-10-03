package com.paa.assistant.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inputs are real outputs of the bundled model's wake-grammar decoder on synthesized speech. */
class WakePhraseTest {
    private fun wakes(s: String) = WakeListenerService.isWake(
        s.split(" ").map { it.substringBefore(":") to it.substringAfter(":").toDouble() }
    )

    @Test fun `ways the decoder hears oyee pa`() {
        for (s in listOf(
            "[unk]:0.88 o:0.50 hey:0.91 pa:1.00",                 // "oyee pa"
            "[unk]:0.68 o:0.50 hey:0.59 pa:1.00",
            "[unk]:0.82 oye:0.87 pa:0.87 [unk]:0.64 [unk]:1.00",  // "oye pa, remind me to call mom"
            "[unk]:0.65 oye:1.00 pa:1.00",                        // "oi pa"
            "[unk]:0.76 oh:0.49 hey:0.58 pa:1.00",                // "oyee paa"
            "[unk]:0.92 oh:0.50 hey:0.56 oy:0.41 [unk]:0.84 o:0.45 hey:0.66 pa:1.00", // "... meet at 5. oyee pa"
            "[unk]:1.00 [unk]:1.00 pa:1.00",                      // the owner's own voice, on the phone
            "oy:0.50 oy:0.50 oy:0.50 pa:1.00",
            "oy:0.23 pa:0.96",                                    // owner on the phone, evening
            "oh:0.41 pa:0.62",
            "oh:0.47 pa:0.95",
        )) assertTrue(s, wakes(s))
    }

    @Test fun `normal speech does not wake`() {
        for (s in listOf(
            "[unk]:1.00",                                         // "I thought we would meet at five"
            "[unk]:0.75 hey:1.00 pa:1.00",                        // "hey Paul how are you"
            "[unk]:0.90 oh:0.50 pa:1.00 pa:1.00 [unk]:1.00 oh:0.50", // "oh papa is coming home"
            "[unk]:1.00 hey:1.00 [unk]:1.00",                     // "hey are you coming"
            "[unk]:0.92 oh:0.45 pa:0.91",                         // "open the app please"
        )) assertFalse(s, wakes(s))
    }

    @Test fun `oyee P A spelled as letters`() {
        assertTrue(wakes("[unk]:0.80 oye:0.40 p:0.70 a:0.90"))
        assertTrue(wakes("oh:0.50 p:0.60 a:0.80"))
        assertFalse(wakes("[unk]:0.90 p:0.90 a:0.90"))
        fun hi(s: String) = WakeListenerService.isWakeHindi(s.split(" ").map { it.substringBefore(":") to it.substringAfter(":").toDouble() })
        assertTrue(hi("ओये:0.40 पीए:0.80"))
        assertTrue(hi("[unk]:1.00 ओ:0.50 पी:0.70 ए:0.60"))
        assertFalse(hi("पापा:1.00"))
        assertFalse(hi("[unk]:1.00 पीए:0.90"))
    }
}

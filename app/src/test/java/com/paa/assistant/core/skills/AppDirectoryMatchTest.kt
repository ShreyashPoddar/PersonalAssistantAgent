package com.paa.assistant.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDirectoryMatchTest {
    private val apps = listOf(
        "Instagram" to "com.instagram.android", "YouTube" to "com.google.android.youtube",
        "WhatsApp" to "com.whatsapp", "WhatsApp Business" to "com.whatsapp.w4b", "Google Pay" to "com.google.android.apps.nbu.paisa.user",
        "PhonePe" to "com.phonepe.app", "Flipkart" to "com.flipkart.android", "Amazon Shopping" to "in.amazon.mShop.android.shopping",
        "Maps" to "com.google.android.apps.maps", "Settings" to "com.android.settings", "Camera" to "com.android.camera",
        "Spotify" to "com.spotify.music", "Swiggy" to "in.swiggy.android", "Zomato" to "com.application.zomato",
        "MakeMyTrip" to "com.makemytrip"
    ).map { AppEntry(it.first, it.second) }

    private fun m(q: String) = AppDirectory.match(q, apps)?.label

    @Test fun aliasesAndNames() {
        assertEquals("Instagram", m("insta"))
        assertEquals("YouTube", m("yt"))
        assertEquals("Google Pay", m("gpay"))
        assertEquals("WhatsApp", m("whatsapp"))
        assertEquals("MakeMyTrip", m("mmt"))
        assertEquals("Amazon Shopping", m("amazon"))
        assertEquals("Swiggy", m("the swiggy app"))
    }

    @Test fun typosAndUnknown() {
        assertEquals("Instagram", m("instgram"))
        assertNull(m("snapchat"))
        assertNull(m(""))
    }

    @Test fun parsesOpenCommands() {
        assertEquals("instagram", AppDirectory.parseOpen("open instagram"))
        assertEquals("insta", AppDirectory.parseOpen("Oyee PA, insta kholo"))
        assertEquals("gpay", AppDirectory.parseOpen("launch gpay"))
        assertEquals("youtube", AppDirectory.parseOpen("youtube open karo"))
        assertNull(AppDirectory.parseOpen("open the file I got from Riya"))
        assertNull(AppDirectory.parseOpen("remind me to open the shop at 9"))
        assertNull(AppDirectory.parseOpen("message Riya that I'm late"))
    }
}

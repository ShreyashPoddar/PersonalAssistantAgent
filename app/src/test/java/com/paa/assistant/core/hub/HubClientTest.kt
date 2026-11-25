package com.paa.assistant.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class HubClientTest {
    private val fp = "ab".repeat(32)

    @Test fun parsesPairingPayload() {
        val p = HubPairing.parse("""{"v":1,"host":"192.168.1.9","port":8765,"token":"abcdefghijklmnopqrstuvwxyz012345","cert_sha256":"${fp.uppercase()}"}""")!!
        assertEquals("192.168.1.9", p.host); assertEquals(8765, p.port); assertEquals(fp, p.certSha256)
    }

    @Test fun rejectsBadPayloads() {
        assertNull(HubPairing.parse("not json"))
        assertNull(HubPairing.parse("""{"host":"h","port":8765,"token":"short","cert_sha256":"$fp"}"""))
        assertNull(HubPairing.parse("""{"host":"h","port":8765,"token":"abcdefghijklmnopqrstuvwxyz","cert_sha256":"xyz"}"""))
        assertNull(HubPairing.parse("""{"host":"h","port":70000,"token":"abcdefghijklmnopqrstuvwxyz","cert_sha256":"$fp"}"""))
    }

    @Test fun pinning() {
        val der = "certificate-bytes".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
        assertTrue(HubClient.pinMatches(der, sha))
        assertTrue(HubClient.pinMatches(der, sha.uppercase()))
        assertFalse(HubClient.pinMatches("other".toByteArray(), sha))
    }
}

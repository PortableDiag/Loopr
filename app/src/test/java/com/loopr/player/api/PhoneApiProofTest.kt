package com.loopr.player.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider contract §2: the proof PhoneAPI checks, and when Loopr gives one. */
class PhoneApiProofTest {

    @Test fun proofIsHmacSha256OfTheChallengeAsBase64UrlWithoutPadding() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?".
        val p = PhoneApiProof.proof("Jefe".toByteArray(), "what do ya want for nothing?")
        assertEquals("W9zBRr9gdU5qBCQmCJV1x1oAPwidJzmDnexYuWTsOEM", p)
    }

    @Test fun noKeyOrNoChallengeMeansNoHeader() {
        val key = ByteArray(32) { it.toByte() }
        assertNull(PhoneApiProof.header(null, "abc"))
        assertNull(PhoneApiProof.header(key, null))
        assertNull(PhoneApiProof.header(key, ""))
        assertNull(PhoneApiProof.header(key, "bad\u0001"))
        assertNull(PhoneApiProof.header(key, "x".repeat(257)))
        val h = PhoneApiProof.header(key, "abc")!!
        assertEquals("X-PhoneAPI-Proof", h.first)
        assertEquals(PhoneApiProof.proof(key, "abc"), h.second)
    }

    @Test fun pinMatchesOnlyPhoneApisReleaseCertificate() {
        assertTrue(PhoneApiProof.pinMatches(PhoneApiProof.PIN.lowercase().replace(":", "")))
        assertFalse(PhoneApiProof.pinMatches(PhoneApiProof.PIN.replaceFirst("AD", "AE")))
        assertEquals(32, PhoneApiProof.pinBytes.size)
    }

    @Test fun proofKeyRoundTrips() {
        val k = ByteArray(32) { (it * 7).toByte() }
        val s = PhoneApiProof.b64url(k)
        assertFalse(s.contains('='))
        assertTrue(k.contentEquals(PhoneApiProof.fromB64url(s)))
    }
}

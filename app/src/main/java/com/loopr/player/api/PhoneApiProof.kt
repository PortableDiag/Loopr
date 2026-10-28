package com.loopr.player.api

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Loopr as a PhoneAPI provider (PhoneAPI's docs/provider-contract.md §2–§3), the parts with no
 * Android in them, so they are covered by JVM tests.
 *
 * Any app can bind 8793 while Loopr's API is off, and PhoneAPI's pin only proves who is installed,
 * not who is listening. So once PhoneAPI holds a proof key from the handshake, every answer carries
 * `X-PhoneAPI-Proof` = HMAC-SHA256(proof key, the request's `X-PhoneAPI-Challenge`), and PhoneAPI
 * throws away an answer without one.
 */
object PhoneApiProof {

    const val PACKAGE = "com.phoneapi.app"
    const val ACTION_GRANT_KEY = "com.phoneapi.action.GRANT_KEY"
    const val HEADER_CHALLENGE = "x-phoneapi-challenge"
    const val HEADER_PROOF = "X-PhoneAPI-Proof"

    /**
     * PhoneAPI's release certificate (keystore-manager record "PhoneAPI"). A debug build of
     * PhoneAPI is signed by the debug key and is refused, as PhoneAPI refuses debug builds of
     * providers.
     */
    const val PIN = "AD:86:A7:04:86:39:86:F3:A4:ED:10:66:00:F1:EC:C2:19:EE:31:82:68:D4:84:BB:54:E9:07:9C:E3:B8:E0:02"

    val pinBytes: ByteArray get() = PIN.split(':').map { it.toInt(16).toByte() }.toByteArray()

    fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** Null when it is not base64url (padded or not). */
    fun fromB64url(s: String?): ByteArray? =
        if (s.isNullOrBlank()) null else runCatching { Base64.getUrlDecoder().decode(s.trim()) }.getOrNull()

    fun proof(proofKey: ByteArray, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(proofKey, "HmacSHA256"))
        return b64url(mac.doFinal(challenge.toByteArray(Charsets.US_ASCII)))
    }

    /** A challenge worth answering: present, short, printable ASCII (it is hashed, never echoed). */
    fun usableChallenge(c: String?): String? {
        val t = c?.trim() ?: return null
        if (t.isEmpty() || t.length > 256 || t.any { it < ' ' || it > '~' }) return null
        return t
    }

    /** The proof header for [challenge], or none when there is no key or no usable challenge. */
    fun header(proofKey: ByteArray?, challenge: String?): Pair<String, String>? {
        val c = usableChallenge(challenge) ?: return null
        if (proofKey == null || proofKey.isEmpty()) return null
        return HEADER_PROOF to proof(proofKey, c)
    }

    /** Whether [certSha256] (hex, any case, colons or not) is PhoneAPI's release certificate. */
    fun pinMatches(certSha256: String?): Boolean {
        if (certSha256 == null) return false
        val norm = { s: String -> s.replace(":", "").trim().lowercase(Locale.ROOT).toByteArray() }
        return MessageDigest.isEqual(norm(certSha256), norm(PIN))
    }
}

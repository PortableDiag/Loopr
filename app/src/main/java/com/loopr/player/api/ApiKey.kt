package com.loopr.player.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * One caller's key: a name, the tools it may use, and what it has done lately.
 *
 * Only a SHA-256 of the key is kept. The key itself is shown once, when it is made, and nobody —
 * Loopr included — can show it again; a lost key is revoked and replaced.
 */
data class ApiKey(
    val id: Long,
    val name: String,
    val hash: String,
    /** The last four characters, so the list can tell two keys of the same name apart. */
    val tail: String,
    val tools: Set<String> = emptySet(),
    val createdAt: Long = 0,
    val lastUsedAt: Long = 0,
    val calls: Int = 0,
    /** The most recent calls, newest last, for the key's activity list. */
    val activity: List<String> = emptyList()
)

/** Where keys live. The Android one is [ApiKeys]; tests hand the router a list. */
interface KeyStore {
    fun keys(): List<ApiKey>

    /** Notes a call against [id]: when, which tool, and whether it worked. */
    fun used(id: Long, line: String)
}

object KeyHash {
    const val PREFIX = "lp_"

    fun newKey(): String {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** The key [provided] belongs to, or null. Every stored hash is compared, in constant time. */
    fun match(keys: List<ApiKey>, provided: String?): ApiKey? {
        if (provided.isNullOrBlank() || !provided.trim().startsWith(PREFIX)) return null
        val h = hash(provided).toByteArray()
        var found: ApiKey? = null
        for (k in keys) if (MessageDigest.isEqual(k.hash.toByteArray(), h)) found = k
        return found
    }
}

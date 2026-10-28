package com.loopr.player.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Loopr's side of the PhoneAPI link (PhoneAPI's docs/provider-contract.md §3): which of the keys in
 * [ApiKeys] is PhoneAPI's, the proof key Loopr answers its challenges with, and where to reach
 * PhoneAPI back.
 *
 * PhoneAPI's key is an ordinary key in the Local API list, named "PhoneAPI", with the tools the
 * owner switched on for it. A second handshake replaces it; revoking it from the list unlinks
 * PhoneAPI, because the proof key is only answered while that key still exists.
 */
object PhoneApiLink {

    private const val TAG = "Loopr.PhoneApi"
    private const val PREFS = "loopr_phoneapi"
    private const val KEY_ID = "key_id"
    private const val KEY_PROOF = "proof_key"
    private const val KEY_PORT = "phoneapi_port"
    private const val KEY_EVENT = "phoneapi_event_key"

    const val KEY_NAME = "PhoneAPI"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The id of PhoneAPI's key in [ApiKeys], or null while PhoneAPI is not linked. */
    fun keyId(c: Context): Long? {
        val id = sp(c).getLong(KEY_ID, 0)
        if (id <= 0) return null
        return id.takeIf { ApiKeys(c).keys().any { it.id == id } }
    }

    fun linked(c: Context): Boolean = keyId(c) != null

    /** The proof key, while PhoneAPI's key still exists. */
    fun proofKey(c: Context): ByteArray? {
        if (!linked(c)) return null
        return PhoneApiProof.fromB64url(sp(c).getString(KEY_PROOF, null))
    }

    fun phoneApiPort(c: Context): Int = sp(c).getInt(KEY_PORT, 0)

    fun eventKey(c: Context): String? = sp(c).getString(KEY_EVENT, null)

    /**
     * Mints PhoneAPI a new key with [tools] and a new 32-byte proof key, replacing any earlier
     * key PhoneAPI had, and keeps the two values PhoneAPI sent. Returns (api key, proof key as
     * base64url); the api key exists nowhere else, since [ApiKeys] keeps only its hash.
     */
    fun grant(c: Context, tools: Set<String>, phoneApiPort: Int, eventKey: String?): Pair<String, String> {
        val keys = ApiKeys(c)
        val old = sp(c).getLong(KEY_ID, 0)
        if (old > 0) keys.revoke(old)
        keys.keys().filter { it.name == KEY_NAME }.forEach { keys.revoke(it.id) }
        val (key, raw) = keys.create(KEY_NAME)
        keys.setTools(key.id, tools)
        val proof = PhoneApiProof.b64url(ByteArray(32).also { SecureRandom().nextBytes(it) })
        sp(c).edit()
            .putLong(KEY_ID, key.id)
            .putString(KEY_PROOF, proof)
            .putInt(KEY_PORT, phoneApiPort)
            .putString(KEY_EVENT, eventKey)
            .apply()
        return raw to proof
    }

    /** Forget PhoneAPI: its key stops working at once, and no challenge is answered. */
    fun unlink(c: Context) {
        val id = sp(c).getLong(KEY_ID, 0)
        if (id > 0) ApiKeys(c).revoke(id)
        sp(c).edit().clear().apply()
    }

    /**
     * Android runs no code on uninstall, so this runs when Loopr starts and when the API starts:
     * a PhoneAPI key outlives neither PhoneAPI nor its signer, so whatever next takes the package
     * name does not inherit it.
     */
    fun dropIfUntrusted(c: Context) {
        if (sp(c).getLong(KEY_ID, 0) <= 0) return
        if (isTrusted(c, PhoneApiProof.PACKAGE)) return
        Log.i(TAG, "PhoneAPI is gone or not signed by its release key: its key is revoked")
        unlink(c)
    }

    /** True when [pkg] is PhoneAPI's release build, signed by the pinned certificate. */
    fun isTrusted(c: Context, pkg: String?): Boolean {
        if (pkg != PhoneApiProof.PACKAGE) return false
        val pm = c.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.hasSigningCertificate(pkg, PhoneApiProof.pinBytes, PackageManager.CERT_INPUT_SHA256)
            } else {
                @Suppress("DEPRECATION")
                val sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
                // One signer, and it is the pinned one. More than one signer is not PhoneAPI.
                sigs != null && sigs.size == 1 &&
                    MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray()).contentEquals(PhoneApiProof.pinBytes)
            }
        }.getOrDefault(false)
    }
}

/** PhoneAPI was uninstalled: its key goes with it. */
class PhoneApiGoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.data?.schemeSpecificPart == PhoneApiProof.PACKAGE) PhoneApiLink.dropIfUntrusted(context)
    }
}

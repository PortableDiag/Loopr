package com.loopr.player.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Whether the API is on, its port, and its keys, in Loopr's private preferences.
 *
 * A key is kept as its SHA-256 only — the key itself is handed back from [create] once, to be
 * shown once — so nothing on disk can be replayed against the API.
 */
class ApiKeys(context: Context) : KeyStore {

    companion object {
        /**
         * Clear of the other in-house local APIs as they stood on 2026-10-27: Lull 8787, TextX
         * 8788, DialX 8789, NotifX 8790 (whose stub agent takes 8791 on the computer, the far end
         * of an `adb forward`), KJVBible 8794, ClipX 8795, ThereminX 8796, AppManager 8797,
         * GhostUser 8799.
         */
        const val DEFAULT_PORT = 8792

        /** Loopback only: off the phone, use `adb forward`, or PhoneAPI once it pairs agents. */
        const val BIND = "127.0.0.1"

        private const val PREFS = "loopr_api"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_KEYS = "keys"
        private const val ACTIVITY_KEPT = 20

        /** Why the last start failed, shown under the switch until it is touched again. */
        @Volatile var failure: String? = null

        /** The service and the settings screen each hold an instance; one lock covers both. */
        private val LOCK = Any()
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(on) { failure = null; prefs.edit().putBoolean(KEY_ENABLED, on).apply() }

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1024, 65535)
        set(p) { prefs.edit().putInt(KEY_PORT, p.coerceIn(1024, 65535)).apply() }

    /** The server could not start: switch it off so the switch is not left saying on, and say why. */
    fun failed(reason: String) {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        failure = reason
    }

    override fun keys(): List<ApiKey> = synchronized(LOCK) {
        val arr = runCatching { JSONArray(prefs.getString(KEY_KEYS, "[]")) }.getOrDefault(JSONArray())
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }

    /** Makes a key with every tool off. The returned string is the only copy of the key. */
    fun create(name: String): Pair<ApiKey, String> = synchronized(LOCK) {
        val raw = KeyHash.newKey()
        val all = keys()
        val key = ApiKey(
            id = (all.maxOfOrNull { it.id } ?: 0) + 1,
            name = name.trim().ifEmpty { "API key" },
            hash = KeyHash.hash(raw),
            tail = raw.takeLast(4),
            createdAt = System.currentTimeMillis()
        )
        save(all + key)
        key to raw
    }

    fun setTools(id: Long, tools: Set<String>) = update(id) { it.copy(tools = tools) }

    fun rename(id: Long, name: String) = update(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun revoke(id: Long) = synchronized(LOCK) { save(keys().filter { it.id != id }) }

    override fun used(id: Long, line: String) = update(id) {
        val stamp = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).format(Date())
        it.copy(
            lastUsedAt = System.currentTimeMillis(),
            calls = it.calls + 1,
            activity = (it.activity + "$stamp  $line").takeLast(ACTIVITY_KEPT)
        )
    }

    private fun update(id: Long, change: (ApiKey) -> ApiKey) = synchronized(LOCK) {
        save(keys().map { if (it.id == id) change(it) else it })
    }

    private fun save(list: List<ApiKey>) {
        prefs.edit().putString(KEY_KEYS, JSONArray(list.map { toJson(it) }).toString()).apply()
    }

    private fun toJson(k: ApiKey) = JSONObject()
        .put("id", k.id).put("name", k.name).put("hash", k.hash).put("tail", k.tail)
        .put("tools", JSONArray(k.tools.sorted())).put("created", k.createdAt)
        .put("last_used", k.lastUsedAt).put("calls", k.calls).put("activity", JSONArray(k.activity))

    private fun fromJson(o: JSONObject): ApiKey {
        val tools = o.optJSONArray("tools") ?: JSONArray()
        val activity = o.optJSONArray("activity") ?: JSONArray()
        return ApiKey(
            id = o.getLong("id"),
            name = o.optString("name"),
            hash = o.getString("hash"),
            tail = o.optString("tail"),
            tools = (0 until tools.length()).map { tools.getString(it) }.toSet(),
            createdAt = o.optLong("created"),
            lastUsedAt = o.optLong("last_used"),
            calls = o.optInt("calls"),
            activity = (0 until activity.length()).map { activity.getString(it) }
        )
    }
}

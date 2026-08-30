package com.loopr.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * The open floating windows, written to disk so the system killing Loopr doesn't take them with it.
 *
 * A floating window outlives the activity that opened it, so the only thing holding one is the
 * service's process — and that is a background process the moment you look at another app. When
 * the system reclaims it, every window goes at once: no message, no notification, nothing to
 * reopen, because the queue, position and A-B points only ever existed in memory. Whether the
 * process was killed for memory or died of an uncaught exception makes no difference from the
 * outside — Android shows the user nothing either way. Writing the windows down is what makes
 * both survivable, and [FloatingPlayerService] puts them back when the system restarts it.
 */
object FloatingState {

    private const val PREFS = "loopr_float_state"
    private const val KEY_WINDOWS = "windows"
    private const val KEY_ATTEMPTS = "restore_attempts"
    private const val KEY_LAST_RESTORE = "last_restore_at"

    /**
     * Restores in quick succession before Loopr stops putting the windows back.
     *
     * If the reason for the kill is that this process is too big to keep, restoring it rebuilds
     * exactly the thing that got killed — so an unbounded restore is a loop that fights the system
     * for the user's battery. Three is enough to ride out a one-off and few enough to notice.
     */
    const val MAX_RESTORES = 3

    /** Two restores further apart than this are separate events rather than a loop. */
    private const val RESTORE_LOOP_MS = 2 * 60 * 1000L

    /** One window as it was: everything [FloatingWindow] needs to be rebuilt exactly. */
    data class Saved(
        val payload: FloatingHandoff.Payload,
        val widthPx: Int,
        val x: Int,
        val y: Int
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, windows: List<Saved>) {
        if (windows.isEmpty()) { clear(context); return }
        val array = JSONArray()
        windows.forEach { array.put(toJson(it)) }
        prefs(context).edit().putString(KEY_WINDOWS, array.toString()).apply()
    }

    /** What was open when we were last alive; empty when the windows were closed deliberately. */
    fun load(context: Context): List<Saved> {
        val raw = prefs(context).getString(KEY_WINDOWS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                runCatching { fromJson(array.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_WINDOWS).apply()
    }

    /**
     * Counts this restore, and says which attempt it is.
     *
     * Uses [SystemClock.elapsedRealtime] rather than the wall clock so a changed time zone can't
     * read as a loop; a reboot resets it, which leaves a stamp in the future — that is simply a
     * stale one, and the count starts again.
     */
    fun noteRestore(context: Context): Int {
        val p = prefs(context)
        val now = SystemClock.elapsedRealtime()
        val last = p.getLong(KEY_LAST_RESTORE, 0L)
        val looping = last in 1 until now && now - last < RESTORE_LOOP_MS
        val attempts = if (looping) p.getInt(KEY_ATTEMPTS, 0) + 1 else 1
        p.edit().putInt(KEY_ATTEMPTS, attempts).putLong(KEY_LAST_RESTORE, now).apply()
        return attempts
    }

    /** Windows still up a while later are proof the restore held, so the next kill starts fresh. */
    fun noteSettled(context: Context) {
        prefs(context).edit().putInt(KEY_ATTEMPTS, 0).apply()
    }

    // ---------------- JSON ----------------

    private fun toJson(saved: Saved): JSONObject {
        val p = saved.payload
        val items = JSONArray()
        p.queue.forEach { items.put(toJson(it)) }
        return JSONObject()
            .put("q", items)
            .put("i", p.index)
            .put("pos", p.positionMs)
            .put("play", p.playing)
            .put("spd", p.speedIndex)
            .put("mute", p.muted)
            .put("rsz", p.resizeIndex)
            .put("a", p.aMs)
            .put("b", p.bMs)
            .put("ext", p.externalUri?.toString() ?: JSONObject.NULL)
            .put("fr", p.folderResolved)
            .put("w", saved.widthPx)
            .put("x", saved.x)
            .put("y", saved.y)
    }

    private fun fromJson(o: JSONObject): Saved {
        val items = o.getJSONArray("q")
        val queue = (0 until items.length()).map { itemFromJson(items.getJSONObject(it)) }
        val ext = if (o.isNull("ext")) null else Uri.parse(o.getString("ext"))
        return Saved(
            payload = FloatingHandoff.Payload(
                queue = queue,
                index = o.getInt("i").coerceIn(0, maxOf(0, queue.size - 1)),
                positionMs = o.getLong("pos"),
                playing = o.getBoolean("play"),
                speedIndex = o.getInt("spd"),
                muted = o.getBoolean("mute"),
                resizeIndex = o.getInt("rsz"),
                aMs = o.getLong("a"),
                bMs = o.getLong("b"),
                externalUri = ext,
                folderResolved = o.getBoolean("fr")
            ),
            widthPx = o.getInt("w"),
            x = o.getInt("x"),
            y = o.getInt("y")
        )
    }

    private fun toJson(v: VideoItem): JSONObject = JSONObject()
        .put("id", v.id)
        .put("u", v.uri.toString())
        .put("t", v.title)
        .put("d", v.durationMs)
        .put("s", v.sizeBytes)
        .put("w", v.width)
        .put("h", v.height)
        .put("b", v.bucket)

    private fun itemFromJson(o: JSONObject) = VideoItem(
        id = o.getLong("id"),
        uri = Uri.parse(o.getString("u")),
        title = o.getString("t"),
        durationMs = o.getLong("d"),
        sizeBytes = o.getLong("s"),
        width = o.getInt("w"),
        height = o.getInt("h"),
        bucket = o.getString("b")
    )
}

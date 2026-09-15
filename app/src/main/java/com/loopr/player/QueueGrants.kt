package com.loopr.player

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Carries the read permission for a queue's videos from one Loopr component to the next.
 *
 * **The permission a file manager gives us is not ours to keep.** Sift (or any launcher) attaches
 * `FLAG_GRANT_READ_URI_PERMISSION` to the video it opens, and Android ties that grant to the
 * *activity* that received it: when the activity finishes, the grant goes. Loopr finishes that
 * activity the instant it hands a video to a floating window — so a window outlives its own right
 * to read the files it is playing.
 *
 * Nothing goes wrong at first, because the player already has the file open. It goes wrong the
 * next time it **re-opens** one: every loop of a short clip, and every ⏭ to the next video. The
 * player then gets
 *
 * ```
 * SecurityException: Permission Denial: opening provider androidx.core.content.FileProvider
 *   from com.loopr.player ... that is not exported from UID <the file manager>
 * ```
 *
 * which surfaces as `ERROR_CODE_IO_UNSPECIFIED`, and after two failures a video is skipped, then
 * the next fails the same way, until the window closes saying it can't play the video.
 *
 * It looked like a directory bug from the outside, and in a sense it is one: opening a second video
 * **from the same folder** re-grants that whole folder — the older window's file included — so its
 * access is renewed just in time and nothing is ever seen to fail. From a different folder, nothing
 * renews it.
 *
 * The cure is to pass the grant on rather than let it die with the activity: an Intent sent with
 * the same flag re-grants every URI it carries to whatever receives it, for as long as *that*
 * component lives. Sent to the service, the windows hold the files for as long as they are open;
 * sent back to the activity on ⛶, full screen holds them again.
 *
 * Only a process kill defeats this — a grant cannot outlive the process it was made to — which is
 * what [FloatingState]'s restore runs into, and why nothing here tries to.
 */
object QueueGrants {

    private const val TAG = "LooprQueue"

    /**
     * How many of the queue's videos to carry the grant for.
     *
     * Every one of them costs a record in the system's grant table, and a folder can hold
     * thousands, so this takes a window centred on what is playing rather than the lot: far enough
     * either way that ⏮/⏭ keep working through anything a person is going to walk through by hand.
     */
    const val MAX_FORWARDED = 512

    /**
     * Attaches the queue's content URIs to [intent] so the receiver is granted read access to them.
     *
     * Returns the number of URIs attached. Only `content://` URIs can be granted — a `file://` one
     * needs no grant and would make `startService`/`startActivity` throw — so anything else is
     * left out.
     */
    fun attach(intent: Intent, queue: List<VideoItem>, index: Int): Int {
        val uris = around(queue, index)
        if (uris.isEmpty()) return 0
        val clip = ClipData("Loopr queue", arrayOf("video/*"), ClipData.Item(uris.first()))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uris.size
    }

    /**
     * Sends [intent] with the queue's grants attached, falling back to sending it without them.
     *
     * Forwarding a URI we do not actually hold makes the system throw, and losing the window over a
     * grant we could not pass on would be worse than the bug this fixes: the video in hand always
     * plays, because the player still has it open. [send] is the caller's own start call.
     */
    fun sendWithGrants(
        intent: Intent,
        queue: List<VideoItem>,
        index: Int,
        send: (Intent) -> Unit
    ): Boolean {
        val granted = attach(intent, queue, index)
        return runCatching { send(intent); logd("forwarded $granted uri grants"); true }
            .getOrElse { first ->
                Log.w(TAG, "could not forward uri grants", first)
                intent.clipData = null
                runCatching { send(intent); true }.getOrDefault(false)
            }
    }

    /** The grantable URIs nearest [index], newest-first duplicates removed. */
    private fun around(queue: List<VideoItem>, index: Int): List<Uri> {
        if (queue.isEmpty()) return emptyList()
        val centre = index.coerceIn(0, queue.size - 1)
        val half = MAX_FORWARDED / 2
        val from = (centre - half).coerceAtLeast(0)
        val to = (from + MAX_FORWARDED).coerceAtMost(queue.size)
        return queue.subList(from, to)
            .map { it.uri }
            .filter { it.scheme == ContentResolver.SCHEME_CONTENT }
            .distinct()
    }

    private fun logd(msg: String) { if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, msg) }
}

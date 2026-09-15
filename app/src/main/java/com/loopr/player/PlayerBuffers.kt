package com.loopr.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Builds every [ExoPlayer] Loopr owns, with a buffer small enough that several can coexist.
 *
 * ExoPlayer's default video buffer target is **2000 × 64 KB = 128 MB** of heap, and it is a target
 * it will genuinely reach on a high-bitrate file. Loopr runs up to [FloatingPlayerService.MAX_WINDOWS]
 * floating players plus the full-screen one in a **single process** whose heap is capped at 256 MB
 * on an ordinary phone — so two players filling their default buffers is already over the limit,
 * and the loader thread dies with an uncatchable `OutOfMemoryError` that takes the process, and
 * therefore every floating window, with it.
 *
 * Measured on the reporting device (Android 15, 256 MB growth limit): one window playing a
 * 20 Mbit/s file sat at **139 MB of Dalvik heap**; floating a second video OOM'd inside a second.
 *
 * Nothing is lost by capping it. These are local files — a rebuffer costs a disk read, not a
 * network round trip — so [TARGET_BUFFER_BYTES] of read-ahead is ample, and it is the ceiling that
 * matters: [MAX_BUFFER_MS] still governs ordinary bitrates, while the byte cap is what stops a
 * 4K clip from eating the heap.
 */
@UnstableApi
object PlayerBuffers {

    /**
     * Read-ahead ceiling per player. Four of these fit inside the heap with room to spare, which
     * is the whole point: the cap is chosen from the number of players Loopr can open at once,
     * not from what one player would like.
     */
    const val TARGET_BUFFER_BYTES = 16 * 1024 * 1024

    private const val MIN_BUFFER_MS = 15_000
    private const val MAX_BUFFER_MS = 30_000
    private const val BUFFER_FOR_PLAYBACK_MS = 2_500
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

    fun loadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            // The byte cap is the safety net, so it has to win when the two disagree.
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

    /** The only place a player is built, so no caller can forget the cap. */
    fun newPlayer(context: Context): ExoPlayer =
        ExoPlayer.Builder(context).setLoadControl(loadControl()).build()
}

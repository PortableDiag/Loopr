package com.loopr.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the buffer ceiling that stops several players from eating the process's heap.
 *
 * Loopr runs one player per floating window plus the full-screen one, all in a single process.
 * ExoPlayer's own default target for video is 128 MB — so on the 256 MB heap an ordinary phone
 * gives an app, the *second* player filling its buffer is already fatal, and it is fatal in the
 * worst way: an `OutOfMemoryError` on the loader thread, which kills the process and takes every
 * floating window with it.
 *
 * Observed on the reporting device before the fix: one window on a 20 Mbit/s file sat at 139 MB of
 * heap and floating a second video died inside a second. After it, the same window sits at 26 MB
 * and three windows plus a full-screen player coexist.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlayerBufferTest {

    private val playerId = PlayerId.UNSET
    private val periodId = MediaSource.MediaPeriodId(Any())

    private fun parameters(bufferedDurationUs: Long) = LoadControl.Parameters(
        playerId,
        Timeline.EMPTY,
        periodId,
        /* playbackPositionUs = */ 0L,
        bufferedDurationUs,
        /* playbackSpeed = */ 1f,
        /* playWhenReady = */ true,
        /* rebuffering = */ false,
        /* targetLiveOffsetUs = */ C.TIME_UNSET
    )

    /**
     * The byte ceiling must be what stops the loading, not the time one.
     *
     * A second of a high-bitrate video is worth tens of megabytes, so a control that only counts
     * seconds keeps reading until the heap is gone. This asserts the reverse: with the cap's worth
     * of memory already taken and barely a second buffered, loading stops.
     */
    @Test
    fun aPlayersBufferStopsAtItsShareOfTheHeapNotAtSecondsBuffered() {
        val control = PlayerBuffers.loadControl()
        control.onPrepared(playerId)
        control.onTracksSelected(
            playerId, Timeline.EMPTY, periodId, emptyArray(), TrackGroupArray.EMPTY, emptyArray()
        )
        val allocator = control.allocator as DefaultAllocator

        assertTrue(
            "an empty buffer must keep loading",
            control.shouldContinueLoading(parameters(0L))
        )

        while (allocator.totalBytesAllocated < PlayerBuffers.TARGET_BUFFER_BYTES) allocator.allocate()

        assertFalse(
            "loading must stop once the cap is reached, however little is buffered in seconds",
            control.shouldContinueLoading(parameters(1_000_000L))
        )
        control.onReleased(playerId)
    }

    /**
     * Every player Loopr can have open at once has to fit, together, in this device's heap.
     *
     * This is the arithmetic the cap was chosen by, written down where raising either number
     * without redoing it will fail: windows are capped at [FloatingPlayerService.MAX_WINDOWS] and
     * the full-screen player makes one more.
     */
    @Test
    fun everyPlayerLooprCanOpenAtOnceFitsInTheHeap() {
        val players = FloatingPlayerService.MAX_WINDOWS + 1
        val buffers = players.toLong() * PlayerBuffers.TARGET_BUFFER_BYTES
        val heap = Runtime.getRuntime().maxMemory()
        assertTrue(
            "$players players want ${buffers / 1024 / 1024} MB of buffer out of a " +
                "${heap / 1024 / 1024} MB heap — no room for the app itself",
            buffers < heap / 2
        )
    }
}

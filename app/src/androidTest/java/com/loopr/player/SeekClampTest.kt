package com.loopr.player

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The seek clamp, which crashed the whole app.
 *
 * Reported from the device on 2026-09-12 as floating windows dying, and found in that phone's
 * logcat as a fatal `IllegalArgumentException` on the main thread:
 *
 * ```
 * Cannot coerce value to an empty range: maximum -6424 is less than minimum 0.
 *   at PlayerActivity.seekToAbs(PlayerActivity.kt:963)
 *   at PlayerActivity.seekBy(PlayerActivity.kt:966)
 *   at setupButtons$lambda$53(PlayerActivity.kt:1098)   // the rewind button
 * ```
 *
 * Loopr is a single process, so an uncaught exception in the activity takes every open floating
 * window with it — which is how a seek-bar bug presents as popups vanishing.
 *
 * Pure arithmetic, so it needs no device state; it lives in `androidTest` only because the project
 * has no JVM test source set.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SeekClampTest {

    /** Position and step from the reported crash: 3576 ms in, rewind 10 s, duration not known. */
    private val reportedTarget = 3576L - 10_000L

    @Test
    fun rewindingBeforeTheDurationIsKnownClampsToZero() {
        assertEquals(0L, PlayerActivity.clampSeek(reportedTarget, 0L))
    }

    /**
     * The exact expression that shipped, kept so the fix cannot be "simplified" back into it.
     * Folding both ends into one range makes the fallback bound the very value being clamped —
     * fine while it is positive, an empty range the moment it is not.
     */
    @Test
    fun theShippedExpressionThrewOnThatSameInput() {
        val absMs = reportedTarget
        val fullDurationMs = 0L
        try {
            val max = if (fullDurationMs > 0) fullDurationMs else absMs
            absMs.coerceIn(0L, max)
            fail("the 1.14 clamp was expected to throw on $absMs")
        } catch (expected: IllegalArgumentException) {
            // This is the crash. Anything that does not throw here is no longer that code.
        }
    }

    @Test
    fun rewindingWithAKnownDurationStillClampsToZero() {
        assertEquals(0L, PlayerActivity.clampSeek(-1L, 600_000L))
    }

    @Test
    fun seekingPastTheEndStopsAtTheDuration() {
        assertEquals(600_000L, PlayerActivity.clampSeek(700_000L, 600_000L))
    }

    /**
     * With no duration there is nothing to clamp against, so a forward seek is passed through and
     * ExoPlayer bounds it itself. Deliberate: guessing a ceiling here would break long videos.
     */
    @Test
    fun seekingForwardWithNoDurationIsLeftAlone() {
        assertEquals(700_000L, PlayerActivity.clampSeek(700_000L, 0L))
    }

    @Test
    fun ordinarySeeksAreUntouched() {
        assertEquals(42_000L, PlayerActivity.clampSeek(42_000L, 600_000L))
    }
}

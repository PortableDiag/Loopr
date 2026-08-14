package com.loopr.player

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Drives a real floating window on a device.
 *
 * A floating window is an overlay drawn by a service, so nothing in the accessibility tree or in
 * `adb shell input` can reach it — its pinch-resize can only be exercised by injecting genuine
 * multi-pointer events, which is what `UiAutomation` is for. The window's geometry is read back
 * from `dumpsys window`, the same ground truth used by hand.
 *
 * Requires: a video in MediaStore, and "display over other apps" allowed:
 *   adb shell appops set com.loopr.player SYSTEM_ALERT_WINDOW allow
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FloatingWindowTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    private fun density() = context.resources.displayMetrics.density
    private fun screenWidth() = context.resources.displayMetrics.widthPixels

    @Before
    fun openWindow() {
        val video = firstVideo()
        assumeTrue("no video in MediaStore to float", video != null)
        closeAll()

        FloatingHandoff.offerToFloating(
            FloatingHandoff.Payload(
                queue = listOf(video!!),
                index = 0,
                positionMs = 0L,
                playing = true,
                speedIndex = 3,
                muted = true,
                resizeIndex = 0,
                aMs = PlayerActivity.UNSET,
                bMs = PlayerActivity.UNSET,
                externalUri = null,
                folderResolved = true
            )
        )
        ContextCompat.startForegroundService(
            context,
            Intent(context, FloatingPlayerService::class.java)
                .setAction(FloatingPlayerService.ACTION_ADD)
        )
        waitFor("a floating window to open") { windowFrames().size == 1 }
        // Long enough for the first frame to settle the aspect ratio *and* for the controls to
        // auto-hide — a gesture that starts on the play button never reaches the window itself.
        SystemClock.sleep(4500)
    }

    @After
    fun tearDown() = closeAll()

    @Test
    fun pinchOutGrowsTheWindowAndKeepsItsAspectRatio() {
        val before = windowFrames().single()
        val aspectBefore = before.width().toFloat() / before.height()

        pinch(before, fromGap = 60, toGap0 = 240)

        val after = waitForFrameChange(before)
        assertTrue(
            "pinching out should widen the window: ${before.width()} -> ${after.width()}",
            after.width() > before.width()
        )
        val aspectAfter = after.width().toFloat() / after.height()
        assertTrue(
            "aspect ratio should survive the resize: $aspectBefore -> $aspectAfter",
            abs(aspectAfter - aspectBefore) < 0.05f
        )
    }

    @Test
    fun pinchOutStopsAtSixtyPercentOfTheScreen() {
        repeat(3) { pinch(windowFrames().single(), fromGap = 40, toGap0 = 460) }
        SystemClock.sleep(500)

        val ceiling = (screenWidth() * FloatingWindow.MAX_WIDTH_FRACTION).roundToInt()
        val width = windowFrames().single().width()
        assertTrue("width $width should not exceed the $ceiling ceiling", width <= ceiling + 2)
        assertTrue("width $width should have grown towards the ceiling", width >= ceiling - 2)
    }

    @Test
    fun pinchInStopsAtTheMinimumWidth() {
        // Both fingers have to start inside the window, or the gesture goes to whatever is behind it.
        repeat(3) {
            val frame = windowFrames().single()
            pinch(frame, fromGap = (frame.width() * 0.35f).roundToInt(), toGap0 = 16)
        }
        SystemClock.sleep(500)

        val floor = (FloatingWindow.MIN_WIDTH_DP * density()).roundToInt()
        val width = windowFrames().single().width()
        assertTrue("width $width should not go below the $floor floor", width >= floor - 2)
        assertTrue("width $width should have shrunk to the floor", width <= floor + 2)
    }

    @Test
    fun theWindowStaysOnScreenWhenDraggedOff() {
        val before = windowFrames().single()
        drag(before, dx = 2000, dy = 4000)
        val after = waitForFrameChange(before)

        val metrics = context.resources.displayMetrics
        assertTrue("right edge ${after.right} is off screen", after.right <= metrics.widthPixels)
        assertTrue("bottom edge ${after.bottom} is off screen", after.bottom <= metrics.heightPixels)
        assertEquals("window should keep its size", before.width(), after.width())
    }

    // ---------------- helpers ----------------

    private fun firstVideo(): VideoItem? {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return VideoItem(
                    id, ContentUris.withAppendedId(collection, id),
                    c.getString(1) ?: "Video", 0, 0, 0, 0, ""
                )
            }
        }
        return null
    }

    private fun closeAll() {
        context.startService(
            Intent(context, FloatingPlayerService::class.java)
                .setAction(FloatingPlayerService.ACTION_CLOSE_ALL)
        )
        runCatching { waitFor("windows to close") { windowFrames().isEmpty() } }
    }

    /** The live overlay geometry, straight from the window manager. */
    private fun windowFrames(): List<Rect> {
        val dump = shell("dumpsys window windows")
        val frames = mutableListOf<Rect>()
        var inOurWindow = false
        for (line in dump.lineSequence()) {
            if (line.contains("Window{") && line.contains("com.loopr.player}")) inOurWindow = true
            else if (line.contains("Window #")) inOurWindow = false
            if (!inOurWindow) continue
            val m = FRAME.find(line) ?: continue
            val (l, t, r, b) = m.destructured
            frames.add(Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt()))
            inOurWindow = false
        }
        return frames
    }

    private fun shell(cmd: String): String =
        automation.executeShellCommand(cmd).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                .bufferedReader().readText()
        }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (cond()) return
            SystemClock.sleep(250)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun waitForFrameChange(before: Rect): Rect {
        var latest = before
        runCatching {
            waitFor("the window to move or resize") {
                latest = windowFrames().singleOrNull() ?: latest
                latest != before
            }
        }
        return latest
    }

    /**
     * Two fingers either side of the window's centre, moved apart or together. Anchored low in the
     * window, clear of the transport buttons, and kept on screen so no pointer is cancelled.
     */
    private fun pinch(frame: Rect, fromGap: Int, toGap0: Int, steps: Int = 12) {
        val cx = frame.centerX().toFloat()
        val cy = frame.top + frame.height() * 0.85f
        val room = minOf(cx, context.resources.displayMetrics.widthPixels - cx).toInt() - 8
        val toGap = minOf(toGap0, room)
        val down = SystemClock.uptimeMillis()

        inject(down, down, MotionEvent.ACTION_DOWN, listOf(cx - fromGap to cy))
        inject(
            down, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(cx - fromGap to cy, cx + fromGap to cy)
        )
        for (i in 1..steps) {
            val gap = fromGap + (toGap - fromGap) * i / steps
            inject(
                down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                listOf(cx - gap to cy, cx + gap to cy)
            )
            SystemClock.sleep(16)
        }
        inject(
            down, SystemClock.uptimeMillis(),
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(cx - toGap to cy, cx + toGap to cy)
        )
        inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, listOf(cx - toGap to cy))
    }

    private fun drag(frame: Rect, dx: Int, dy: Int, steps: Int = 10) {
        val x = frame.centerX().toFloat()
        val y = frame.top + frame.height() * 0.85f   // clear of the transport buttons
        val down = SystemClock.uptimeMillis()
        inject(down, down, MotionEvent.ACTION_DOWN, listOf(x to y))
        for (i in 1..steps) {
            inject(
                down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                listOf((x + dx * i / steps) to (y + dy * i / steps))
            )
            SystemClock.sleep(16)
        }
        inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
            listOf((x + dx) to (y + dy)))
    }

    private fun inject(downTime: Long, eventTime: Long, action: Int, points: List<Pair<Float, Float>>) {
        val props = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].first; y = points[i].second; pressure = 1f; size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, eventTime, action, points.size, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        automation.injectInputEvent(event, true)
        event.recycle()
    }

    private companion object {
        val FRAME = Regex("""frame=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
    }
}

package com.loopr.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.Window
import com.loopr.player.api.MainThread
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What `loopr.screenshot` sees: the picture alone (`video`), or the player as drawn, controls and
 * all (`window`). A SurfaceView's picture is a separate layer the window's own pixels never hold,
 * so a full-screen `window` shot is the two copied and laid one over the other.
 */
object Screenshot {

    /** Gathered on the main thread; [capture] then runs on the caller's thread and may block. */
    fun interface Target { fun capture(): Bitmap }

    private val copier by lazy { Handler(HandlerThread("LooprScreenshot").apply { start() }.looper) }

    fun surface(view: SurfaceView): Target {
        val w = view.width; val h = view.height
        return Target { pixelCopy(w, h) { bmp, done -> PixelCopy.request(view, bmp, done, copier) } }
    }

    fun texture(view: TextureView): Target = Target {
        MainThread.get { view.bitmap } ?: throw IllegalStateException("The picture is not drawn yet")
    }

    /** A whole activity window, with [surface] (its SurfaceView picture) laid underneath. */
    fun window(window: Window, under: SurfaceView?): Target {
        val decor = window.decorView
        val w = decor.width; val h = decor.height
        val at = IntArray(2).also { under?.getLocationInWindow(it) }
        val video = under?.let { surface(it) }
        return Target {
            val top = pixelCopy(w, h) { bmp, done -> PixelCopy.request(window, bmp, done, copier) }
            if (video == null) return@Target top
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(android.graphics.Color.BLACK)
                runCatching { video.capture() }.getOrNull()?.let { drawBitmap(it, at[0].toFloat(), at[1].toFloat(), null) }
                drawBitmap(top, 0f, 0f, null)
            }
            out
        }
    }

    /**
     * A floating window: an overlay has no window of ours to copy, so its views are drawn by hand,
     * the TextureView's picture first and everything after it in [root] (badges, controls) on top.
     */
    fun overlay(root: android.view.ViewGroup, texture: TextureView?, pictureHolder: View?): Target = Target {
        MainThread.get {
            val out = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(android.graphics.Color.BLACK)
            texture?.bitmap?.let { bmp ->
                val at = IntArray(2); val base = IntArray(2)
                texture.getLocationInWindow(at); root.getLocationInWindow(base)
                c.drawBitmap(bmp, (at[0] - base[0]).toFloat(), (at[1] - base[1]).toFloat(), null)
            }
            val from = pictureHolder?.let { root.indexOfChild(it) + 1 } ?: 0
            for (i in from until root.childCount) {
                val child = root.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                c.save(); c.translate(child.left.toFloat(), child.top.toFloat()); child.draw(c); c.restore()
            }
            out
        }
    }

    private fun pixelCopy(w: Int, h: Int, request: (Bitmap, PixelCopy.OnPixelCopyFinishedListener) -> Unit): Bitmap {
        if (w <= 0 || h <= 0) throw IllegalStateException("Nothing is on screen to copy")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        request(bmp) { r -> result = r; latch.countDown() }
        if (!latch.await(5, TimeUnit.SECONDS)) throw IllegalStateException("The screen copy did not finish in 5 s")
        if (result != PixelCopy.SUCCESS) throw IllegalStateException(
            when (result) {
                PixelCopy.ERROR_SOURCE_NO_DATA -> "Nothing has been drawn there yet"
                PixelCopy.ERROR_SOURCE_INVALID -> "The picture is not on screen (in the background, or the screen is off)"
                PixelCopy.ERROR_TIMEOUT -> "The screen copy timed out"
                else -> "Android could not copy the screen (PixelCopy $result)"
            }
        )
        return bmp
    }

    fun jpeg(bmp: Bitmap, quality: Int = 85): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}

package com.loopr.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatSeekBar

/**
 * SeekBar that draws A and B loop markers plus a highlighted region between them,
 * on top of the normal track.
 */
class AbSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatSeekBar(context, attrs) {

    private var aFrac = -1f
    private var bFrac = -1f

    private val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 124, 92, 255)
    }
    private val markerAPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3DDC97"); strokeWidth = dp(2.5f)
    }
    private val markerBPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5C7A"); strokeWidth = dp(2.5f)
    }

    /** fractions in 0..1, or negative to clear. */
    fun setMarkers(a: Float, b: Float) {
        aFrac = a; bFrac = b
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val usable = width - paddingLeft - paddingRight
        if (usable <= 0) return
        val cy = height / 2f
        val regionH = dp(3f)

        if (aFrac in 0f..1f && bFrac in 0f..1f && bFrac > aFrac) {
            val left = paddingLeft + usable * aFrac
            val right = paddingLeft + usable * bFrac
            canvas.drawRect(left, cy - regionH, right, cy + regionH, regionPaint)
        }
        if (aFrac in 0f..1f) {
            val x = paddingLeft + usable * aFrac
            canvas.drawLine(x, cy - dp(8f), x, cy + dp(8f), markerAPaint)
        }
        if (bFrac in 0f..1f) {
            val x = paddingLeft + usable * bFrac
            canvas.drawLine(x, cy - dp(8f), x, cy + dp(8f), markerBPaint)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}

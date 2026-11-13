package com.loopr.player.model

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * The orbit camera around a model, in the units Loopr Desktop's `loopr.model` uses so one agent
 * prompt drives both: degrees, a zoom where 1 frames the whole model, a pan in model radii.
 *
 * - **yaw 0 is the model's front** (+Z, after a VRM 0.x avatar has been turned round), 180 its back,
 *   90 looks at its left side from the viewer's right.
 * - **pitch + looks down from above**, limited to ±[MAX_PITCH] so the camera never flips over a pole.
 *
 * Pure math, no renderer: [eye] and [target] are what the scene hands Filament's `lookAt`.
 */
class ModelCamera {
    var yaw = 0.0
        set(v) { field = normalise(v) }
    var pitch = DEFAULT_PITCH
        set(v) { field = v.coerceIn(-MAX_PITCH, MAX_PITCH) }
    var zoom = 1.0
        set(v) { field = v.coerceIn(MIN_ZOOM, MAX_ZOOM) }
    /** Right and up, in model radii, within ±[MAX_PAN]. */
    var panX = 0.0
        set(v) { field = v.coerceIn(-MAX_PAN, MAX_PAN) }
    var panY = 0.0
        set(v) { field = v.coerceIn(-MAX_PAN, MAX_PAN) }

    /** The model's bounding sphere; set once it is loaded. */
    var center = doubleArrayOf(0.0, 0.0, 0.0)
    var radius = 1.0
        set(v) { field = if (v.isFinite() && v > 1e-6) v else 1.0 }

    /** Vertical field of view, degrees. */
    val fovDeg = 35.0

    fun reset() { yaw = 0.0; pitch = DEFAULT_PITCH; zoom = 1.0; panX = 0.0; panY = 0.0 }

    fun view(name: String): Boolean {
        when (name) {
            "front" -> { yaw = 0.0; pitch = DEFAULT_PITCH }
            "back" -> { yaw = 180.0; pitch = DEFAULT_PITCH }
            "left" -> { yaw = -90.0; pitch = DEFAULT_PITCH }
            "right" -> { yaw = 90.0; pitch = DEFAULT_PITCH }
            "top" -> { yaw = 0.0; pitch = MAX_PITCH }
            "bottom" -> { yaw = 0.0; pitch = -MAX_PITCH }
            else -> return false
        }
        panX = 0.0; panY = 0.0
        return true
    }

    /** How far from the target the whole sphere just fits the narrower side, times [zoom]. */
    fun distance(aspect: Double): Double {
        val half = Math.toRadians(fovDeg / 2)
        // Horizontal half-angle for a portrait screen, where the width is the tighter fit.
        val fit = if (aspect < 1.0) kotlin.math.atan(tan(half) * aspect) else half
        return radius / sin(fit) * zoom
    }

    /** Unit vector from the target towards the eye. */
    private fun back(): DoubleArray {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        return doubleArrayOf(sin(y) * cos(p), sin(p), cos(y) * cos(p))
    }

    /** The camera's right and up, for panning in screen directions. */
    private fun basis(): Pair<DoubleArray, DoubleArray> {
        val y = Math.toRadians(yaw)
        val p = Math.toRadians(pitch)
        val right = doubleArrayOf(cos(y), 0.0, -sin(y))
        val up = doubleArrayOf(-sin(y) * sin(p), cos(p), -cos(y) * sin(p))
        return right to up
    }

    fun target(): DoubleArray {
        val (r, u) = basis()
        return DoubleArray(3) { center[it] + (r[it] * panX + u[it] * panY) * radius }
    }

    fun eye(aspect: Double): DoubleArray {
        val t = target()
        val b = back()
        val d = distance(aspect)
        return DoubleArray(3) { t[it] + b[it] * d }
    }

    fun near(aspect: Double): Double = max(distance(aspect) - radius * 4, distance(aspect) * 0.01)
    fun far(aspect: Double): Double = distance(aspect) + radius * 8

    /** Screen drag → turn: [DP_PER_DEGREE] dp of finger a degree. */
    fun drag(dxPx: Float, dyPx: Float, density: Float) {
        yaw -= dxPx / density / DP_PER_DEGREE
        pitch += dyPx / density / DP_PER_DEGREE
    }

    /** Two-finger drag → pan, so the point under the fingers stays under them. */
    fun panByPixels(dxPx: Float, dyPx: Float, viewHeightPx: Int, aspect: Double) {
        if (viewHeightPx <= 0) return
        val worldPerPx = 2 * distance(aspect) * tan(Math.toRadians(fovDeg / 2)) / viewHeightPx
        panX -= dxPx * worldPerPx / radius
        panY += dyPx * worldPerPx / radius
    }

    companion object {
        const val MAX_PITCH = 89.5
        const val MIN_ZOOM = 0.05
        const val MAX_ZOOM = 20.0
        const val MAX_PAN = 5.0
        /** A slight look down, as a person standing in front of an avatar sees it. */
        const val DEFAULT_PITCH = 4.6
        private const val DP_PER_DEGREE = 2.5f

        fun normalise(deg: Double): Double {
            var d = deg % 360.0
            if (d > 180) d -= 360
            if (d <= -180) d += 360
            return d
        }
    }
}

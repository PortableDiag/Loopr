package com.loopr.player.model

import android.graphics.Bitmap

/**
 * A 3D model view as the local API sees it. It shares [com.loopr.player.Players]' id numbers, so a
 * `player_id` names exactly one thing, a video player or a model view — Loopr Desktop's rule.
 * Main thread only.
 */
interface ControllableModel {
    val playerId: Int
    fun modelStatus(): ModelStatus
    fun modelQueue(): List<ModelItem>

    fun rotateTo(yaw: Double?, pitch: Double?)
    fun rotateBy(yaw: Double, pitch: Double)
    fun setZoom(zoom: Double)
    fun panTo(x: Double?, y: Double?)
    fun setView(name: String): Boolean
    fun resetView()
    fun setSpin(on: Boolean, speed: Double?)
    fun setLight(light: Float)
    fun setBackground(name: String)
    fun next(): Boolean
    fun previous()
    fun jumpTo(index: Int)
    fun closeModel()

    /** Prepared on the main thread; `capture` may block, so it runs off it. */
    fun screenshotTarget(what: String): com.loopr.player.Screenshot.Target
}

data class ModelStatus(
    val name: String,
    val uri: String,
    val path: String?,
    val index: Int,
    val queueSize: Int,
    val loading: Boolean,
    val error: String?,
    val meta: ModelMeta?,
    val size: DoubleArray?,
    val yaw: Double,
    val pitch: Double,
    val zoom: Double,
    val panX: Double,
    val panY: Double,
    val spin: Boolean,
    val spinSpeed: Double,
    val light: Float,
    val background: String
)

/** The model views that are open now. Main thread only. */
object Models {
    private val open = LinkedHashMap<Int, ControllableModel>()

    fun register(m: ControllableModel) { open[m.playerId] = m }
    fun unregister(m: ControllableModel) { open.remove(m.playerId) }
    fun all(): List<ControllableModel> = open.values.toList()
    fun get(id: Int): ControllableModel? = open[id]
}

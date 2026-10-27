package com.loopr.player

import android.graphics.Rect

/**
 * One player as the local API sees it: the full-screen [PlayerActivity] or a [FloatingWindow].
 *
 * Every call lands on the same code the player's own buttons run, so the API cannot do anything a
 * person couldn't — it just doesn't have to tap. All of it is main-thread only.
 */
interface ControllablePlayer {
    val playerId: Int
    fun status(): PlayerStatus
    fun queueItems(): List<VideoItem>

    /** Starts playing, lifting the player out of `STATE_IDLE` or `STATE_ENDED` as the button does. */
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun jumpTo(index: Int)

    fun setSpeedIndex(index: Int)
    fun setMuted(muted: Boolean)
    fun setResizeIndex(index: Int)
    /** Remembered for the next video opened, as the chip does. */
    fun setRepeatMode(mode: Int)
    fun setShuffle(on: Boolean)
    fun setZoom(scale: Float)

    /** Points already checked by the caller: `0 <= a < b`. */
    fun setAbPoints(aMs: Long, bMs: Long)
    fun clearAbPoints()

    fun closePlayer()
}

/** What a player is doing, read once on the main thread. */
data class PlayerStatus(
    val kind: String,
    val title: String,
    val uri: String,
    val index: Int,
    val queueSize: Int,
    val positionMs: Long,
    /** 0 until the player knows the length. */
    val durationMs: Long,
    val playing: Boolean,
    val playWhenReady: Boolean,
    val playbackState: Int,
    val speed: Float,
    val muted: Boolean,
    val resizeIndex: Int,
    val repeatMode: Int,
    val shuffle: Boolean,
    val aMs: Long,
    val bMs: Long,
    val zoom: Float,
    /** A floating window's place and size on screen; null full screen. */
    val bounds: Rect? = null,
    val inPip: Boolean = false
) {
    companion object {
        const val FULL_SCREEN = "full_screen"
        const val FLOATING = "floating"
    }
}

/** The players that are open now, numbered for the API. Main thread only. */
object Players {
    private var lastId = 0
    private val open = LinkedHashMap<Int, ControllablePlayer>()

    fun newId(): Int = ++lastId

    fun register(p: ControllablePlayer) { open[p.playerId] = p }

    fun unregister(p: ControllablePlayer) { open.remove(p.playerId) }

    fun all(): List<ControllablePlayer> = open.values.toList()

    fun get(id: Int): ControllablePlayer? = open[id]
}

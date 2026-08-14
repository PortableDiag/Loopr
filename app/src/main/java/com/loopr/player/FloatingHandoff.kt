package com.loopr.player

import android.net.Uri
import java.util.ArrayDeque

/**
 * Carries a player's whole state between [PlayerActivity] and [FloatingPlayerService].
 *
 * Both live in the same process, so the state travels through a static slot rather than the
 * Intent: a folder queue runs to hundreds of items and would blow the Binder transaction limit
 * (the same reason [PlayQueue] exists). Kept separate from [PlayQueue] so handing a video to a
 * window never clobbers the library's queue, and one deque per direction so a hand-off going one
 * way can't consume the other's payload.
 */
object FloatingHandoff {

    /** Everything the far side needs to carry on exactly where this one left off. */
    data class Payload(
        val queue: List<VideoItem>,
        val index: Int,
        val positionMs: Long,
        val playing: Boolean,
        val speedIndex: Int,
        val muted: Boolean,
        val resizeIndex: Int,
        /** A-B loop points, [PlayerActivity.UNSET] when unset — they travel with the video. */
        val aMs: Long,
        val bMs: Long,
        /** Set when the video came from another app, so Next/Prev can still explain a lone file. */
        val externalUri: Uri?,
        val folderResolved: Boolean
    )

    private val toFloating = ArrayDeque<Payload>()
    private val toActivity = ArrayDeque<Payload>()

    @Synchronized fun offerToFloating(p: Payload) { toFloating.addLast(p) }

    @Synchronized fun takeToFloating(): Payload? = toFloating.pollFirst()

    @Synchronized fun offerToActivity(p: Payload) { toActivity.addLast(p) }

    @Synchronized fun takeToActivity(): Payload? = toActivity.pollFirst()
}

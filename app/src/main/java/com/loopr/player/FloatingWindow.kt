package com.loopr.player

import android.content.Context
import android.content.Intent
import android.graphics.Insets
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.loopr.player.databinding.FloatingWindowBinding
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One floating video: its own [ExoPlayer] drawn into a `TYPE_APPLICATION_OVERLAY` window that the
 * user can drag and pinch-resize.
 *
 * Each window is self-contained — it holds the folder queue and index so ⏮/⏭ traverse exactly as
 * they do full screen, and it runs its own A-B poll loop, because the A-B points travel with the
 * video when it is floated. [FloatingPlayerService] owns the set of them.
 */
@UnstableApi
class FloatingWindow(
    private val service: FloatingPlayerService,
    private val payload: FloatingHandoff.Payload,
    startWidthPx: Int,
    startX: Int,
    startY: Int
) {

    companion object {
        /** Same tag as the queue diagnostics: `setprop log.tag.LooprQueue DEBUG` turns both on. */
        private const val TAG = "LooprQueue"
        private const val HIDE_DELAY = 3000L
        /** Below this a window is a thumbnail, not a video. */
        const val MIN_WIDTH_DP = 160
        /** Above this it stops being a window you can see past. */
        const val MAX_WIDTH_FRACTION = 0.6f

        /** How often the stall watchdog looks; cheap enough to run for the window's whole life. */
        private const val STALL_CHECK_MS = 1000L
        /**
         * No frames for this long, while the player insists it is playing, means the picture is
         * gone. Well clear of the gap a seek or an A-B loop-back leaves.
         */
        private const val STALL_AFTER_MS = 3000L
        /**
         * Decoded frames still arriving but none of them reaching the screen for this long means
         * the view has stopped compositing. Longer than [STALL_AFTER_MS] because this is the
         * quieter signal of the two and a false trip costs a visible surface rebuild.
         */
        private const val COMPOSITE_STALL_AFTER_MS = 5000L
        /**
         * A player that wants to play but is sitting in `STATE_IDLE` has stopped for good: an
         * error puts it there and nothing lifts it out but [ExoPlayer.prepare]. There is nothing
         * to wait for, so this only needs to be long enough to clear [recoverFromError]'s own
         * retry.
         */
        private const val IDLE_STALL_AFTER_MS = 3000L
        /** Buffering this long on a local file is not buffering, it is a wedge. */
        private const val BUFFER_STALL_AFTER_MS = 12000L
        /** Breathing space before retrying a failed item — a reclaimed decoder needs a moment. */
        private const val ERROR_RETRY_DELAY_MS = 500L
        /** How many videos may be skipped past before the queue is declared unplayable. */
        private const val MAX_SKIPS_AFTER_ERROR = 3
        /**
         * Playing for this long is what counts as having recovered from an error.
         *
         * Not a rendered frame: a file that is broken at one point plays a frame, dies, is retried
         * at the same point and plays that frame again — so a frame resets the tally on every lap
         * and the escalation that should move the queue past it never happens.
         */
        private const val FAILURE_FORGET_MS = 8000L
    }

    private val themed = ContextThemeWrapper(service, R.style.Theme_Loopr)
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val binding = FloatingWindowBinding.inflate(LayoutInflater.from(themed))

    private val player: ExoPlayer = ExoPlayer.Builder(service).build()

    private var queue: List<VideoItem> = payload.queue
    private var currentIndex = payload.index
    private var externalUri = payload.externalUri
    private var folderResolved = payload.folderResolved

    private var aMs = payload.aMs
    private var bMs = payload.bMs

    private var userRepeatMode = Player.REPEAT_MODE_ALL
    private var controlsVisible = true
    private var closed = false

    /** Video aspect, kept so resizing preserves it. 16:9 until the first frame reports otherwise. */
    private var aspect = 16f / 9f
    private var widthPx = startWidthPx
    private var heightPx = (startWidthPx / aspect).roundToInt()

    private val lp = WindowManager.LayoutParams(
        widthPx, heightPx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = startX
        y = startY
    }

    // drag state
    private var downRawX = 0f
    private var downRawY = 0f
    private var downLpX = 0
    private var downLpY = 0
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    // pinch state. Deliberately not a ScaleGestureDetector: its minimum scaling span is around
    // 27mm — wider than a small floating window — so a pinch that starts inside the window never
    // begins, and shrinking one becomes impossible. Two pointers and a distance ratio have no
    // such floor.
    private var pinching = false
    private var pinchStartDistance = 0f
    private var pinchStartWidth = 0

    /**
     * Shows the window and starts playing. Separate from the constructor because it leans on the
     * listeners and runnables declared below — they only exist once construction has finished.
     */
    fun start() {
        val prefs = service.getSharedPreferences(ThemeManager.PREFS, Context.MODE_PRIVATE)
        userRepeatMode = prefs.getInt(PlayerActivity.KEY_REPEAT, Player.REPEAT_MODE_ALL)

        binding.playerView.player = player
        binding.playerView.resizeMode =
            PlayerActivity.RESIZE_MODES[payload.resizeIndex.coerceIn(0, PlayerActivity.RESIZE_MODES.size - 1)]
        watchComposition()

        setupTouch()
        setupButtons()
        updateAbBadge()
        updateSkipButtons()

        wm.addView(binding.root, lp)

        player.addListener(playerListener)
        player.setMediaItems(queue.map { toMediaItem(it) }, currentIndex, payload.positionMs)
        player.repeatMode = userRepeatMode
        player.shuffleModeEnabled = prefs.getBoolean(PlayerActivity.KEY_SHUFFLE, false)
        player.setPlaybackSpeed(
            PlayerActivity.SPEEDS[payload.speedIndex.coerceIn(0, PlayerActivity.SPEEDS.size - 1)]
        )
        player.volume = if (payload.muted) 0f else 1f
        player.prepare()
        player.playWhenReady = payload.playing

        updateLoopWatcher()
        noteFrames()
        handler.postDelayed(watchdogRunnable, STALL_CHECK_MS)
        scheduleHide()
        logd(
            "floating opened queue size=${queue.size} index=$currentIndex " +
                "pos=${payload.positionMs} ab=${if (abActive()) "${aMs}-${bMs}" else "none"}"
        )
    }

    val title: String get() = queue.getOrNull(currentIndex)?.title ?: ""

    private fun logd(msg: String) { if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, msg) }

    // ---------------- Player ----------------

    private fun toMediaItem(v: VideoItem): MediaItem =
        MediaItem.Builder()
            .setUri(v.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build())
            .build()

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            logd("floating state=$state playWhenReady=${player.playWhenReady} pos=${player.currentPosition}")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.btnPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            if (isPlaying) scheduleHide() else handler.removeCallbacks(hideRunnable)
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val idx = player.currentMediaItemIndex
            if (idx != currentIndex) {
                // A different video: A-B belonged to the one we've left, exactly as full screen.
                currentIndex = idx
                aMs = PlayerActivity.UNSET
                bMs = PlayerActivity.UNSET
                updateLoopWatcher()
                updateAbBadge()
            }
            logd("floating now playing index=$idx/${queue.size} title=$title")
            service.onWindowTitleChanged()
        }

        override fun onRenderedFirstFrame() = noteFrames()

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                val par = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
                aspect = (videoSize.width * par) / videoSize.height
                resizeTo(widthPx)
            }
        }

        override fun onPlayerError(error: PlaybackException) = recoverFromError(error)
    }

    // ---------------- Error recovery ----------------

    /** Consecutive failures of one video, so a retry can't spin on a file that will never play. */
    private var failuresOnItem = 0
    private var failingIndex = -1
    /** Videos skipped past since playback last worked, so a dead queue can't be walked forever. */
    private var itemsSkipped = 0
    /** When the last failure happened, so [FAILURE_FORGET_MS] of playback can clear the tally. */
    private var lastErrorAtMs = 0L

    /**
     * Puts a failed player back to work.
     *
     * An error leaves ExoPlayer in [Player.STATE_IDLE], and **an idle player never restarts on its
     * own**: `seekTo` does not lift it, and neither does `playWhenReady`. So every branch here has
     * to either call [ExoPlayer.prepare] or close the window. Anything else strands the window as
     * a black rectangle with no sound and a play button that does nothing — which is exactly what
     * the old `seekToNextMediaItem()`-without-prepare did, and what the stall watchdog could not
     * see, because it only looks while the player claims to be playing.
     */
    private fun recoverFromError(error: PlaybackException) {
        if (closed) return
        val index = player.currentMediaItemIndex
        if (index != failingIndex) { failingIndex = index; failuresOnItem = 0 }
        failuresOnItem++
        lastErrorAtMs = SystemClock.elapsedRealtime()
        val resumeAt = player.currentPosition

        // Hardware decoders are a shared resource, and the system takes one back from a background
        // window the moment something in the foreground wants it. The platform calls that
        // recoverable and means it — so retry before believing the device is out of decoders.
        val decoderProblem = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

        logd("floating error code=${error.errorCodeName} index=$index attempt=$failuresOnItem " +
            "skipped=$itemsSkipped decoder=$decoderProblem pos=$resumeAt")

        when {
            // First failure of this video: put it back exactly where it was. A momentary fault
            // costs the viewer nothing this way, and skipping the video they were watching would
            // be the wrong cure for it.
            failuresOnItem <= 1 -> retryAfterError { restartAt(index, resumeAt) }
            // A decoder that will not come back is not the file's fault, so moving through the
            // queue cannot help. Say what happened and close instead of churning.
            decoderProblem -> giveUp(R.string.float_no_decoder)
            // Twice on the same video: it is the file. Move past it — with the prepare whose
            // absence used to leave the window stranded.
            itemsSkipped < MAX_SKIPS_AFTER_ERROR && player.hasNextMediaItem() ->
                retryAfterError { itemsSkipped++; player.seekToNextMediaItem(); restartHere() }
            else -> giveUp(R.string.float_cant_play)
        }
    }

    /** Retries off the error callback and after a pause, so a hard failure can't recurse tightly. */
    private fun retryAfterError(action: () -> Unit) =
        handler.postDelayed({ if (!closed) action() }, ERROR_RETRY_DELAY_MS)

    private fun restartAt(index: Int, positionMs: Long) {
        player.seekTo(index, positionMs)
        restartHere()
    }

    /** [ExoPlayer.prepare] is the step that lifts an idle player; [ExoPlayer.play] is not. */
    private fun restartHere() {
        player.prepare()
        player.play()
    }

    /**
     * Closes the window and says why.
     *
     * Logged as well as toasted, because the toast is not guaranteed to be seen: Android
     * suppresses toasts from an app whose notifications are turned off, so on a phone where that
     * is the case every reason Loopr gives for closing a window is swallowed and the window simply
     * vanishes — which is indistinguishable from the process having been killed.
     */
    private fun giveUp(messageRes: Int) {
        logd("floating giving up reason=${service.resources.getResourceEntryName(messageRes)} " +
            "index=$currentIndex")
        service.toast(service.getString(messageRes))
        close()
    }

    // ---------------- A-B loop ----------------

    private fun abActive(): Boolean =
        aMs != PlayerActivity.UNSET && bMs != PlayerActivity.UNSET && bMs > aMs

    /** The same poll-and-seek watcher [PlayerActivity] runs, so the loop behaves identically here. */
    private val loopRunnable = object : Runnable {
        override fun run() {
            if (abActive()) {
                val ended = player.playbackState == Player.STATE_ENDED
                if (player.currentPosition >= bMs || ended) {
                    player.seekTo(aMs)
                    if (ended) player.play()
                }
            }
            handler.postDelayed(this, PlayerActivity.LOOP_POLL_MS)
        }
    }

    private fun updateLoopWatcher() {
        handler.removeCallbacks(loopRunnable)
        if (abActive()) handler.post(loopRunnable)
    }

    private fun updateAbBadge() {
        binding.abBadge.visibility = if (abActive()) View.VISIBLE else View.GONE
    }

    // ---------------- Stall watchdog ----------------

    /*
     * A window can go black while the player still believes it is playing. [onPlayerError] covers
     * every failure ExoPlayer *reports*, but a video surface can stop producing frames without
     * raising one — the clock keeps running and the window is left showing a black rectangle with
     * no error path to close it. So watch for frames actually arriving rather than trusting the
     * player's own account, and re-seat the surface, then re-prepare, before giving up.
     */

    private var lastFrameAtMs = 0L
    private var lastRenderedFrames = -1
    private var lastPositionMs = 0L
    private var lastPositionAtMs = 0L
    private var recoveryStage = 0

    /** Which signal tripped the current recovery, so only that one clears it again. */
    private var recoverySignal: String? = null

    /** When the decoder last produced a frame, and when one last made it onto the screen. */
    private var framesAdvancedAtMs = 0L
    private var lastCompositedAtMs = 0L

    /** When the player last looked capable of playing — i.e. not wanting to play and unable to. */
    private var playbackOkAtMs = 0L

    /** The video TextureView and the listener ExoPlayer put on it, so [close] can restore it. */
    private var videoTexture: TextureView? = null
    private var innerTextureListener: TextureView.SurfaceTextureListener? = null

    private val power by lazy { service.getSystemService(Context.POWER_SERVICE) as PowerManager }

    /**
     * A frame reaching the *screen*, which is a different question from a frame reaching the
     * surface — and the one [framesRendered] cannot answer.
     *
     * `onSurfaceTextureUpdated` is dispatched from the TextureView's own draw pass, when it calls
     * `updateTexImage()`. So it fires only when the view actually composites, and stops the moment
     * the view goes dark — even while the decoder carries on filling the surface behind it. That is
     * the exact shape of a window that goes black with a live player and no error, which is why the
     * decoder counter alone was never going to see it.
     *
     * ExoPlayer owns this listener for its own surface handling, so wrap rather than replace it:
     * every callback is forwarded untouched and only the update is also counted here. [close]
     * puts the original back, because ExoPlayer unsets it on release only if it is still there.
     */
    private fun watchComposition() {
        val texture = binding.playerView.videoSurfaceView as? TextureView ?: return
        val inner = texture.surfaceTextureListener
        videoTexture = texture
        innerTextureListener = inner
        lastCompositedAtMs = SystemClock.elapsedRealtime()
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                lastCompositedAtMs = SystemClock.elapsedRealtime()
                inner?.onSurfaceTextureAvailable(s, w, h)
            }

            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) =
                inner?.onSurfaceTextureSizeChanged(s, w, h) ?: Unit

            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean =
                inner?.onSurfaceTextureDestroyed(s) ?: true

            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                lastCompositedAtMs = SystemClock.elapsedRealtime()
                inner?.onSurfaceTextureUpdated(s)
            }
        }
    }

    /**
     * Frames the video renderer has actually put on the surface.
     *
     * The obvious signal, `AnalyticsListener.onVideoFrameProcessingOffset`, is reported when a
     * renderer is disabled or reset rather than as frames go out — healthy playback can run for
     * seconds without one, which reads as a stall. This counter moves with every frame rendered.
     */
    private fun framesRendered(): Int {
        val counters = player.videoDecoderCounters ?: return -1
        counters.ensureUpdated()
        return counters.renderedOutputBufferCount
    }

    private fun noteFrames() {
        lastFrameAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * Clears the recovery ladder once the signal that tripped it is healthy again.
     *
     * It has to be *that* signal and not just any sign of life: during a composite stall the
     * decoder keeps producing frames the whole time, so treating a decoded frame as recovery would
     * reset the ladder on every tick and the escalation would never get past its first stage.
     */
    private fun noteRecovered(healthy: Boolean) {
        if (recoveryStage == 0 || !healthy) return
        logd("floating recovered after stage=$recoveryStage signal=$recoverySignal")
        recoveryStage = 0
        recoverySignal = null
    }

    /**
     * The third signal: playback itself having stopped, which neither frame signal can see.
     *
     * Both of those only run while the player claims to be playing, so the state an error actually
     * leaves behind — [Player.STATE_IDLE] with `playWhenReady` still true — switches the whole
     * watchdog off. That is why a window could go black and silent with a dead play button and
     * never produce a single diagnostic line.
     *
     * Returns true when playback is the thing at fault, so the caller leaves the frame signals
     * alone for this tick: a player that is not playing has no frames to miss.
     */
    private fun checkPlaybackStall(now: Long, position: Long): Boolean {
        if (playbackOkAtMs == 0L) playbackOkAtMs = now
        val state = player.playbackState
        // Only a player that has been told to play can be stuck. A window the user paused is not
        // a fault, and neither is one that has reached the end with repeat off.
        val wedged = player.playWhenReady && when (state) {
            // An error puts the player here, and nothing but prepare() gets it out again.
            Player.STATE_IDLE -> true
            // Buffering that never ends. These are local files: this is a wedge, not a slow link.
            Player.STATE_BUFFERING -> now - lastPositionAtMs >= BUFFER_STALL_AFTER_MS
            else -> false
        }
        if (!wedged) {
            playbackOkAtMs = now
            noteRecovered(recoverySignal == "playback")
            // Sustained playback, not a single frame, is what proves an error is behind us.
            if (lastErrorAtMs != 0L && player.isPlaying && now - lastErrorAtMs >= FAILURE_FORGET_MS) {
                lastErrorAtMs = 0L
                failuresOnItem = 0
                itemsSkipped = 0
            }
            return false
        }
        val threshold = if (state == Player.STATE_IDLE) IDLE_STALL_AFTER_MS else BUFFER_STALL_AFTER_MS
        // Inside the grace period: [recoverFromError] gets first refusal at its own retry.
        if (now - playbackOkAtMs < threshold) return true

        recoverySignal = "playback"
        recoveryStage++
        logd("floating stalled signal=playback stage=$recoveryStage state=$state pos=$position " +
            "index=$currentIndex ab=${if (abActive()) "${aMs}-${bMs}" else "none"}")

        when (recoveryStage) {
            // Preparing again is the entire cure for an idle player — nothing about the window,
            // its surface or its view is wrong, so none of that is worth disturbing.
            1 -> restartHere()
            // Then put it back where it was explicitly, in case the position is what it choked on.
            2 -> restartAt(player.currentMediaItemIndex, position)
            // Then assume this video will not play at all and move the queue past it.
            3 -> {
                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(0, 0L)
                restartHere()
            }
            else -> { giveUp(R.string.float_stalled); return true }
        }
        // Give the recovery a full interval to take effect before escalating.
        playbackOkAtMs = now
        return true
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkForStall()
            handler.postDelayed(this, STALL_CHECK_MS)
        }
    }

    private fun checkForStall() {
        val now = SystemClock.elapsedRealtime()

        // Track the playback clock before anything can return early: the playback signal below is
        // the one case where the player is *not* playing, so the gates that follow would otherwise
        // keep resetting the very timer it needs.
        val position = player.currentPosition
        if (position != lastPositionMs) {
            lastPositionMs = position
            lastPositionAtMs = now
        }

        // Playback stopping is the third way a window dies, and the two frame signals are blind to
        // it by construction — they only look while the player says it is playing. An error leaves
        // it idle with playWhenReady still true: picture gone, sound gone, play button dead, and
        // every gate below satisfied that there is nothing to watch.
        if (checkPlaybackStall(now, position)) return

        val playing = player.playWhenReady && player.playbackState == Player.STATE_READY
        // Frames legitimately stop when the screen is off, and when the system hides overlays over
        // Settings and permission dialogs. Neither is a stall — treating them as one would fight
        // the display every time the phone is pocketed, and close the window behind a dialog.
        val onScreen = binding.root.isAttachedToWindow &&
            binding.root.windowVisibility == View.VISIBLE
        if (!playing || !power.isInteractive || !onScreen) {
            lastFrameAtMs = now
            lastRenderedFrames = framesRendered()
            framesAdvancedAtMs = now
            lastCompositedAtMs = now
            return
        }

        val rendered = framesRendered()
        // No video renderer yet, or nothing to render: there is no picture to lose.
        if (rendered < 0) {
            lastFrameAtMs = now
            lastCompositedAtMs = now
            return
        }
        if (rendered != lastRenderedFrames) {
            lastRenderedFrames = rendered
            framesAdvancedAtMs = now
            noteFrames()
        }

        val pos = position
        if (lastFrameAtMs == 0L) lastFrameAtMs = now
        if (lastCompositedAtMs == 0L) lastCompositedAtMs = now

        // Decoded frames still arriving, none of them reaching the screen: the picture is gone
        // while the player is perfectly healthy — the failure the decoder counter is blind to.
        // Gated on the decoder having produced something recently, so a genuinely static shot or a
        // very low frame rate can never look like a dead view: no new frames, nothing to composite.
        val decoderAlive = now - framesAdvancedAtMs < STALL_CHECK_MS * 2
        val compositeStalled = decoderAlive && now - lastCompositedAtMs >= COMPOSITE_STALL_AFTER_MS
        val framesStalled = now - lastFrameAtMs >= STALL_AFTER_MS

        noteRecovered(
            when (recoverySignal) {
                "composite" -> !compositeStalled
                "frames" -> !framesStalled
                else -> true
            }
        )

        if (!compositeStalled && !framesStalled) return
        // A dead picture under a live decoder is the more specific diagnosis, so it names the log.
        val signal = if (compositeStalled) "composite" else "frames"
        recoverySignal = signal

        // The clock separates the two shapes of this failure: still running means the video surface
        // died under a live player, stopped means playback itself wedged. Logged either way, so the
        // next occurrence is diagnosable from a logcat instead of from memory.
        val clock = if (now - lastPositionAtMs < STALL_AFTER_MS) "running" else "stopped"
        recoveryStage++
        logd("floating stalled signal=$signal stage=$recoveryStage clock=$clock pos=$pos " +
            "index=$currentIndex ab=${if (abActive()) "${aMs}-${bMs}" else "none"}")

        when (recoveryStage) {
            1 -> reseatSurface()   // enough when the window merely lost its output
            2 -> {
                // Re-seating the surface can't help a view whose rendering is itself dead, so take
                // the window down and put it back up: that builds a new ViewRootImpl and a new
                // TextureView, which is the only thing in our reach that rebuilds the whole path
                // from decoder to screen. Deliberately before re-preparing — playback is fine in
                // the composite case, and re-preparing it would cost a visible hitch for nothing.
                rebuildWindow()
            }
            3 -> {
                val wasPlaying = player.playWhenReady
                player.seekTo(pos)
                player.prepare()
                player.playWhenReady = wasPlaying
            }
            else -> {
                service.toast(service.getString(R.string.float_stalled))
                close()
                return
            }
        }
        // Give the recovery a full interval to take effect before escalating.
        lastFrameAtMs = now
        lastCompositedAtMs = now
    }

    /**
     * Detaches the window from the [WindowManager] and adds it straight back, then re-attaches the
     * player. Everything the view side owns — window, view root, surface — is built fresh; the
     * player, its queue and its position are untouched, so playback carries on where it was.
     */
    private fun rebuildWindow() {
        if (closed) return
        val restored = runCatching {
            wm.removeView(binding.root)
            wm.addView(binding.root, lp)
            reseatSurface()
        }.isSuccess
        logd("floating window rebuilt ok=$restored")
        // A window that failed to go back up is off the screen while still being counted as open:
        // a player running with nothing to show for it, and one more way for a window to vanish
        // without saying anything. Close it properly instead.
        if (!restored) giveUp(R.string.float_stalled)
    }

    /**
     * Hands the video surface back to the player, then re-installs the composition heartbeat.
     *
     * Re-attaching the player makes ExoPlayer put *its own* listener back on the TextureView, which
     * drops the wrapper [watchComposition] installed. Without re-wrapping here, the first recovery
     * stage would leave the heartbeat permanently silent and every later tick would read as a
     * composite stall — escalating a healthy window all the way to closed.
     */
    private fun reseatSurface() {
        binding.playerView.player = null
        binding.playerView.player = player
        watchComposition()
    }

    // ---------------- Queue ----------------

    /** True when this is a lone externally-opened file, so ⏮/⏭ can say why they can't move. */
    private fun explainSingleFile(): Boolean {
        if (externalUri == null || queue.size > 1 || folderResolved) return false
        service.toast(service.getString(R.string.folder_unavailable))
        return true
    }

    private fun nextItem() {
        if (explainSingleFile()) { player.seekTo(0); player.play(); return }
        if (userRepeatMode == Player.REPEAT_MODE_ONE) {
            player.seekTo(0)
        } else if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            service.toast(service.getString(R.string.end_of_queue))
        }
        player.play()
    }

    private fun prevItem() {
        if (explainSingleFile()) { player.seekTo(0); player.play(); return }
        when {
            userRepeatMode == Player.REPEAT_MODE_ONE -> player.seekTo(0)
            player.currentPosition > 3000 -> player.seekTo(0)
            player.hasPreviousMediaItem() -> player.seekToPreviousMediaItem()
            else -> player.seekTo(0)
        }
        player.play()
    }

    private fun updateSkipButtons() {
        // Disabled rather than hidden on a queue of one, matching the PiP controls.
        val canSkip = queue.size > 1
        binding.btnPrev.isEnabled = canSkip
        binding.btnNext.isEnabled = canSkip
        binding.btnPrev.alpha = if (canSkip) 1f else 0.4f
        binding.btnNext.alpha = if (canSkip) 1f else 0.4f
    }

    // ---------------- Window ----------------

    private fun setupButtons() {
        binding.btnPlayPause.setOnClickListener {
            when (player.playbackState) {
                // An idle player ignores playWhenReady — it has to be prepared again first.
                // Without this the button is dead and closing the window is the only way out.
                Player.STATE_IDLE -> restartHere()
                Player.STATE_ENDED -> { player.seekTo(0); player.play() }
                else -> player.playWhenReady = !player.playWhenReady
            }
            poke()
        }
        binding.btnPrev.setOnClickListener { prevItem(); poke() }
        binding.btnNext.setOnClickListener { nextItem(); poke() }
        binding.btnExpand.setOnClickListener { expand() }
        binding.btnClose.setOnClickListener { close() }
    }

    /** One finger drags the window, two pinch it; a tap that did neither shows the controls. */
    private fun setupTouch() {
        binding.root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    downLpX = lp.x; downLpY = lp.y
                    dragging = false
                    pinching = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    pinching = true
                    dragging = false
                    pinchStartDistance = spanBetween(e)
                    pinchStartWidth = widthPx
                }
                MotionEvent.ACTION_MOVE -> when {
                    pinching && e.pointerCount >= 2 -> {
                        val span = spanBetween(e)
                        if (pinchStartDistance > touchSlop) {
                            resizeTo((pinchStartWidth * span / pinchStartDistance).roundToInt())
                        }
                    }
                    !pinching -> {
                        val dx = e.rawX - downRawX
                        val dy = e.rawY - downRawY
                        if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                        if (dragging) {
                            lp.x = downLpX + dx.roundToInt()
                            lp.y = downLpY + dy.roundToInt()
                            clampAndApply()
                        }
                    }
                }
                // The finger left over after a pinch mustn't drag the window or toggle the controls.
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (!dragging && !pinching) toggleControls()
            }
            true
        }
    }

    private fun spanBetween(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return hypot(dx, dy)
    }

    /**
     * The area a window's x/y are measured in: the display minus the status and navigation bars.
     * Not the whole display — an overlay is positioned inside that inset parent frame, so clamping
     * against the raw display height lets a dragged window slide off the bottom of the screen.
     */
    private fun usableSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            // Two sources, because neither is complete on its own: the WindowManager metrics can
            // report a zero navigation-bar inset, and the view's insets only exist once attached.
            val fromMetrics = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
            val fromView = binding.root.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
            val bars = if (fromView != null) Insets.max(fromView, fromMetrics) else fromMetrics
            (metrics.bounds.width() - bars.left - bars.right) to
                (metrics.bounds.height() - bars.top - bars.bottom)
        } else {
            val dm = service.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }

    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).roundToInt()

    /** Resizes to [targetWidth], clamped, keeping the video's aspect ratio. */
    private fun resizeTo(targetWidth: Int) {
        val (screenW, _) = usableSize()
        val minW = dp(MIN_WIDTH_DP)
        val maxW = max((screenW * MAX_WIDTH_FRACTION).roundToInt(), minW)
        widthPx = targetWidth.coerceIn(minW, maxW)
        heightPx = (widthPx / aspect).roundToInt().coerceAtLeast(dp(64))
        lp.width = widthPx
        lp.height = heightPx
        clampAndApply()
        service.rememberWidth(widthPx)
    }

    /** Keeps the whole window on screen, then pushes the layout to the system. */
    private fun clampAndApply() {
        val (screenW, screenH) = usableSize()
        lp.x = lp.x.coerceIn(0, max(0, screenW - widthPx))
        lp.y = lp.y.coerceIn(0, max(0, screenH - heightPx))
        if (!closed) runCatching { wm.updateViewLayout(binding.root, lp) }
    }

    /** Rotation changes the bounds under us; drag the windows back inside them. */
    fun reclamp() {
        resizeTo(widthPx)
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    private fun showControls() {
        controlsVisible = true
        binding.controls.visibility = View.VISIBLE
        updateSkipButtons()
        scheduleHide()
    }

    private fun hideControls() {
        controlsVisible = false
        binding.controls.visibility = View.GONE
    }

    private val hideRunnable = Runnable { if (controlsVisible) hideControls() }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (player.isPlaying) handler.postDelayed(hideRunnable, HIDE_DELAY)
    }

    private fun poke() { if (controlsVisible) scheduleHide() }

    // ---------------- Hand-off out ----------------

    private fun snapshot(): FloatingHandoff.Payload = FloatingHandoff.Payload(
        queue = queue,
        index = player.currentMediaItemIndex,
        positionMs = player.currentPosition,
        playing = player.playWhenReady,
        speedIndex = PlayerActivity.SPEEDS
            .indexOfFirst { abs(it - player.playbackParameters.speed) < 0.01f }
            .let { if (it < 0) 3 else it },
        muted = player.volume == 0f,
        resizeIndex = PlayerActivity.RESIZE_MODES.indexOf(binding.playerView.resizeMode)
            .let { if (it < 0) 0 else it },
        aMs = aMs,
        bMs = bMs,
        externalUri = externalUri,
        folderResolved = folderResolved
    )

    /**
     * This window as it stands, for [FloatingState] to write down.
     *
     * Geometry as well as playback state: a window the system killed should come back the size it
     * was and where the user left it, not cascaded from the corner like a new one.
     */
    fun savedState(): FloatingState.Saved? =
        if (closed) null else FloatingState.Saved(snapshot(), widthPx, lp.x, lp.y)

    /** Back to full screen with everything intact — position, queue, speed, mute and A-B. */
    fun expand() {
        val state = snapshot()
        logd("floating expand index=${state.index} pos=${state.positionMs} " +
            "ab=${if (abActive()) "${aMs}-${bMs}" else "none"}")
        FloatingHandoff.offerToActivity(state)
        val multi = service.getSharedPreferences(ThemeManager.PREFS, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_MULTI_INSTANCE, false)
        val intent = Intent(service, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_FROM_FLOATING, true)
            .addFlags(
                if (multi) Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                else Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        runCatching { service.startActivity(intent) }
            .onFailure { FloatingHandoff.takeToActivity() }
        close()
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        player.removeListener(playerListener)
        // Put ExoPlayer's own listener back before releasing: it unsets the listener itself on the
        // way out, but only if it still finds the one it installed there.
        videoTexture?.surfaceTextureListener = innerTextureListener
        player.release()
        runCatching { wm.removeView(binding.root) }
        logd("floating closed")
        service.onWindowClosed(this)
    }
}

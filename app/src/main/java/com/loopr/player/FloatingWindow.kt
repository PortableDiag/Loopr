package com.loopr.player

import android.content.Context
import android.content.Intent
import android.graphics.Insets
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
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

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                val par = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
                aspect = (videoSize.width * par) / videoSize.height
                resizeTo(widthPx)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // A device can only decode so many videos at once; when it runs out, say so and close
            // rather than leaving a black rectangle floating over everything.
            val outOfDecoders = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
            when {
                outOfDecoders -> { service.toast(service.getString(R.string.float_no_decoder)); close() }
                player.hasNextMediaItem() -> player.seekToNextMediaItem()
                else -> { service.toast(service.getString(R.string.float_cant_play)); close() }
            }
        }
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
            if (player.playbackState == Player.STATE_ENDED) { player.seekTo(0); player.play() }
            else player.playWhenReady = !player.playWhenReady
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
        player.release()
        runCatching { wm.removeView(binding.root) }
        logd("floating closed")
        service.onWindowClosed(this)
    }
}

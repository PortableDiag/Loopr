package com.loopr.player

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.loopr.player.databinding.ActivityPlayerBinding
import kotlin.math.abs
import kotlin.math.roundToInt

/** Holds the play queue across the Activity boundary without hitting Binder size limits. */
object PlayQueue {
    @JvmField var items: List<VideoItem> = emptyList()
}

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_INDEX = "index"
        private const val PIP_ACTION = "com.loopr.player.PIP_CONTROL"
        private const val EXTRA_CONTROL = "control"
        private const val CONTROL_PLAY = 1
        private const val CONTROL_PAUSE = 2

        private const val KEY_REPEAT = "repeat_mode"
        private const val KEY_SHUFFLE = "shuffle"

        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private const val SEEK_STEP_MS = 10_000L
        // A-B loop boundary poll; small enough that the loop-back is imperceptible.
        private const val LOOP_POLL_MS = 30L
        private const val UNSET = Long.MIN_VALUE
        private const val HIDE_DELAY = 3500L

        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f

        /** Live player instances, so we can collapse to one when multi-instance is off. */
        private val liveInstances = mutableListOf<PlayerActivity>()
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var player: ExoPlayer
    private lateinit var audio: AudioManager
    private lateinit var scaleDetector: ScaleGestureDetector
    private val handler = Handler(Looper.getMainLooper())

    private var queue: List<VideoItem> = emptyList()
    private var startIndex = 0
    private var currentVideoIndex = 0

    // Non-null when this instance was launched by an external VIEW intent (single file). Kept so
    // that, once media permission is granted, we can upgrade the single-item queue to the whole
    // folder and let Next/Prev traverse it.
    private var externalUri: Uri? = null

    private val requestMediaPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) tryUpgradeToFolderQueue()
        }

    private var userRepeatMode = Player.REPEAT_MODE_ALL
    private var shuffle = false

    // A-B loop (applies to the current item only)
    private var aMs = UNSET
    private var bMs = UNSET
    private var fullDurationMs = 0L

    private var speedIndex = 3
    private var muted = false
    private var resizeIndex = 0
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private val resizeLabels = arrayOf("Fit", "Crop", "Stretch")

    private var controlsVisible = true
    private var isSeeking = false

    // gesture state
    private var gestureAxis = 0
    private var verticalRight = false
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var seekStartPos = 0L
    private var pendingSeekTarget = -1L

    // pinch-to-zoom state
    private var videoScale = 1f
    private var videoTransX = 0f
    private var videoTransY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        liveInstances.add(this)
        enforceInstancePolicy()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val prefs = getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE)
        userRepeatMode = prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_ALL)
        shuffle = prefs.getBoolean(KEY_SHUFFLE, false)

        if (!buildQueue()) {
            Toast.makeText(this, "No video", Toast.LENGTH_SHORT).show(); finish(); return
        }
        binding.title.text = queue[startIndex].title
        binding.title.isSelected = true

        applyInsets()
        setupButtons()
        setupGestures()
        setupSeekBar()
        setupPlayer()
        updateChips()
        showControls()
        maybeRequestPermissionForFolder()
    }

    /** Resolves the play queue from the static handoff, or an external VIEW intent. */
    private fun buildQueue(): Boolean {
        val idx = intent.getIntExtra(EXTRA_INDEX, -1)
        if (PlayQueue.items.isNotEmpty() && idx >= 0) {
            externalUri = null
            queue = PlayQueue.items
            startIndex = idx.coerceIn(0, queue.size - 1)
        } else {
            val uri = intent.data ?: return false
            externalUri = uri
            val title = intent.getStringExtra(EXTRA_TITLE)
                ?: uri.lastPathSegment ?: "Video"
            // Gather the other videos in the same folder so Next/Prev can traverse them;
            // fall back to just this file if we can't (no media permission, unknown source).
            val folder = runCatching { buildFolderQueue(uri) }.getOrNull()
            if (folder != null && folder.first.size > 1) {
                queue = folder.first
                startIndex = folder.second
            } else {
                queue = listOf(VideoItem(0, uri, title, 0, 0, 0, 0, ""))
                startIndex = 0
            }
        }
        currentVideoIndex = startIndex
        return true
    }

    /**
     * Resolves the folder holding the externally-opened [uri] and returns every video in it
     * (sorted by name) paired with the index of the opened file, so Next/Prev traverse the
     * folder. Returns null when we lack media permission or can't locate the file in MediaStore.
     */
    private fun buildFolderQueue(uri: Uri): Pair<List<VideoItem>, Int>? {
        if (!hasMediaPermission()) return null

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val (openedId, bucketId) = locateInMediaStore(uri, collection) ?: return null

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val selection = "${MediaStore.Video.Media.BUCKET_ID} = ?"
        val args = arrayOf(bucketId.toString())
        val sort = "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE ASC"

        val list = ArrayList<VideoItem>()
        var startIdx = -1
        contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                if (id == openedId) startIdx = list.size
                list.add(
                    VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        title = c.getString(nameCol) ?: "Video",
                        durationMs = c.getLong(durCol),
                        sizeBytes = c.getLong(sizeCol),
                        width = c.getInt(wCol),
                        height = c.getInt(hCol),
                        bucket = c.getString(bucketCol) ?: ""
                    )
                )
            }
        }
        if (startIdx < 0 || list.isEmpty()) return null
        return list to startIdx
    }

    /**
     * Finds the opened [uri]'s MediaStore row, returning its (_ID, BUCKET_ID) or null. Handles
     * MediaStore uris, Storage-Access-Framework / documents uris (e.g. opened from Downloads),
     * and file:// uris by trying id → display name (+ size) → path in turn.
     */
    private fun locateInMediaStore(uri: Uri, collection: Uri): Pair<Long, Long>? {
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.BUCKET_ID)

        // 1) Already a MediaStore video uri — look it up by id.
        if (uri.authority == MediaStore.AUTHORITY) {
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull()
            if (id != null && id > 0) {
                queryRow(collection, proj, "${MediaStore.Video.Media._ID} = ?", arrayOf(id.toString()))
                    ?.let { return it }
            }
        }

        // 2) Match by display name (+ size to disambiguate). Works for SAF/Downloads uris that
        //    don't expose _data and aren't under the "media" authority.
        val (name, size) = queryNameSize(uri)
        if (!name.isNullOrEmpty()) {
            if (size != null && size > 0) {
                queryRow(
                    collection, proj,
                    "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.SIZE} = ?",
                    arrayOf(name, size.toString())
                )?.let { return it }
            }
            queryRow(collection, proj, "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name))
                ?.let { return it }
        }

        // 3) Last resort: match by absolute path (file:// or a provider that exposes _data).
        resolvePath(uri)?.let { path ->
            queryRow(collection, proj, "${MediaStore.Video.Media.DATA} = ?", arrayOf(path))
                ?.let { return it }
        }
        return null
    }

    /** Runs a query and returns the first row's (_ID, BUCKET_ID) pair, or null. */
    private fun queryRow(uri: Uri, proj: Array<String>, sel: String, args: Array<String>): Pair<Long, Long>? {
        contentResolver.query(uri, proj, sel, args, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0) to c.getLong(1)
        }
        return null
    }

    /** Display name + size for [uri] via OpenableColumns (works across content:// providers). */
    private fun queryNameSize(uri: Uri): Pair<String?, Long?> {
        if (uri.scheme == "file") return uri.lastPathSegment to null
        return runCatching {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return null to null
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                val n = if (ni >= 0) c.getString(ni) else null
                val s = if (si >= 0 && !c.isNull(si)) c.getLong(si) else null
                n to s
            } ?: (null to null)
        }.getOrElse { null to null }
    }

    /** Best-effort absolute path for [uri]: direct for file://, else the DATA column. */
    private fun resolvePath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        return runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    private fun mediaPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, mediaPermission()) == PackageManager.PERMISSION_GRANTED

    /**
     * On an external launch we can only enqueue the whole folder once we hold media permission
     * (see [buildFolderQueue]). An app opened from a file manager usually hasn't been granted it,
     * so request it here; the grant callback upgrades the single-item queue via
     * [tryUpgradeToFolderQueue]. No-op for library launches or when permission is already held.
     */
    private fun maybeRequestPermissionForFolder() {
        if (externalUri != null && queue.size <= 1 && !hasMediaPermission()) {
            runCatching { requestMediaPerm.launch(mediaPermission()) }
        }
    }

    /**
     * After media permission is granted, rebuild the folder queue for the externally-opened file
     * and swap it into the player in place, preserving the current file, position and play state,
     * so Next/Prev can now traverse the folder.
     */
    private fun tryUpgradeToFolderQueue() {
        val uri = externalUri ?: return
        if (queue.size > 1) return
        val folder = runCatching { buildFolderQueue(uri) }.getOrNull() ?: return
        if (folder.first.size <= 1) return

        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady
        queue = folder.first
        startIndex = folder.second
        currentVideoIndex = startIndex
        player.setMediaItems(queue.map { toMediaItem(it) }, startIndex, pos)
        player.prepare()
        player.playWhenReady = wasPlaying
        binding.title.text = queue[startIndex].title
        updateChips()
    }

    // ---------------- Player ----------------

    private fun toMediaItem(v: VideoItem): MediaItem =
        MediaItem.Builder()
            .setUri(v.uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build())
            .build()

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build()
        binding.playerView.player = player
        binding.playerView.resizeMode = resizeModes[resizeIndex]

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                binding.buffering.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    val d = player.duration
                    if (d > 0) {
                        fullDurationMs = d
                        binding.duration.text = VideoAdapter.formatDuration(d)
                        updateMarkers()
                    }
                }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val idx = player.currentMediaItemIndex
                if (idx != currentVideoIndex) {
                    // Moved to a different video; A-B belonged to the previous one.
                    currentVideoIndex = idx
                    aMs = UNSET; bMs = UNSET
                    fullDurationMs = 0
                    binding.duration.text = "0:00"
                    binding.position.text = "0:00"
                    binding.seekBar.progress = 0
                    updateLoopWatcher()
                    updateChips(); updateMarkers()
                    resetZoom()
                }
                binding.title.text = item?.mediaMetadata?.title
                    ?: queue.getOrNull(idx)?.title ?: ""
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                if (isInPipMode()) updatePipParams()
                if (isPlaying) scheduleHide() else handler.removeCallbacks(hideRunnable)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (isInPipMode()) updatePipParams()
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Can't play: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                // Skip a bad file when running through a queue.
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
            }
        })

        loadQueueIntoPlayer()
        startProgress()
    }

    /** Pushes the current [queue]/[startIndex] into the player and begins playback. */
    private fun loadQueueIntoPlayer() {
        player.setMediaItems(queue.map { toMediaItem(it) }, startIndex, 0L)
        player.repeatMode = userRepeatMode
        player.shuffleModeEnabled = shuffle
        player.setPlaybackSpeed(SPEEDS[speedIndex])
        player.volume = if (muted) 0f else 1f
        player.prepare()
        player.playWhenReady = true
    }

    /** Reused-player path: a new video was picked while this instance is alive (multi-instance off). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enforceInstancePolicy()
        if (!buildQueue()) return
        aMs = UNSET; bMs = UNSET
        fullDurationMs = 0
        binding.position.text = "0:00"
        binding.duration.text = "0:00"
        binding.seekBar.progress = 0
        binding.title.text = queue[startIndex].title
        binding.title.isSelected = true
        resetZoom()
        updateLoopWatcher()
        loadQueueIntoPlayer()
        updateChips(); updateMarkers()
        showControls()
        maybeRequestPermissionForFolder()
    }

    private fun multiInstanceEnabled(): Boolean =
        getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_MULTI_INSTANCE, false)

    /** When multi-instance is off, leave only this player alive (covers external launches too). */
    private fun enforceInstancePolicy() {
        if (multiInstanceEnabled()) return
        liveInstances.toList().forEach { if (it !== this && !it.isFinishing) it.finish() }
    }

    private fun toggleMultiInstance() {
        val enabled = !multiInstanceEnabled()
        getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE).edit()
            .putBoolean(MainActivity.KEY_MULTI_INSTANCE, enabled).apply()
        if (!enabled) enforceInstancePolicy()
        updateChips()
        toast(getString(if (enabled) R.string.multi_on else R.string.multi_off))
    }

    private fun currentAbsPosition(): Long = player.currentPosition

    private fun seekToAbs(absMs: Long) {
        val max = if (fullDurationMs > 0) fullDurationMs else absMs
        player.seekTo(absMs.coerceIn(0L, max))
    }

    private fun seekBy(deltaMs: Long) = seekToAbs(currentAbsPosition() + deltaMs)

    // ---------------- Queue navigation ----------------

    private fun nextItem() {
        if (userRepeatMode == Player.REPEAT_MODE_ONE) {
            player.seekTo(0)
        } else if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            toast(getString(R.string.end_of_queue))
        }
        player.play()
    }

    private fun prevItem() {
        when {
            userRepeatMode == Player.REPEAT_MODE_ONE -> player.seekTo(0)
            player.currentPosition > 3000 -> player.seekTo(0)
            player.hasPreviousMediaItem() -> player.seekToPreviousMediaItem()
            else -> player.seekTo(0)
        }
        player.play()
    }

    private fun cycleRepeat() {
        userRepeatMode = (userRepeatMode + 1) % 3
        getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_REPEAT, userRepeatMode).apply()
        player.repeatMode = userRepeatMode
        updateChips()
        toast(getString(
            when (userRepeatMode) {
                Player.REPEAT_MODE_ONE -> R.string.repeat_one_msg
                Player.REPEAT_MODE_ALL -> R.string.repeat_all_msg
                else -> R.string.repeat_off_msg
            }
        ))
    }

    private fun toggleShuffle() {
        shuffle = !shuffle
        player.shuffleModeEnabled = shuffle
        getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_SHUFFLE, shuffle).apply()
        updateChips()
        toast(getString(if (shuffle) R.string.shuffle_on else R.string.shuffle_off))
    }

    // ---------------- A-B loop ----------------

    private fun setPointA() {
        aMs = currentAbsPosition()
        if (bMs != UNSET && bMs <= aMs) bMs = UNSET
        toast(getString(R.string.ab_hint_a, VideoAdapter.formatDuration(aMs)))
        applyAb()
    }

    private fun setPointB() {
        val pos = currentAbsPosition()
        if (aMs == UNSET || pos <= aMs) { toast(getString(R.string.ab_need_order)); return }
        bMs = pos
        toast(getString(R.string.ab_hint_b, VideoAdapter.formatDuration(bMs)))
        applyAb()
    }

    private fun clearAb() {
        if (aMs == UNSET && bMs == UNSET) return
        aMs = UNSET; bMs = UNSET
        updateLoopWatcher()
        toast(getString(R.string.ab_cleared))
        updateChips(); updateMarkers()
    }

    private fun applyAb() {
        updateLoopWatcher()
        updateChips(); updateMarkers()
    }

    /** True while a valid A-B range is set on the current item. */
    private fun abActive(): Boolean = aMs != UNSET && bMs != UNSET && bMs > aMs

    /** Polls playback while an A-B range is set and seeks back to A on reaching B. */
    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!isSeeking && abActive()) {
                val ended = player.playbackState == Player.STATE_ENDED
                if (player.currentPosition >= bMs || ended) {
                    player.seekTo(aMs)
                    if (ended) player.play()
                }
            }
            handler.postDelayed(this, LOOP_POLL_MS)
        }
    }

    private fun updateLoopWatcher() {
        handler.removeCallbacks(loopRunnable)
        if (abActive()) handler.post(loopRunnable)
    }

    private fun updateMarkers() {
        if (fullDurationMs <= 0) { binding.seekBar.setMarkers(-1f, -1f); return }
        val a = if (aMs != UNSET) aMs.toFloat() / fullDurationMs else -1f
        val b = if (bMs != UNSET) bMs.toFloat() / fullDurationMs else -1f
        binding.seekBar.setMarkers(a, b)
    }

    // ---------------- UI controls ----------------

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlayPause.setOnClickListener { togglePlay(); poke() }
        binding.btnRewind.setOnClickListener { seekBy(-SEEK_STEP_MS); poke() }
        binding.btnForward.setOnClickListener { seekBy(SEEK_STEP_MS); poke() }
        binding.btnPrev.setOnClickListener { prevItem(); poke() }
        binding.btnNext.setOnClickListener { nextItem(); poke() }
        binding.btnRotate.setOnClickListener { toggleOrientation(); poke() }
        binding.btnPip.setOnClickListener { enterPip() }

        binding.chipLoop.setOnClickListener { cycleRepeat(); poke() }
        binding.chipShuffle.setOnClickListener { toggleShuffle(); poke() }
        binding.chipMulti.setOnClickListener { toggleMultiInstance(); poke() }
        binding.chipSetA.setOnClickListener { setPointA(); poke() }
        binding.chipSetB.setOnClickListener { setPointB(); poke() }
        binding.chipClear.setOnClickListener { clearAb(); poke() }
        binding.chipSpeed.setOnClickListener { cycleSpeed(); poke() }
        binding.chipMute.setOnClickListener { toggleMute(); poke() }
        binding.chipResize.setOnClickListener { cycleResize(); poke() }
    }

    private fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) { player.seekTo(0L); player.play() }
        else player.playWhenReady = !player.playWhenReady
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.size
        player.setPlaybackSpeed(SPEEDS[speedIndex])
        binding.chipSpeed.text = formatSpeed(SPEEDS[speedIndex])
    }

    private fun formatSpeed(s: Float): String =
        if (s == s.toInt().toFloat()) "${s.toInt()}.0×" else "$s×"

    private fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
        binding.chipMute.setText(if (muted) R.string.unmute else R.string.mute)
        binding.chipMute.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up, 0, 0, 0
        )
    }

    private fun cycleResize() {
        resizeIndex = (resizeIndex + 1) % resizeModes.size
        binding.playerView.resizeMode = resizeModes[resizeIndex]
        binding.chipResize.text = resizeLabels[resizeIndex]
    }

    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun updateChips() {
        binding.chipLoop.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (userRepeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_loop_one
            else R.drawable.ic_loop, 0, 0, 0
        )
        binding.chipLoop.setText(
            when (userRepeatMode) {
                Player.REPEAT_MODE_ONE -> R.string.repeat_one
                Player.REPEAT_MODE_ALL -> R.string.repeat_all
                else -> R.string.repeat_off
            }
        )
        binding.chipLoop.alpha = if (userRepeatMode == Player.REPEAT_MODE_OFF) 0.55f else 1f
        binding.chipShuffle.alpha = if (shuffle) 1f else 0.55f
        binding.chipMulti.alpha = if (multiInstanceEnabled()) 1f else 0.55f
        binding.chipSetA.alpha = if (aMs != UNSET) 1f else 0.85f
        binding.chipSetB.alpha = if (bMs != UNSET) 1f else 0.85f
        binding.chipClear.alpha = if (aMs != UNSET || bMs != UNSET) 1f else 0.4f
        binding.chipSpeed.text = formatSpeed(SPEEDS[speedIndex])
    }

    // ---------------- SeekBar ----------------

    private fun setupSeekBar() {
        binding.seekBar.max = 1000
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && fullDurationMs > 0) {
                    val target = (progress / 1000f * fullDurationMs).toLong()
                    binding.position.text = VideoAdapter.formatDuration(target)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) { isSeeking = true; handler.removeCallbacks(hideRunnable) }

            override fun onStopTrackingTouch(sb: SeekBar) {
                if (fullDurationMs > 0) {
                    val target = (sb.progress / 1000f * fullDurationMs).toLong()
                    seekToAbs(target)
                }
                isSeeking = false
                scheduleHide()
            }
        })
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (!isSeeking && fullDurationMs > 0) {
                val abs = currentAbsPosition()
                binding.position.text = VideoAdapter.formatDuration(abs)
                binding.seekBar.progress = (abs.toFloat() / fullDurationMs * 1000).toInt()
            }
            handler.postDelayed(this, 250)
        }
    }

    private fun startProgress() {
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    // ---------------- Gestures + controls visibility ----------------

    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { gestureAxis = 0; return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleControls(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { handleDoubleTap(e.x); return true }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
                if (e1 == null || scaleDetector.isInProgress) return false
                // When zoomed in, a one-finger drag pans the video instead of seeking.
                if (videoScale > 1f) { panBy(dX, dY); return true }
                handleScroll(e1, e2); return true
            }
        })
        detector.setIsLongpressEnabled(false)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                lastFocusX = d.focusX; lastFocusY = d.focusY
                return true
            }
            override fun onScale(d: ScaleGestureDetector): Boolean {
                videoScale = (videoScale * d.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                // Pan along with the pinch focus so the content tracks the fingers.
                videoTransX += d.focusX - lastFocusX
                videoTransY += d.focusY - lastFocusY
                lastFocusX = d.focusX; lastFocusY = d.focusY
                applyVideoTransform()
                showBadge("${(videoScale * 100).roundToInt()}%")
                return true
            }
            override fun onScaleEnd(d: ScaleGestureDetector) {
                if (videoScale <= MIN_SCALE) resetZoom()
                handler.postDelayed({ binding.centerBadge.visibility = View.GONE }, 500)
            }
        })
        scaleDetector.isQuickScaleEnabled = false

        binding.gestureLayer.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            if (!scaleDetector.isInProgress) detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) endGesture()
            true
        }
    }

    private fun panBy(dX: Float, dY: Float) {
        videoTransX -= dX
        videoTransY -= dY
        applyVideoTransform()
    }

    /** Applies the current zoom scale + pan offset to the video surface, keeping edges in bounds. */
    private fun applyVideoTransform() {
        val maxX = binding.playerView.width * (videoScale - 1f) / 2f
        val maxY = binding.playerView.height * (videoScale - 1f) / 2f
        videoTransX = videoTransX.coerceIn(-maxX, maxX)
        videoTransY = videoTransY.coerceIn(-maxY, maxY)
        binding.playerView.scaleX = videoScale
        binding.playerView.scaleY = videoScale
        binding.playerView.translationX = videoTransX
        binding.playerView.translationY = videoTransY
    }

    private fun resetZoom() {
        videoScale = 1f; videoTransX = 0f; videoTransY = 0f
        applyVideoTransform()
    }

    private fun handleDoubleTap(x: Float) {
        // While zoomed, a double-tap snaps back to fit instead of seeking.
        if (videoScale > 1f) { resetZoom(); flashBadge("100%"); return }
        val w = binding.gestureLayer.width
        when {
            x < w / 3f -> { seekBy(-SEEK_STEP_MS); flashBadge("−10s") }
            x > w * 2f / 3f -> { seekBy(SEEK_STEP_MS); flashBadge("+10s") }
            else -> togglePlay()
        }
    }

    private fun handleScroll(e1: MotionEvent, e2: MotionEvent) {
        val w = binding.gestureLayer.width
        val h = binding.gestureLayer.height
        if (w == 0 || h == 0) return
        if (gestureAxis == 0) {
            val dx = abs(e2.x - e1.x); val dy = abs(e2.y - e1.y)
            if (dx < 12 && dy < 12) return
            gestureAxis = if (dx > dy) 1 else 2
            if (gestureAxis == 1) {
                seekStartPos = currentAbsPosition()
            } else {
                verticalRight = e1.x > w / 2f
                startBrightness = currentBrightness()
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        when (gestureAxis) {
            1 -> {
                val deltaMs = ((e2.x - e1.x) / w * 90_000f).toLong()
                val target = (seekStartPos + deltaMs).coerceIn(0L, fullDurationMs.coerceAtLeast(1))
                pendingSeekTarget = target
                val sign = if (deltaMs >= 0) "+" else "−"
                showBadge("${VideoAdapter.formatDuration(target)}  ($sign${abs(deltaMs) / 1000}s)")
            }
            2 -> {
                val frac = (e1.y - e2.y) / h
                if (!verticalRight) {
                    val nb = (startBrightness + frac).coerceIn(0.02f, 1f)
                    setBrightness(nb)
                    showBadge("Brightness ${(nb * 100).roundToInt()}%")
                } else {
                    val maxV = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val nv = (startVolume + frac * maxV).roundToInt().coerceIn(0, maxV)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                    showBadge("Volume ${(nv * 100 / maxV)}%")
                }
            }
        }
    }

    private fun endGesture() {
        if (gestureAxis == 1 && pendingSeekTarget >= 0) seekToAbs(pendingSeekTarget)
        pendingSeekTarget = -1
        gestureAxis = 0
        handler.postDelayed({ binding.centerBadge.visibility = View.GONE }, 500)
    }

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        return if (b >= 0f) b else 0.5f
    }

    private fun setBrightness(v: Float) {
        val lp = window.attributes; lp.screenBrightness = v; window.attributes = lp
    }

    private fun showBadge(text: String) {
        binding.centerBadge.text = text
        binding.centerBadge.visibility = View.VISIBLE
    }

    private fun flashBadge(text: String) {
        showBadge(text)
        handler.postDelayed({ binding.centerBadge.visibility = View.GONE }, 500)
    }

    private fun toggleControls() { if (controlsVisible) hideControls() else showControls() }

    private fun showControls() {
        controlsVisible = true
        binding.controls.animate().alpha(1f).setDuration(160).withStartAction {
            binding.controls.visibility = View.VISIBLE
        }.start()
        systemBars(true)
        scheduleHide()
    }

    private fun hideControls() {
        controlsVisible = false
        binding.controls.animate().alpha(0f).setDuration(160).withEndAction {
            binding.controls.visibility = View.INVISIBLE
        }.start()
        systemBars(false)
    }

    private val hideRunnable = Runnable { if (controlsVisible) hideControls() }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        if (player.isPlaying) handler.postDelayed(hideRunnable, HIDE_DELAY)
    }

    private fun poke() { if (controlsVisible) scheduleHide() }

    private fun systemBars(show: Boolean) {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (show) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.controls) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topBar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.bottomBar.updatePadding(left = bars.left + dp(10), right = bars.right + dp(10), bottom = bars.bottom)
            insets
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------- Picture in Picture ----------------

    private fun supportsPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun isInPipMode(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode

    private fun pipAspect(): Rational {
        val vs = player.videoSize
        val w = if (vs.width > 0) vs.width else 16
        val h = if (vs.height > 0) vs.height else 9
        var r = Rational(w, h)
        val max = Rational(239, 100); val min = Rational(100, 239)
        if (r.toFloat() > max.toFloat()) r = max
        if (r.toFloat() < min.toFloat()) r = min
        return r
    }

    private fun buildPipParams(): PictureInPictureParams {
        val playing = player.isPlaying
        val iconRes = if (playing) R.drawable.ic_pause else R.drawable.ic_play
        val control = if (playing) CONTROL_PAUSE else CONTROL_PLAY
        val intent = Intent(PIP_ACTION).setPackage(packageName).putExtra(EXTRA_CONTROL, control)
        val pi = PendingIntent.getBroadcast(
            this, control, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val label = if (playing) getString(R.string.pause) else getString(R.string.play)
        val action = RemoteAction(Icon.createWithResource(this, iconRes), label, label, pi)
        return PictureInPictureParams.Builder()
            .setAspectRatio(pipAspect())
            .setActions(listOf(action))
            .build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try { setPictureInPictureParams(buildPipParams()) } catch (_: Throwable) {}
    }

    private fun enterPip() {
        if (!supportsPip()) { toast(getString(R.string.pip_unsupported)); return }
        try { enterPictureInPictureMode(buildPipParams()) } catch (_: Throwable) {
            toast(getString(R.string.pip_unsupported))
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != PIP_ACTION) return
            when (intent.getIntExtra(EXTRA_CONTROL, 0)) {
                CONTROL_PLAY -> player.play()
                CONTROL_PAUSE -> player.pause()
            }
            updatePipParams()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (supportsPip() && player.isPlaying && !isInPipMode()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (isInPip) {
            resetZoom()
            binding.controls.visibility = View.INVISIBLE
            binding.gestureLayer.visibility = View.GONE
            binding.centerBadge.visibility = View.GONE
            ContextCompat.registerReceiver(
                this, pipReceiver, IntentFilter(PIP_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            try { unregisterReceiver(pipReceiver) } catch (_: Throwable) {}
            binding.gestureLayer.visibility = View.VISIBLE
            controlsVisible = true
            binding.controls.alpha = 1f
            binding.controls.visibility = View.VISIBLE
            scheduleHide()
        }
    }

    // ---------------- Lifecycle ----------------

    override fun onStop() {
        super.onStop()
        // With multiple players enabled, backgrounded instances keep playing so several
        // videos can run at once; otherwise pause when we leave the foreground (unless in PiP).
        if (!isInPipMode() && !multiInstanceEnabled()) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        liveInstances.remove(this)
        handler.removeCallbacksAndMessages(null)
        if (this::player.isInitialized) player.release()
    }
}

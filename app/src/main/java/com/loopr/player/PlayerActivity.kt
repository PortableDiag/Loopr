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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.system.Os
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
        private const val TAG = "LooprQueue"
        const val EXTRA_TITLE = "title"
        const val EXTRA_INDEX = "index"
        /** Marks an external launch we've already moved into its own task, so it can't bounce again. */
        private const val EXTRA_OWN_TASK = "own_task"
        /** Set on the intent a floating window sends when it expands back to full screen. */
        const val EXTRA_FROM_FLOATING = "from_floating"
        private const val PIP_ACTION = "com.loopr.player.PIP_CONTROL"
        private const val EXTRA_CONTROL = "control"
        private const val CONTROL_PLAY = 1
        private const val CONTROL_PAUSE = 2
        private const val CONTROL_PREV = 3
        private const val CONTROL_NEXT = 4

        internal const val KEY_REPEAT = "repeat_mode"
        internal const val KEY_SHUFFLE = "shuffle"

        // Shared with FloatingWindow so a floated video keeps the same speeds, resize modes and
        // A-B behaviour it had full screen — one definition, no drift between the two players.
        internal val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        internal val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        internal val RESIZE_LABELS = arrayOf("Fit", "Crop", "Stretch")
        private const val SEEK_STEP_MS = 10_000L
        // A-B loop boundary poll; small enough that the loop-back is imperceptible.
        internal const val LOOP_POLL_MS = 30L
        internal const val UNSET = Long.MIN_VALUE
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

    // Whether we managed to resolve the opened file's folder at all. False means the queue is a
    // lone file because the folder couldn't be read, not because it holds a single video.
    private var folderResolved = false

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

    /** State handed back by a floating window that expanded; consumed when the player is built. */
    private var restore: FloatingHandoff.Payload? = null

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
        // Before anything is built: a video opened from another app has to be relaunched into its
        // own task to get its own window (see [redispatchIntoOwnTask]). Not on a restore — the
        // intent is then one we've already handled, not a fresh launch.
        if (savedInstanceState == null && redispatchIntoOwnTask(intent)) { finish(); return }

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
        if (restoreFromFloating()) return true
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
            val folder = queueFromClipData(uri)
                ?: runCatching { buildFolderQueue(uri) }
                    .onFailure { Log.w(TAG, "buildFolderQueue failed", it) }.getOrNull()
            // A resolved folder that holds one video is a different situation from a folder we
            // couldn't read at all — only the latter is worth explaining at Next/Prev.
            folderResolved = folder != null
            if (folder != null && folder.first.size > 1) {
                queue = folder.first
                startIndex = folder.second
            } else {
                queue = listOf(VideoItem(0, uri, title, 0, 0, 0, 0, ""))
                startIndex = 0
            }
            logd("external launch -> queue size=${queue.size} startIndex=$startIndex")
        }
        currentVideoIndex = startIndex
        return true
    }

    /**
     * Picks up a video coming back out of a floating window. Everything travels — queue, index,
     * position, play state, speed, mute, resize and the A-B points — so the round trip loses
     * nothing; the position itself is applied in [loadQueueIntoPlayer], once there is a player.
     */
    private fun restoreFromFloating(): Boolean {
        if (!intent.getBooleanExtra(EXTRA_FROM_FLOATING, false)) return false
        val p = FloatingHandoff.takeToActivity() ?: return false
        if (p.queue.isEmpty()) return false

        restore = p
        queue = p.queue
        startIndex = p.index.coerceIn(0, p.queue.size - 1)
        currentVideoIndex = startIndex
        externalUri = p.externalUri
        folderResolved = p.folderResolved
        aMs = p.aMs
        bMs = p.bMs
        speedIndex = p.speedIndex.coerceIn(0, SPEEDS.size - 1)
        muted = p.muted
        resizeIndex = p.resizeIndex.coerceIn(0, RESIZE_MODES.size - 1)
        logd("restored from floating window -> queue size=${queue.size} index=$startIndex")
        return true
    }

    /**
     * The folder queue the launching app handed us outright. A file manager already knows which
     * folder it's showing, so it can attach the other videos as ClipData items; the read grant on
     * the intent covers all of them. This is the only way to page through folders MediaStore
     * doesn't index (anything under a `.nomedia`), and it needs no media permission at all.
     * Ignored unless it carries at least two items including the one we were asked to open.
     */
    private fun queueFromClipData(uri: Uri): Pair<List<VideoItem>, Int>? {
        val clip = intent.clipData ?: return null
        if (clip.itemCount < 2) return null

        val list = ArrayList<VideoItem>(clip.itemCount)
        var startIdx = -1
        for (i in 0 until clip.itemCount) {
            val u = clip.getItemAt(i).uri ?: continue
            if (u == uri) startIdx = list.size
            val name = clip.getItemAt(i).text?.toString()?.takeIf { it.isNotBlank() }
                ?: u.lastPathSegment?.substringAfterLast('/') ?: "Video"
            list.add(VideoItem(0, u, name, 0, 0, 0, 0, ""))
        }
        if (list.size < 2 || startIdx < 0) return null
        logd("clipdata queue size=${list.size} startIndex=$startIdx")
        return list to startIdx
    }

    /**
     * Resolves the folder holding the externally-opened [uri] and returns every video in it
     * (sorted by name) paired with the index of the opened file, so Next/Prev traverse the folder.
     * Locates the folder by the opened file's MediaStore bucket, or — when the opened row can't be
     * found (e.g. not indexed) — by enumerating siblings under its directory path. Returns null
     * when we lack media permission or can resolve neither.
     */
    private fun buildFolderQueue(uri: Uri): Pair<List<VideoItem>, Int>? {
        if (!hasMediaPermission()) { logd("no media permission -> single item"); return null }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val openedPath = resolvePath(uri)
        val located = locateInMediaStore(uri, collection)
        logd("external uri=$uri authority=${uri.authority} path=$openedPath located=$located")

        // Preferred: group by the opened file's MediaStore bucket — exactly its folder.
        if (located != null) {
            queryFolder(
                collection,
                "${MediaStore.Video.Media.BUCKET_ID} = ?", arrayOf(located.second.toString()),
                matchId = located.first, matchPath = null, fallbackUri = null, fallbackTitle = null
            )?.let { return it }
        }

        // Fallback: the opened row isn't in MediaStore, but we know its directory from the path —
        // enumerate the sibling videos there and splice the opened file in so it still plays.
        if (openedPath != null) {
            val dir = openedPath.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: openedPath.substringAfterLast('/')
                // Escape LIKE metacharacters in the dir (paths commonly contain '_') so the prefix
                // match can't spill into similarly-named sibling folders; the trailing /% stay wild.
                val p = escapeLike(dir)
                queryFolder(
                    collection,
                    "${MediaStore.Video.Media.DATA} LIKE ? ESCAPE '\\' AND " +
                        "${MediaStore.Video.Media.DATA} NOT LIKE ? ESCAPE '\\'",
                    arrayOf("$p/%", "$p/%/%"),
                    matchId = -1L, matchPath = openedPath, fallbackUri = uri, fallbackTitle = title
                )?.let { return it }

                // Last resort: read the directory itself. MediaStore knows nothing about folders
                // it doesn't index — anything under a .nomedia folder, or files copied in since the
                // last scan — but they're still an ordinary folder of videos the user wants to
                // page through. Only works where we can actually read the directory.
                listFolderOnDisk(dir, openedPath, uri, title)?.let { return it }
            }
        }
        return null
    }

    /** Video file extensions we'll pick up when enumerating a folder off disk. */
    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp", "3g2", "ts", "m2ts", "mts",
        "flv", "wmv", "asf", "mpg", "mpeg", "m2v", "ogv", "divx", "vob", "rm", "rmvb"
    )

    /**
     * Enumerates [dir] straight off the filesystem, for folders MediaStore has no rows for. Keeps
     * the opened file's original [uri] (that's the one we hold a read grant for) and represents its
     * siblings as file uris. Returns null when the directory isn't readable — which is the norm
     * under scoped storage unless the user has granted all-files access.
     */
    private fun listFolderOnDisk(
        dir: String, openedPath: String, uri: Uri, title: String
    ): Pair<List<VideoItem>, Int>? {
        val files = runCatching {
            java.io.File(dir).listFiles { f ->
                f.isFile && f.extension.lowercase() in videoExtensions
            }
        }.getOrNull()
        if (files.isNullOrEmpty()) { logd("disk listing unavailable for $dir"); return null }

        val sorted = files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        var startIdx = -1
        val list = sorted.mapIndexed { i, f ->
            val isOpened = f.absolutePath == openedPath
            if (isOpened) startIdx = i
            VideoItem(
                id = 0,
                uri = if (isOpened) uri else Uri.fromFile(f),
                title = f.name,
                durationMs = 0, sizeBytes = f.length(), width = 0, height = 0, bucket = ""
            )
        }
        if (startIdx < 0) return null
        logd("disk listing $dir -> ${list.size} files")
        return list to startIdx
    }

    /**
     * Runs a MediaStore video query and returns the matching folder's items (sorted by name)
     * paired with the index of the opened file. The opened file is matched by [matchId] or, failing
     * that, [matchPath]; if it isn't among the rows but [fallbackUri] is given, it's spliced in by
     * name order so Next/Prev still include it. Returns null when nothing matched.
     */
    private fun queryFolder(
        collection: Uri, selection: String, args: Array<String>,
        matchId: Long, matchPath: String?, fallbackUri: Uri?, fallbackTitle: String?
    ): Pair<List<VideoItem>, Int>? {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATA
        )
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
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val path = c.getString(dataCol)
                if ((matchId >= 0 && id == matchId) || (matchPath != null && path == matchPath)) {
                    startIdx = list.size
                }
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
        if (list.isEmpty()) return null
        if (startIdx < 0) {
            // Opened file isn't indexed; splice it in at its name-sorted position so it still plays.
            val uriToAdd = fallbackUri ?: return null
            val name = fallbackTitle ?: "Video"
            val insertAt = list.indexOfFirst { name.compareTo(it.title, ignoreCase = true) < 0 }
                .let { if (it < 0) list.size else it }
            list.add(insertAt, VideoItem(0, uriToAdd, name, 0, 0, 0, 0, ""))
            startIdx = insertAt
        }
        return list to startIdx
    }

    /**
     * Finds the opened [uri]'s MediaStore row, returning its (_ID, BUCKET_ID) or null. Handles
     * MediaStore uris, Storage-Access-Framework / documents uris (e.g. opened from Downloads),
     * file:// uris, and third-party file-manager providers, by trying id → path → display name in
     * turn — the strong, unambiguous keys first.
     */
    private fun locateInMediaStore(uri: Uri, collection: Uri): Pair<Long, Long>? {
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.BUCKET_ID)

        // 1) A directly addressable MediaStore _id — a media uri, or one embedded in a documents
        //    uri (e.g. the Files app: .../document/video%3A1234). Unambiguous.
        mediaStoreIdFrom(uri)?.let { id ->
            queryRow(collection, proj, "${MediaStore.Video.Media._ID} = ?", arrayOf(id.toString()))
                ?.let { return it }
        }

        // 2) An absolute path (file://, a decoded document id, or a provider exposing _data). A path
        //    is unique, so prefer it over the name heuristic below.
        resolvePath(uri)?.let { path ->
            queryRow(collection, proj, "${MediaStore.Video.Media.DATA} = ?", arrayOf(path))
                ?.let { return it }
        }

        // 3) Match by display name (+ size) — the fallback for third-party file-manager providers
        //    that expose neither an id nor _data (only OpenableColumns). This is a heuristic:
        //    several files can share a name/size, so accept ONLY an unambiguous single match.
        //    Otherwise a staged/temp copy (e.g. a file manager's cache of an SMB/FTP download)
        //    could resolve to a coincidentally-named local video and queue the wrong folder — a
        //    miss here just means single-item playback, which is the safe outcome.
        val (name, size) = queryNameSize(uri)
        if (!name.isNullOrEmpty()) {
            if (size != null && size > 0) {
                queryRowUnique(
                    collection, proj,
                    "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.SIZE} = ?",
                    arrayOf(name, size.toString())
                )?.let { return it }
            }
            queryRowUnique(collection, proj, "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name))
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

    /** Like [queryRow] but returns a row only when the query matches EXACTLY one, so an ambiguous
     *  name/size lookup resolves to null (single item) rather than to an arbitrary folder. */
    private fun queryRowUnique(uri: Uri, proj: Array<String>, sel: String, args: Array<String>): Pair<Long, Long>? {
        contentResolver.query(uri, proj, sel, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val row = c.getLong(0) to c.getLong(1)
                if (!c.moveToNext()) return row
            }
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

    /**
     * A MediaStore video _id directly addressable from [uri], if any: a media-authority uri, or a
     * media-documents uri (`.../document/video:1234`) as sent by the system Files app. Returns null
     * for uris that don't carry a MediaStore id (SAF path uris, file uris, third-party providers).
     */
    private fun mediaStoreIdFrom(uri: Uri): Long? {
        if (uri.authority == MediaStore.AUTHORITY) {
            runCatching { ContentUris.parseId(uri) }.getOrNull()?.let { if (it > 0) return it }
        }
        if (uri.authority == "com.android.providers.media.documents" && isDocumentUri(uri)) {
            // Document id is "video:1234" / "image:.." — the numeric part is the MediaStore _id.
            runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?.substringAfter(':', "")?.toLongOrNull()?.let { if (it > 0) return it }
        }
        return null
    }

    private fun isDocumentUri(uri: Uri): Boolean =
        runCatching { DocumentsContract.isDocumentUri(this, uri) }.getOrDefault(false)

    /** Escapes SQLite LIKE metacharacters (\ % _) so a value can be matched literally with ESCAPE '\'. */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Maps primary-volume path aliases to the canonical form MediaStore's DATA column uses,
     *  so path-based lookups (`DATA = ?` / `DATA LIKE ?`) match. Includes the internal FUSE mount
     *  points, which is the shape descriptor paths come back in. */
    private fun normalizeStoragePath(path: String): String {
        val aliases = listOf(
            "/sdcard/", "/mnt/sdcard/", "/storage/self/primary/", "/storage/emulated/legacy/",
            "/mnt/user/0/primary/", "/mnt/user/0/emulated/0/", "/mnt/runtime/default/emulated/0/",
            "/mnt/runtime/read/emulated/0/", "/mnt/runtime/write/emulated/0/", "/mnt/androidwritable/0/emulated/0/"
        )
        for (a in aliases) if (path.startsWith(a)) return "/storage/emulated/0/" + path.removePrefix(a)
        // Removable volumes: /mnt/media_rw/<vol>/x -> /storage/<vol>/x
        if (path.startsWith("/mnt/media_rw/")) return "/storage/" + path.removePrefix("/mnt/media_rw/")
        return path
    }

    /**
     * Best-effort absolute filesystem path for [uri]. Handles file:// directly, decodes the real
     * path out of Storage-Access-Framework document ids (ExternalStorageProvider volumes and the
     * Downloads provider's `raw:` ids) — which is what most file managers send — and otherwise
     * falls back to the provider's DATA column, then to the opened descriptor's real path.
     */
    private fun resolvePath(uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let { normalizeStoragePath(it) }

        if (isDocumentUri(uri)) {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            when (uri.authority) {
                "com.android.externalstorage.documents" -> if (docId != null) {
                    // "primary:Movies/clip.mp4" or "1AB2-3CD4:Movies/clip.mp4" (SD card volume).
                    // We don't stat the path — under scoped storage File.exists() is unreliable for
                    // media the app can only reach via MediaStore; the DATA lookups validate it.
                    val parts = docId.split(":", limit = 2)
                    val vol = parts[0]
                    val rel = parts.getOrNull(1).orEmpty()
                    val base = if (vol.equals("primary", ignoreCase = true))
                        "/storage/emulated/0" else "/storage/$vol"
                    if (rel.isNotEmpty() || vol.isNotEmpty()) return "$base/$rel".trimEnd('/')
                }
                "com.android.providers.downloads.documents" ->
                    if (docId != null && docId.startsWith("raw:"))
                        return normalizeStoragePath(docId.removePrefix("raw:"))
            }
        }

        runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.let { return normalizeStoragePath(it) }

        return pathFromDescriptor(uri)
    }

    /**
     * The real filesystem path behind [uri], read off the open descriptor. File managers hand us
     * their own FileProvider uris (`content://<their.app>.fileprovider/...`), which answer neither
     * a MediaStore id nor a DATA column — but the descriptor they return still points at the actual
     * file, and /proc/self/fd/N is a symlink to it. That's what tells us which folder to enqueue.
     */
    private fun pathFromDescriptor(uri: Uri): String? = runCatching {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val link = Os.readlink("/proc/self/fd/${pfd.fd}")
            // Pipes/sockets (a provider streaming rather than serving a file) aren't paths.
            if (link.startsWith("/") && !link.startsWith("/proc/")) normalizeStoragePath(link)
            else null
        }
    }.getOrNull()

    private fun mediaPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, mediaPermission()) == PackageManager.PERMISSION_GRANTED

    /**
     * True when the user chose "Allow limited access" (Android 14+). The video permission still
     * reports as granted, but MediaStore then only exposes the handful of items the user picked
     * and blanks their paths — so no folder can be enumerated, not even the one being played.
     */
    private fun hasLimitedMediaAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED

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
        folderResolved = true
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
        binding.playerView.resizeMode = RESIZE_MODES[resizeIndex]

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
                logd("now playing index=$idx/${queue.size} title=${binding.title.text}")
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
        // A video handed back by a floating window resumes where it was, still paused if it was.
        val resume = restore
        restore = null
        player.setMediaItems(queue.map { toMediaItem(it) }, startIndex, resume?.positionMs ?: 0L)
        player.repeatMode = userRepeatMode
        player.shuffleModeEnabled = shuffle
        player.setPlaybackSpeed(SPEEDS[speedIndex])
        player.volume = if (muted) 0f else 1f
        player.prepare()
        player.playWhenReady = resume?.playing ?: true
        updateLoopWatcher()
    }

    /** Reused-player path: a new video was picked while this instance is alive (multi-instance off). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // With multiple players on, a video arriving from another app opens in its own window
        // instead of taking this one over — whatever is playing here keeps playing.
        if (redispatchIntoOwnTask(intent)) return
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

    /**
     * Gives an externally-opened video its own player window when multiple players is on.
     *
     * The flags that actually put a video in its own window (`NEW_DOCUMENT | MULTIPLE_TASK`) are
     * set by whoever starts the activity — [MainActivity] does it for library picks, but a file
     * manager has no idea the setting exists. Its VIEW intent therefore lands in the task that's
     * already open and takes over the player sitting in it, collapsing a floating (PiP) window back
     * to full screen. So we don't rely on the caller: relaunch the intent into a fresh task
     * ourselves and let this pass-through instance finish.
     *
     * Returns true when [source] was handed on and the caller should stop handling it. Library
     * launches (which carry an index) and intents we've already relaunched are left alone.
     */
    private fun redispatchIntoOwnTask(source: Intent): Boolean {
        if (!multiInstanceEnabled()) return false
        if (source.data == null || source.getIntExtra(EXTRA_INDEX, -1) >= 0) return false
        if (source.getBooleanExtra(EXTRA_OWN_TASK, false)) return false

        // Copied wholesale so the data uri, ClipData folder handoff and the read grant that rides
        // FLAG_GRANT_READ_URI_PERMISSION all carry over to the new task.
        val relaunch = Intent(source)
            .setClass(this, PlayerActivity::class.java)
            .putExtra(EXTRA_OWN_TASK, true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            )
        return runCatching { startActivity(relaunch); logd("relaunched into own task"); true }
            .onFailure { Log.w(TAG, "own-task relaunch failed", it) }
            .getOrDefault(false)
    }

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

    // ---------------- Floating windows ----------------

    private fun floatingEnabled(): Boolean =
        getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_FLOATING, false)

    private fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(this)

    /**
     * Returned from the "Display over other apps" screen. The user went there because they were
     * trying to float a video, so finish that off rather than making them ask twice.
     */
    private val overlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (canDrawOverlay()) floatVideo()
            else toast(getString(R.string.float_permission_denied))
        }

    private fun requestOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle(R.string.float_permission_title)
            .setMessage(R.string.float_permission_body)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                runCatching { overlayPermission.launch(i) }
                    .onFailure { toast(getString(R.string.float_permission_denied)) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun floatSnapshot() = FloatingHandoff.Payload(
        queue = queue,
        index = player.currentMediaItemIndex,
        positionMs = player.currentPosition,
        playing = player.playWhenReady,
        speedIndex = speedIndex,
        muted = muted,
        resizeIndex = resizeIndex,
        aMs = aMs,
        bMs = bMs,
        externalUri = externalUri,
        folderResolved = folderResolved
    )

    /**
     * Hands this video to a floating window and finishes: the service owns the playback from here,
     * so the video keeps going while Loopr itself leaves the screen. Refuses — out loud — when
     * three are already up, because the decoders behind them are a finite resource.
     */
    private fun floatVideo(): Boolean {
        if (!canDrawOverlay()) { requestOverlayPermission(); return false }
        if (FloatingPlayerService.windowCount >= FloatingPlayerService.MAX_WINDOWS) {
            toast(getString(R.string.float_limit, FloatingPlayerService.MAX_WINDOWS))
            return false
        }
        val state = floatSnapshot()
        logd("floating handoff index=${state.index} pos=${state.positionMs} queue=${state.queue.size}")
        FloatingHandoff.offerToFloating(state)
        // Silence this copy before the window's own player picks the video up.
        player.playWhenReady = false
        val started = runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, FloatingPlayerService::class.java)
                    .setAction(FloatingPlayerService.ACTION_ADD)
            )
        }.isSuccess
        if (!started) {
            FloatingHandoff.takeToFloating()
            player.play()
            return false
        }
        finish()
        return true
    }

    /** The window button: a floating window while that mode is on, the system PiP window otherwise. */
    private fun enterWindowMode() {
        if (floatingEnabled()) floatVideo() else enterPip()
    }

    private fun toggleFloating() {
        val enabled = !floatingEnabled()
        getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE).edit()
            .putBoolean(MainActivity.KEY_FLOATING, enabled).apply()
        updateChips()
        toast(getString(if (enabled) R.string.float_on else R.string.float_off))
        if (enabled && !canDrawOverlay()) requestOverlayPermission()
    }

    private fun currentAbsPosition(): Long = player.currentPosition

    private fun seekToAbs(absMs: Long) {
        val max = if (fullDurationMs > 0) fullDurationMs else absMs
        player.seekTo(absMs.coerceIn(0L, max))
    }

    private fun seekBy(deltaMs: Long) = seekToAbs(currentAbsPosition() + deltaMs)

    // ---------------- Queue navigation ----------------

    /**
     * True when this is a lone externally-opened file, in which case Next/Prev have nowhere to go.
     * Says why rather than silently restarting the same video, which reads as being stuck on
     * repeat — the folder is there, we just couldn't enumerate it.
     */
    private fun explainSingleFile(): Boolean {
        if (externalUri == null || queue.size > 1 || folderResolved) return false
        toast(getString(
            when {
                !hasMediaPermission() -> R.string.folder_needs_permission
                hasLimitedMediaAccess() -> R.string.folder_limited_access
                else -> R.string.folder_unavailable
            }
        ))
        return true
    }

    private fun nextItem() {
        if (explainSingleFile()) { player.seekTo(0); player.play(); return }
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
        if (explainSingleFile()) { player.seekTo(0); player.play(); return }
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
        binding.btnPip.setOnClickListener { enterWindowMode() }

        binding.chipLoop.setOnClickListener { cycleRepeat(); poke() }
        binding.chipShuffle.setOnClickListener { toggleShuffle(); poke() }
        binding.chipMulti.setOnClickListener { toggleMultiInstance(); poke() }
        binding.chipFloat.setOnClickListener { toggleFloating(); poke() }
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
        resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
        binding.playerView.resizeMode = RESIZE_MODES[resizeIndex]
        binding.chipResize.text = RESIZE_LABELS[resizeIndex]
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
        binding.chipFloat.alpha = if (floatingEnabled()) 1f else 0.55f
        binding.chipSetA.alpha = if (aMs != UNSET) 1f else 0.85f
        binding.chipSetB.alpha = if (bMs != UNSET) 1f else 0.85f
        binding.chipClear.alpha = if (aMs != UNSET || bMs != UNSET) 1f else 0.4f
        binding.chipSpeed.text = formatSpeed(SPEEDS[speedIndex])
        binding.chipResize.text = RESIZE_LABELS[resizeIndex]
        binding.chipMute.setText(if (muted) R.string.unmute else R.string.mute)
        binding.chipMute.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up, 0, 0, 0
        )
        // The window button floats the video while that mode is on, and enters PiP otherwise.
        binding.btnPip.setImageResource(
            if (floatingEnabled()) R.drawable.ic_float else R.drawable.ic_pip
        )
        binding.btnPip.contentDescription =
            getString(if (floatingEnabled()) R.string.float_video else R.string.pip)
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

    /** Off by default; enable to diagnose external-launch queueing:
     *  `adb shell setprop log.tag.LooprQueue DEBUG`. */
    private fun logd(msg: String) { if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, msg) }

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

    /** One tappable control in the PiP window, wired to [pipReceiver] through [PIP_ACTION]. */
    private fun pipAction(control: Int, iconRes: Int, label: String, enabled: Boolean): RemoteAction {
        val intent = Intent(PIP_ACTION).setPackage(packageName).putExtra(EXTRA_CONTROL, control)
        val pi = PendingIntent.getBroadcast(
            this, control, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return RemoteAction(Icon.createWithResource(this, iconRes), label, label, pi)
            .apply { isEnabled = enabled }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val playing = player.isPlaying
        // Skipping without going full screen again. Greyed out rather than hidden on a lone video,
        // so the window's controls don't shift about depending on what's playing.
        val canSkip = queue.size > 1
        val actions = listOf(
            pipAction(CONTROL_PREV, R.drawable.ic_skip_prev, getString(R.string.previous), canSkip),
            if (playing) pipAction(CONTROL_PAUSE, R.drawable.ic_pause, getString(R.string.pause), true)
            else pipAction(CONTROL_PLAY, R.drawable.ic_play, getString(R.string.play), true),
            pipAction(CONTROL_NEXT, R.drawable.ic_skip_next, getString(R.string.next), canSkip)
        )
        // The system caps how many controls a PiP window will show (typically three).
        val max = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) maxNumPictureInPictureActions
        else actions.size
        return PictureInPictureParams.Builder()
            .setAspectRatio(pipAspect())
            .setActions(if (actions.size <= max) actions else listOf(actions[1]))
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
                CONTROL_PREV -> prevItem()
                CONTROL_NEXT -> nextItem()
            }
            updatePipParams()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // An instance that finished during onCreate (no video, or handed on to its own task) never
        // built a player, and still gets this callback on the way out.
        if (!this::player.isInitialized) return
        if (!player.isPlaying || isInPipMode()) return
        // Home with floating windows on hands the video to its own window instead of the system's.
        if (floatingEnabled()) {
            if (canDrawOverlay()) { floatVideo(); return }
            // The permission was granted once and has since been withdrawn: say so, then fall back.
            toast(getString(R.string.float_permission_lost))
        }
        if (supportsPip()) enterPip()
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
        if (!this::player.isInitialized) return
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

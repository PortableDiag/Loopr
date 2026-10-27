package com.loopr.player.api

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.loopr.player.ControllablePlayer
import com.loopr.player.FloatingHandoff
import com.loopr.player.FloatingPlayerService
import com.loopr.player.FloatingWindow
import com.loopr.player.MainActivity
import com.loopr.player.NotificationAccess
import com.loopr.player.PlayQueue
import com.loopr.player.PlayerActivity
import com.loopr.player.PlayerStatus
import com.loopr.player.Players
import com.loopr.player.ThemeManager
import com.loopr.player.ThumbnailLoader
import com.loopr.player.VideoItem
import com.loopr.player.VideoLibrary
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * What each tool actually does, on a real phone.
 *
 * Requests arrive on socket threads; anything that touches a player, a window or the theme hops to
 * the main thread through [MainThread], and the library is read where the request already is.
 */
@UnstableApi
class AndroidBackend(context: Context) : LooprBackend {

    private val app = context.applicationContext

    override val version: String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    override fun run(tool: String, args: JSONObject): Any = when (tool) {
        "loopr.library" -> library(args)
        "loopr.folders" -> folders()
        "loopr.thumbnail" -> JSONObject().put("mime", "image/jpeg")
            .put("data_b64", Base64.encodeToString(thumbnailJpeg(longArg(args, "video_id")), Base64.NO_WRAP))
        "loopr.players" -> players()
        "loopr.queue" -> queue(args)
        "loopr.open" -> open(args)
        "loopr.playback" -> playback(args)
        "loopr.ab_loop" -> abLoop(args)
        "loopr.options" -> options(args)
        "loopr.window" -> window(args)
        "loopr.settings" -> settings()
        "loopr.change_settings" -> changeSettings(args)
        else -> throw ToolError(ToolError.UNKNOWN_TOOL, "No tool named $tool")
    }

    // ---------------- Library ----------------

    private fun requireLibrary(): List<VideoLibrary.Entry> {
        if (!VideoLibrary.hasPermission(app)) throw ToolError(
            ToolError.REFUSED,
            "Loopr has not been allowed to see the phone's videos. Open Loopr and tap Grant access; nothing in the library can be listed or opened until then."
        )
        return VideoLibrary.query(app)
    }

    private fun library(args: JSONObject): JSONObject {
        val words = args.optString("q").lowercase().split(' ').filter { it.isNotBlank() }
        val folder = args.optString("folder").trim()
        val all = requireLibrary().filter { e ->
            words.all { e.item.title.lowercase().contains(it) } &&
                (folder.isEmpty() || e.folderId.toString() == folder || e.item.bucket.equals(folder, ignoreCase = true))
        }
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val limit = args.optInt("limit", 50).coerceIn(1, 500)
        return JSONObject()
            .put("total", all.size)
            .put("offset", offset)
            .put("videos", JSONArray(all.drop(offset).take(limit).map { videoJson(it) }))
            .apply { if (VideoLibrary.hasLimitedAccess(app)) put("note", LIMITED_NOTE) }
    }

    private fun videoJson(e: VideoLibrary.Entry) = JSONObject()
        .put("id", e.item.id)
        .put("title", e.item.title)
        .put("duration_ms", e.item.durationMs)
        .put("size_bytes", e.item.sizeBytes)
        .put("width", e.item.width)
        .put("height", e.item.height)
        .put("folder", e.item.bucket)
        .put("folder_id", e.folderId)
        .putOpt("path", e.path)

    private fun folders(): JSONArray {
        val groups = requireLibrary().groupBy { it.folderId }
        return JSONArray(groups.values
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first().item.bucket })
            .map { g ->
                JSONObject().put("id", g.first().folderId).put("name", g.first().item.bucket)
                    .put("videos", g.size).put("duration_ms", g.sumOf { it.item.durationMs })
            })
    }

    override fun thumbnailJpeg(videoId: Long): ByteArray {
        val entry = requireLibrary().firstOrNull { it.item.id == videoId }
            ?: throw ToolError(ToolError.NOT_FOUND, "No video $videoId in the library; loopr.library lists them")
        val bmp = runBlocking { ThumbnailLoader.load(app, entry.item) }
            ?: throw ToolError(ToolError.NOT_FOUND, "Android has no thumbnail for video $videoId")
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    // ---------------- Players ----------------

    private fun players(): JSONObject {
        val list = MainThread.get { Players.all().map { it.playerId to it.status() } }
        return JSONObject()
            .put("players", JSONArray(list.map { (id, s) -> statusJson(id, s) }))
            .put("floating_open", list.count { it.second.kind == PlayerStatus.FLOATING })
            .put("floating_max", FloatingPlayerService.MAX_WINDOWS)
    }

    private fun statusJson(id: Int, s: PlayerStatus): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", s.kind)
        .put("title", s.title)
        .put("uri", s.uri)
        .put("index", s.index)
        .put("queue_size", s.queueSize)
        .put("position_ms", s.positionMs)
        .put("duration_ms", s.durationMs)
        .put("playing", s.playing)
        .put("play_when_ready", s.playWhenReady)
        .put("state", STATES[s.playbackState] ?: "unknown")
        .put("speed", s.speed.toDouble())
        .put("muted", s.muted)
        .put("resize", RESIZE_NAMES.getOrElse(s.resizeIndex) { "fit" })
        .put("repeat", REPEAT_NAMES[s.repeatMode] ?: "all")
        .put("shuffle", s.shuffle)
        .put("ab", if (s.aMs != PlayerActivity.UNSET || s.bMs != PlayerActivity.UNSET) JSONObject()
            .put("a_ms", s.aMs.takeIf { it != PlayerActivity.UNSET } ?: JSONObject.NULL)
            .put("b_ms", s.bMs.takeIf { it != PlayerActivity.UNSET } ?: JSONObject.NULL)
            .put("looping", s.aMs != PlayerActivity.UNSET && s.bMs != PlayerActivity.UNSET && s.bMs > s.aMs)
            else JSONObject.NULL)
        .put("zoom", s.zoom.toDouble())
        .apply {
            s.bounds?.let { put("window", JSONObject().put("x", it.left).put("y", it.top).put("width", it.width()).put("height", it.height())) }
            if (s.kind == PlayerStatus.FULL_SCREEN) put("pip", s.inPip)
        }

    /** Must run on the main thread. A missing id is only fine while there is exactly one player. */
    private fun resolve(args: JSONObject): ControllablePlayer {
        val open = Players.all()
        if (args.has("player_id")) {
            val id = args.optInt("player_id", -1)
            return Players.get(id) ?: throw ToolError(
                ToolError.NOT_FOUND,
                "No player $id is open." + if (open.isEmpty()) " None are; loopr.open opens one." else " Open now: ${open.joinToString { it.playerId.toString() }}."
            )
        }
        return when (open.size) {
            0 -> throw ToolError(ToolError.NOT_FOUND, "No player is open; loopr.open opens one")
            1 -> open.first()
            else -> throw ToolError(ToolError.BAD_ARGS, "${open.size} players are open (${open.joinToString { it.playerId.toString() }}); say which with player_id")
        }
    }

    /** Runs [block] on [resolve]'s player and answers that player's state afterwards. */
    private fun onPlayer(args: JSONObject, block: (ControllablePlayer) -> Unit): JSONObject =
        MainThread.get {
            val p = resolve(args)
            block(p)
            statusJson(p.playerId, p.status())
        }

    private fun queue(args: JSONObject): JSONObject = MainThread.get {
        val p = resolve(args)
        val items = p.queueItems()
        val current = p.status().index
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val limit = args.optInt("limit", 100).coerceIn(1, 500)
        JSONObject()
            .put("player_id", p.playerId)
            .put("index", current)
            .put("total", items.size)
            .put("items", JSONArray(items.withIndex().drop(offset).take(limit).map { (i, v) ->
                JSONObject().put("index", i).put("title", v.title).put("uri", v.uri.toString())
                    .apply { if (v.id > 0) put("video_id", v.id) }
                    .put("current", i == current)
            }))
    }

    private fun playback(args: JSONObject): JSONObject {
        val action = args.optString("action")
        return onPlayer(args) { p ->
            when (action) {
                "play" -> p.play()
                "pause" -> p.pause()
                "toggle" -> {
                    val s = p.status()
                    val active = s.playWhenReady && (s.playbackState == Player.STATE_READY || s.playbackState == Player.STATE_BUFFERING)
                    if (active) p.pause() else p.play()
                }
                "seek" -> p.seekTo(longArg(args, "position_ms").coerceAtLeast(0))
                "seek_by" -> p.seekTo(p.status().positionMs + longArg(args, "delta_ms"))
                "next" -> p.next()
                "previous" -> p.previous()
                "jump" -> {
                    val i = intArg(args, "index")
                    val size = p.queueItems().size
                    if (i !in 0 until size) throw ToolError(ToolError.BAD_ARGS, "index must be 0 to ${size - 1}; this queue has $size")
                    p.jumpTo(i)
                }
                else -> throw ToolError(ToolError.BAD_ARGS, "action is one of play, pause, toggle, seek, seek_by, next, previous, jump")
            }
        }
    }

    private fun abLoop(args: JSONObject): JSONObject {
        val action = args.optString("action")
        return onPlayer(args) { p ->
            val s = p.status()
            when (action) {
                "set" -> {
                    val a = longArg(args, "a_ms")
                    val b = longArg(args, "b_ms")
                    if (a < 0 || b <= a) throw ToolError(ToolError.BAD_ARGS, "Needs 0 <= a_ms < b_ms")
                    if (s.durationMs > 0 && b > s.durationMs) {
                        throw ToolError(ToolError.BAD_ARGS, "b_ms is past the end of the video (${s.durationMs} ms)")
                    }
                    p.setAbPoints(a, b)
                }
                // The chips' own rules: A here drops a B that is no longer after it; B needs an A before it.
                "set_a" -> p.setAbPoints(s.positionMs, if (s.bMs != PlayerActivity.UNSET && s.bMs > s.positionMs) s.bMs else PlayerActivity.UNSET)
                "set_b" -> {
                    if (s.aMs == PlayerActivity.UNSET || s.positionMs <= s.aMs) throw ToolError(
                        ToolError.REFUSED, "B has to come after A: set A first, or seek past it (A is ${if (s.aMs == PlayerActivity.UNSET) "not set" else "${s.aMs} ms"})"
                    )
                    p.setAbPoints(s.aMs, s.positionMs)
                }
                "clear" -> p.clearAbPoints()
                else -> throw ToolError(ToolError.BAD_ARGS, "action is one of set, set_a, set_b, clear")
            }
        }
    }

    private fun options(args: JSONObject): JSONObject {
        val speed = if (args.has("speed")) {
            val v = args.optDouble("speed").toFloat()
            PlayerActivity.SPEEDS.indexOfFirst { kotlin.math.abs(it - v) < 0.001f }.takeIf { it >= 0 }
                ?: throw ToolError(ToolError.BAD_ARGS, "speed is one of ${PlayerActivity.SPEEDS.joinToString()}")
        } else null
        val resize = if (args.has("resize")) RESIZE_NAMES.indexOf(args.optString("resize")).takeIf { it >= 0 }
            ?: throw ToolError(ToolError.BAD_ARGS, "resize is one of ${RESIZE_NAMES.joinToString()}") else null
        val repeat = if (args.has("repeat")) REPEAT_NAMES.entries.firstOrNull { it.value == args.optString("repeat") }?.key
            ?: throw ToolError(ToolError.BAD_ARGS, "repeat is one of off, one, all") else null
        val zoom = if (args.has("zoom")) args.optDouble("zoom").toFloat().takeIf { it in 1f..5f }
            ?: throw ToolError(ToolError.BAD_ARGS, "zoom is 1 to 5") else null
        val muted = if (args.has("muted")) boolArg(args, "muted") else null
        val shuffle = if (args.has("shuffle")) boolArg(args, "shuffle") else null
        if (listOf(speed, resize, repeat, zoom, muted, shuffle).all { it == null }) {
            throw ToolError(ToolError.BAD_ARGS, "Give at least one of speed, muted, resize, repeat, shuffle, zoom")
        }
        return onPlayer(args) { p ->
            speed?.let { p.setSpeedIndex(it) }
            muted?.let { p.setMuted(it) }
            resize?.let { p.setResizeIndex(it) }
            repeat?.let { p.setRepeatMode(it) }
            shuffle?.let { p.setShuffle(it) }
            zoom?.let { p.setZoom(it) }
        }
    }

    // ---------------- Opening and windows ----------------

    private fun open(args: JSONObject): JSONObject {
        val library = requireLibrary()
        val entry = when {
            args.has("video_id") -> library.firstOrNull { it.item.id == args.optLong("video_id") }
                ?: throw ToolError(ToolError.NOT_FOUND, "No video ${args.optLong("video_id")} in the library; loopr.library lists them")
            args.optString("path").isNotBlank() -> VideoLibrary.findByPath(app, args.optString("path").trim())
                ?: throw ToolError(ToolError.NOT_FOUND, "The library has no video at that path. Only videos Android has indexed can be opened from here; a file manager can still open the rest.")
            else -> throw ToolError(ToolError.BAD_ARGS, "Give video_id or path")
        }
        val (queue, index) = when (val q = args.optString("queue").ifEmpty { "library" }) {
            "library" -> library.map { it.item } to library.indexOfFirst { it.item.id == entry.item.id }.coerceAtLeast(0)
            "folder" -> library.filter { it.folderId == entry.folderId }.map { it.item }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                .let { list -> list to list.indexOfFirst { it.id == entry.item.id }.coerceAtLeast(0) }
            "single" -> listOf(entry.item) to 0
            else -> throw ToolError(ToolError.BAD_ARGS, "queue is one of library, folder, single, not $q")
        }
        val position = args.optLong("position_ms", 0L).coerceAtLeast(0L)
        val paused = args.optBoolean("paused", false)
        return when (val w = args.optString("window").ifEmpty { "full" }) {
            "floating" -> openFloating(queue, index, position, paused)
            "full" -> openFull(queue, index, entry.item, position, paused)
            else -> throw ToolError(ToolError.BAD_ARGS, "window is full or floating, not $w")
        }
    }

    private fun openFloating(queue: List<VideoItem>, index: Int, position: Long, paused: Boolean): JSONObject {
        requireFloatingRoom()
        val before = MainThread.get { Players.all().map { it.playerId }.toSet() }
        FloatingHandoff.offerToFloating(
            FloatingHandoff.Payload(
                queue = queue, index = index, positionMs = position, playing = !paused,
                speedIndex = 3, muted = false, resizeIndex = 0,
                aMs = PlayerActivity.UNSET, bMs = PlayerActivity.UNSET,
                externalUri = null, folderResolved = true
            )
        )
        // Library videos are MediaStore's, read on Loopr's own permission: no grant to forward.
        val started = runCatching {
            ContextCompat.startForegroundService(
                app, Intent(app, FloatingPlayerService::class.java).setAction(FloatingPlayerService.ACTION_ADD)
            )
        }
        if (started.isFailure) {
            FloatingHandoff.takeToFloating()
            throw ToolError(ToolError.REFUSED, "Android would not start the floating window: ${started.exceptionOrNull()?.message}")
        }
        return awaitNewPlayer(before, PlayerStatus.FLOATING)
    }

    private fun openFull(queue: List<VideoItem>, index: Int, item: VideoItem, position: Long, paused: Boolean): JSONObject {
        requireCanShowScreen()
        val before = MainThread.get { Players.all().associate { it.playerId to it.status().uri } }
        MainThread.run {
            // The grid's own launch, from outside an activity.
            PlayQueue.items = queue
            app.startActivity(Intent(app, PlayerActivity::class.java).apply {
                data = item.uri
                putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                putExtra(PlayerActivity.EXTRA_INDEX, index)
                addFlags(
                    if (multiInstance()) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            })
        }
        // With one player, the same activity takes the new video: look for a changed uri as well as a new id.
        val p = awaitPlayer { list ->
            list.firstOrNull { (id, s) -> s.kind == PlayerStatus.FULL_SCREEN && s.uri == item.uri.toString() && before[id] != s.uri }
        } ?: throw ToolError(ToolError.REFUSED, "Loopr was asked to open it full screen but no player appeared; Android may have kept it from coming to the front")
        if (position > 0 || paused) MainThread.run {
            Players.get(p)?.let { if (position > 0) it.seekTo(position); if (paused) it.pause() }
        }
        return MainThread.get { Players.get(p)?.let { statusJson(p, it.status()) } } ?: JSONObject().put("id", p)
    }

    private fun window(args: JSONObject): JSONObject {
        val action = args.optString("action")
        if (action == "close_all") {
            val n = MainThread.get { Players.all().onEach { it.closePlayer() }.size }
            return JSONObject().put("closed", n)
        }
        val needs = setOf("float", "expand", "pip", "rotate", "move", "resize", "close")
        if (action !in needs) throw ToolError(ToolError.BAD_ARGS, "action is one of ${needs.joinToString()}, close_all")
        if (action == "float") requireFloatingRoom()
        if (action == "expand") requireCanShowScreen()
        val before = MainThread.get { Players.all().map { it.playerId }.toSet() }
        val moved = MainThread.get {
            val p = resolve(args)
            val full = p as? PlayerActivity
            val floating = p as? FloatingWindow
            fun wrongKind(want: String): Nothing = throw ToolError(
                ToolError.REFUSED, "Player ${p.playerId} is ${if (full != null) "full screen" else "a floating window"}; $action is for $want"
            )
            when (action) {
                "close" -> { p.closePlayer(); return@get JSONObject().put("closed", p.playerId) }
                "float" -> { if (full == null) wrongKind("a full-screen player"); if (!full.floatFromApi()) throw ToolError(ToolError.REFUSED, "The player could not hand the video to a window") }
                "expand" -> (floating ?: wrongKind("a floating window")).expand()
                "pip" -> (full ?: wrongKind("a full-screen player")).pipFromApi()?.let { throw ToolError(ToolError.REFUSED, it) }
                "rotate" -> (full ?: wrongKind("a full-screen player")).rotate()
                "move" -> (floating ?: wrongKind("a floating window")).moveTo(intArg(args, "x"), intArg(args, "y"))
                "resize" -> (floating ?: wrongKind("a floating window")).resizeWidth(intArg(args, "width"))
            }
            if (action == "float" || action == "expand") null else statusJson(p.playerId, p.status())
        }
        if (moved != null) return moved
        // The video moved to a new player: answer that one, so the caller has the id it now goes by.
        return awaitNewPlayer(before, if (action == "float") PlayerStatus.FLOATING else PlayerStatus.FULL_SCREEN)
    }

    private fun requireFloatingRoom() {
        if (!Settings.canDrawOverlays(app)) throw ToolError(
            ToolError.REFUSED,
            "Floating windows need Android's \"Display over other apps\" for Loopr, and it is off. Only the phone's owner can turn it on (Loopr › ⋮ › Floating windows asks). Full screen and picture-in-picture still work."
        )
        if (FloatingPlayerService.windowCount >= FloatingPlayerService.MAX_WINDOWS) throw ToolError(
            ToolError.REFUSED,
            "${FloatingPlayerService.MAX_WINDOWS} floating windows are already open, the most at once; close one first (loopr.window close)"
        )
    }

    /** Android lets a background app bring up a screen only with "Display over other apps", or while it is already in front. */
    private fun requireCanShowScreen() {
        if (Settings.canDrawOverlays(app) || inForeground()) return
        throw ToolError(
            ToolError.REFUSED,
            "Android does not let Loopr open a full screen from the background without \"Display over other apps\". Open Loopr first, or have the owner allow it; floating needs it too."
        )
    }

    private fun inForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun awaitNewPlayer(before: Set<Int>, kind: String): JSONObject {
        val id = awaitPlayer { list -> list.firstOrNull { (id, s) -> id !in before && s.kind == kind } }
            ?: throw ToolError(ToolError.REFUSED, "No ${kind.replace('_', ' ')} player appeared; GET /api/players shows what is open")
        return MainThread.get { Players.get(id)?.let { statusJson(id, it.status()) } } ?: JSONObject().put("id", id)
    }

    /** Polls the open players until [match] picks one, for up to [OPEN_WAIT_MS]. */
    private fun awaitPlayer(match: (List<Pair<Int, PlayerStatus>>) -> Pair<Int, PlayerStatus>?): Int? {
        val until = System.currentTimeMillis() + OPEN_WAIT_MS
        while (System.currentTimeMillis() < until) {
            MainThread.get { match(Players.all().map { it.playerId to it.status() }) }?.let { return it.first }
            Thread.sleep(100)
        }
        return null
    }

    // ---------------- Settings ----------------

    private fun prefs() = app.getSharedPreferences(ThemeManager.PREFS, Context.MODE_PRIVATE)

    private fun multiInstance() = prefs().getBoolean(MainActivity.KEY_MULTI_INSTANCE, false)

    private fun settings(): JSONObject {
        val media = when {
            !VideoLibrary.hasPermission(app) -> "denied"
            VideoLibrary.hasLimitedAccess(app) -> "limited"
            else -> "granted"
        }
        return JSONObject()
            .put("theme", THEME_NAMES.getOrElse(ThemeManager.savedMode(app)) { "dark" })
            .put("multiple_players", multiInstance())
            .put("floating_windows", prefs().getBoolean(MainActivity.KEY_FLOATING, false))
            .put("permissions", JSONObject()
                .put("videos", media)
                .put("display_over_other_apps", Settings.canDrawOverlays(app))
                .put("notifications", NotificationAccess.granted(app)))
            .put("note", "Only the phone's owner can grant permissions, on Android's own screens. Without \"Display over other apps\" floating windows fall back to picture-in-picture; with videos \"limited\", the library shows only the ones the owner picked.")
    }

    private fun changeSettings(args: JSONObject): JSONObject {
        val theme = if (args.has("theme")) THEME_NAMES.indexOf(args.optString("theme")).takeIf { it >= 0 }
            ?: throw ToolError(ToolError.BAD_ARGS, "theme is one of ${THEME_NAMES.joinToString()}") else null
        val multi = if (args.has("multiple_players")) boolArg(args, "multiple_players") else null
        val floating = if (args.has("floating_windows")) boolArg(args, "floating_windows") else null
        if (theme == null && multi == null && floating == null) {
            throw ToolError(ToolError.BAD_ARGS, "Give at least one of theme, multiple_players, floating_windows")
        }
        MainThread.run {
            prefs().edit().apply {
                multi?.let { putBoolean(MainActivity.KEY_MULTI_INSTANCE, it) }
                floating?.let { putBoolean(MainActivity.KEY_FLOATING, it) }
            }.apply()
            theme?.let { ThemeManager.setMode(app, it) }
            Players.all().forEach { (it as? PlayerActivity)?.settingsChanged() }
        }
        return settings()
    }

    // ---------------- Arguments ----------------

    private fun longArg(a: JSONObject, name: String): Long {
        if (!a.has(name)) throw ToolError(ToolError.BAD_ARGS, "$name is required")
        return when (val v = a.get(name)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        } ?: throw ToolError(ToolError.BAD_ARGS, "$name must be a whole number")
    }

    private fun intArg(a: JSONObject, name: String): Int = longArg(a, name).toInt()

    private fun boolArg(a: JSONObject, name: String): Boolean = when (val v = a.opt(name)) {
        is Boolean -> v
        "true" -> true
        "false" -> false
        else -> throw ToolError(ToolError.BAD_ARGS, "$name must be true or false")
    }

    companion object {
        /** Long enough for a cold activity start on a slow phone. */
        private const val OPEN_WAIT_MS = 5_000L

        private val STATES = mapOf(
            Player.STATE_IDLE to "idle", Player.STATE_BUFFERING to "buffering",
            Player.STATE_READY to "ready", Player.STATE_ENDED to "ended"
        )
        /** In [PlayerActivity.RESIZE_MODES] order. */
        private val RESIZE_NAMES = listOf("fit", "crop", "stretch")
        private val REPEAT_NAMES = mapOf(
            Player.REPEAT_MODE_OFF to "off", Player.REPEAT_MODE_ONE to "one", Player.REPEAT_MODE_ALL to "all"
        )
        /** In [ThemeManager] order. */
        private val THEME_NAMES = listOf("system", "light", "dark")

        private const val LIMITED_NOTE =
            "Android shows Loopr only the videos the owner picked (\"Allow limited access\"); the rest of the library is hidden from it."
    }
}

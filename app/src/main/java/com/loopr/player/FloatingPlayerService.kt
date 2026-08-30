package com.loopr.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import kotlin.math.roundToInt

/**
 * Owns the floating players: up to [MAX_WINDOWS] of them, one notification for the lot, and it
 * stops itself when the last window closes.
 *
 * A foreground service is what keeps the windows (and their audio) alive once Loopr itself is
 * gone from the screen — the activity finishes as soon as it hands a video over.
 */
@UnstableApi
class FloatingPlayerService : Service() {

    companion object {
        const val ACTION_ADD = "com.loopr.player.FLOAT_ADD"
        const val ACTION_CLOSE_ALL = "com.loopr.player.FLOAT_CLOSE_ALL"

        /**
         * Hardware video decoders are a finite, small resource — commonly a handful per device —
         * so this is a real constraint rather than caution.
         */
        const val MAX_WINDOWS = 3

        private const val TAG = "LooprQueue"
        private const val CHANNEL_ID = "floating_windows"
        private const val NOTIF_ID = 1971

        /**
         * How often the open windows are written down.
         *
         * The process is killed without warning — there is no callback to save in — so the file on
         * disk is only ever as fresh as the last tick. A restored window resuming up to this far
         * back is the cost of not writing on every frame.
         */
        private const val SAVE_EVERY_MS = 5_000L

        /** Windows still up this long after a restore have held, so the next kill starts fresh. */
        private const val SETTLED_AFTER_MS = 2 * 60 * 1000L

        /** How many windows are up, so the activity can refuse a fourth before tearing itself down. */
        @Volatile
        @JvmStatic
        var windowCount: Int = 0
            private set
    }

    private val windows = mutableListOf<FloatingWindow>()

    /** Last size the user pinched a window to, reused for the next one opened this session. */
    private var lastWidthPx = 0

    private val handler = Handler(Looper.getMainLooper())

    /** Keeps the file on disk current, so a kill costs at most [SAVE_EVERY_MS] of playback. */
    private val saveRunnable = object : Runnable {
        override fun run() {
            saveState()
            handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL) {
            closeAll()
            return START_NOT_STICKY
        }

        // Must be foreground within a few seconds of being started, whatever happens next.
        startInForeground()

        // A null intent is the system restarting us after the process was killed — which is how
        // the windows disappear: silently, all at once, while the user is in another app. There is
        // no hand-off waiting in that case, because the slot it travels through died with the
        // process; what the user had open is on disk.
        if (intent == null) {
            restoreWindows()
            return stickiness()
        }

        val payload = FloatingHandoff.takeToFloating()
        when {
            payload == null -> { logd("float add with no payload"); stopIfEmpty() }
            windows.size >= MAX_WINDOWS -> {
                logd("float refused: ${windows.size} windows already open")
                toast(getString(R.string.float_limit, MAX_WINDOWS))
                stopIfEmpty()
            }
            else -> addWindow(payload)
        }
        return stickiness()
    }

    /**
     * Whether the system should start us again if it kills the process.
     *
     * Only while windows are open: a service with nothing to restore that asks to be restarted is
     * just a process the system has to keep bringing back for no one.
     */
    private fun stickiness() = if (windows.isEmpty()) START_NOT_STICKY else START_STICKY

    /**
     * Puts back the windows the process died holding.
     *
     * Deliberately gives up after [FloatingState.MAX_RESTORES] in quick succession: if the reason
     * for the kill is this process being too expensive to keep, restoring rebuilds precisely the
     * thing that was killed, and a loop of that costs the user their battery to no end. Saying so
     * once is better than either silence or a fight.
     */
    private fun restoreWindows() {
        if (windows.isNotEmpty()) return
        val saved = FloatingState.load(this)
        if (saved.isEmpty()) { stopIfEmpty(); return }

        // The permission can have been withdrawn while we were gone, and addView would throw.
        if (!Settings.canDrawOverlays(this)) {
            logd("float restore skipped: no overlay permission")
            FloatingState.clear(this)
            stopIfEmpty()
            return
        }

        val attempt = FloatingState.noteRestore(this)
        if (attempt > FloatingState.MAX_RESTORES) {
            logd("float restore abandoned after $attempt attempts")
            FloatingState.clear(this)
            toast(getString(R.string.float_restore_gave_up))
            stopIfEmpty()
            return
        }

        logd("float restoring ${saved.size} windows attempt=$attempt")
        saved.take(MAX_WINDOWS).forEach { addWindow(it.payload, it.widthPx, it.x, it.y) }
        handler.postDelayed({ FloatingState.noteSettled(this) }, SETTLED_AFTER_MS)
    }

    private fun addWindow(payload: FloatingHandoff.Payload) {
        val width = if (lastWidthPx > 0) lastWidthPx else defaultWidthPx()
        // Cascade so a second window doesn't land exactly on top of the first.
        val offset = (windows.size * 28 * resources.displayMetrics.density).roundToInt()
        addWindow(
            payload = payload,
            widthPx = width,
            x = offset + (16 * resources.displayMetrics.density).roundToInt(),
            y = offset + (96 * resources.displayMetrics.density).roundToInt()
        )
    }

    /** Opens a window at an exact size and place — a restored one goes back where it was. */
    private fun addWindow(payload: FloatingHandoff.Payload, widthPx: Int, x: Int, y: Int) {
        val window = FloatingWindow(
            service = this,
            payload = payload,
            startWidthPx = widthPx,
            startX = x,
            startY = y
        )
        windows.add(window)
        windowCount = windows.size
        window.start()
        logd("float windows open=${windows.size}")
        updateNotification()
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, SAVE_EVERY_MS)
        saveState()
    }

    /**
     * Writes the open windows down, so a kill is survivable.
     *
     * Read straight off the players, on the main thread the runnable already runs on, because the
     * position is the part that goes stale fastest and it is the reason to save at all.
     */
    private fun saveState() {
        if (windows.isEmpty()) return
        runCatching { FloatingState.save(this, windows.mapNotNull { it.savedState() }) }
    }

    private fun defaultWidthPx(): Int {
        val screenW = resources.displayMetrics.widthPixels
        return (screenW * 0.45f).roundToInt()
            .coerceAtLeast((FloatingWindow.MIN_WIDTH_DP * resources.displayMetrics.density).roundToInt())
    }

    fun rememberWidth(px: Int) { lastWidthPx = px }

    /**
     * Called by a window that has torn itself down.
     *
     * Every route here is a deliberate close — the user, an expand, or a failure we gave up on —
     * so the window is dropped from the file too. A killed process reaches none of this, which is
     * exactly what makes the file left behind mean "these were still open".
     */
    fun onWindowClosed(window: FloatingWindow) {
        windows.remove(window)
        windowCount = windows.size
        if (windows.isEmpty()) {
            FloatingState.clear(this)
            stopIfEmpty()
        } else {
            updateNotification()
            saveState()
        }
    }

    fun onWindowTitleChanged() = updateNotification()

    private fun closeAll() {
        windows.toList().forEach { it.close() }
        windows.clear()
        windowCount = 0
        FloatingState.clear(this)
        stopIfEmpty()
    }

    private fun stopIfEmpty() {
        if (windows.isNotEmpty()) return
        handler.removeCallbacks(saveRunnable)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Rotation moves the screen edges; pull every window back inside them. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        windows.forEach { it.reclamp() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        windows.toList().forEach { it.close() }
        windows.clear()
        windowCount = 0
    }

    // ---------------- Notification ----------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.float_channel), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val count = windows.size.coerceAtLeast(1)
        val title = if (count == 1) getString(R.string.float_notification_one)
        else getString(R.string.float_notification_many, count)

        val closeAll = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingPlayerService::class.java).setAction(ACTION_CLOSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_float)
            .setContentTitle(title)
            .setContentText(windows.firstOrNull()?.title ?: "")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.float_close_all), closeAll)
            .build()
    }

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
    }

    private fun updateNotification() {
        if (windows.isEmpty()) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun logd(msg: String) { if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, msg) }
}

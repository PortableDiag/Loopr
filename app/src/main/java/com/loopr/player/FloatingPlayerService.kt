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
import android.os.IBinder
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

        /** How many windows are up, so the activity can refuse a fourth before tearing itself down. */
        @Volatile
        @JvmStatic
        var windowCount: Int = 0
            private set
    }

    private val windows = mutableListOf<FloatingWindow>()

    /** Last size the user pinched a window to, reused for the next one opened this session. */
    private var lastWidthPx = 0

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
        return START_NOT_STICKY
    }

    private fun addWindow(payload: FloatingHandoff.Payload) {
        val width = if (lastWidthPx > 0) lastWidthPx else defaultWidthPx()
        // Cascade so a second window doesn't land exactly on top of the first.
        val offset = (windows.size * 28 * resources.displayMetrics.density).roundToInt()
        val window = FloatingWindow(
            service = this,
            payload = payload,
            startWidthPx = width,
            startX = offset + (16 * resources.displayMetrics.density).roundToInt(),
            startY = offset + (96 * resources.displayMetrics.density).roundToInt()
        )
        windows.add(window)
        windowCount = windows.size
        window.start()
        logd("float windows open=${windows.size}")
        updateNotification()
    }

    private fun defaultWidthPx(): Int {
        val screenW = resources.displayMetrics.widthPixels
        return (screenW * 0.45f).roundToInt()
            .coerceAtLeast((FloatingWindow.MIN_WIDTH_DP * resources.displayMetrics.density).roundToInt())
    }

    fun rememberWidth(px: Int) { lastWidthPx = px }

    /** Called by a window that has torn itself down. */
    fun onWindowClosed(window: FloatingWindow) {
        windows.remove(window)
        windowCount = windows.size
        if (windows.isEmpty()) stopIfEmpty() else updateNotification()
    }

    fun onWindowTitleChanged() = updateNotification()

    private fun closeAll() {
        windows.toList().forEach { it.close() }
        windows.clear()
        windowCount = 0
        stopIfEmpty()
    }

    private fun stopIfEmpty() {
        if (windows.isNotEmpty()) return
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

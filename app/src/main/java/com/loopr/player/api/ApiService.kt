package com.loopr.player.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import com.loopr.player.R
import java.net.BindException

/**
 * Keeps the API listening while it is on; a quiet notification says so and has Stop.
 *
 * Its own service rather than part of [com.loopr.player.FloatingPlayerService]: that one stops the
 * moment the last window closes, and the API has to be there to open the first one.
 */
@UnstableApi
class ApiService : Service() {

    companion object {
        private const val TAG = "Loopr.Api"
        private const val CHANNEL = "local_api"
        private const val NOTIF_ID = 1972
        private const val STOP = "com.loopr.player.API_STOP"

        /** True while the server is listening, for the settings screen. */
        @Volatile var running = false
            private set

        /** Starts or stops the service to match the switch. */
        fun sync(context: Context) {
            val i = Intent(context, ApiService::class.java)
            if (ApiKeys(context).enabled) runCatching { context.startForegroundService(i) }
                .onFailure { Log.w(TAG, "could not start", it) }
            else context.stopService(i)
        }
    }

    private var server: ApiServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val keys = ApiKeys(this)
        if (intent?.action == STOP) {
            keys.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.api_channel), NotificationManager.IMPORTANCE_MIN)
                .apply { setShowBadge(false) }
        )
        val port = keys.port
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ApiService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_settings)
            .setContentTitle(getString(R.string.api_notification_title))
            .setContentText(getString(R.string.api_notification_text, ApiKeys.BIND, port))
            .addAction(0, getString(R.string.api_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        // A sticky restart after the process was killed may not be allowed into the foreground.
        // Thrown here, that would take the whole process down — floating windows included — so
        // stay off until the owner opens the API screen again instead.
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        }
        if (foreground.isFailure) {
            Log.w(TAG, "not allowed into the foreground", foreground.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }

        if (server?.isRunning != true) {
            val api = LocalApi(keys, AndroidBackend(this), port)
            server = ApiServer(api::handle, ApiKeys.BIND, port).also {
                runCatching { it.start() }.onSuccess { running = true }.onFailure { e ->
                    Log.w(TAG, "could not listen on $port: ${e.message}")
                    keys.failed(
                        if (e is BindException) getString(R.string.api_port_taken, port)
                        else getString(R.string.api_start_failed, port, e.message ?: "")
                    )
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        running = false
        super.onDestroy()
    }
}

/**
 * Puts the API back after a reboot, but only if it was switched on: an agent given a key and a
 * port has every reason to expect both to still work tomorrow. Does nothing while the API is off,
 * which is how it ships.
 */
@UnstableApi
class ApiBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ApiService.sync(context)
    }
}

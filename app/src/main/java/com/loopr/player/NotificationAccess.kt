package com.loopr.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Whether Loopr may post notifications, and whether it is worth asking.
 *
 * More rides on this than the floating-windows notification. **Android discards an app's toasts
 * entirely when its notifications are turned off** — so with this denied, every message Loopr
 * shows is thrown away before it reaches the screen. A window that closes itself then does so in
 * silence, and there is no way to tell that apart from the process having been killed, which is
 * exactly the confusion that cost a session of diagnosis.
 *
 * The permission is declared in the manifest and, until 1.14, was never actually requested — so on
 * a fresh install this was the *default* state rather than an unusual one.
 */
object NotificationAccess {

    /** Below Android 13 notifications need no runtime grant, so there is nothing to ask for. */
    private val needed: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private const val KEY_ASKED = "asked_notifications"

    const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    fun granted(context: Context): Boolean = !needed ||
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Asked once and only once.
     *
     * A refusal is an answer, and re-prompting on every launch is how an app teaches you to dismiss
     * it without reading. Anyone who changes their mind has the system settings.
     */
    fun shouldAsk(context: Context): Boolean = needed && !granted(context) && !asked(context)

    /** Called when the question has been put, however it was answered — including "not now". */
    fun markAsked(context: Context) =
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()

    private fun asked(context: Context) = prefs(context).getBoolean(KEY_ASKED, false)

    private fun prefs(context: Context) =
        context.getSharedPreferences(ThemeManager.PREFS, Context.MODE_PRIVATE)
}

package com.loopr.player.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.util.UnstableApi

/**
 * Debug builds only: makes a key and turns the API on without tapping through the screen, so a
 * device test can drive the API from adb. The key goes to logcat, which is why this never ships.
 *
 *     adb shell am broadcast -n com.loopr.player/.api.DebugApiKeyReceiver \
 *         --es name test --es tools all          # or a comma list: loopr.players,loopr.playback
 *     adb logcat -d -s Loopr.ApiDebug
 */
@UnstableApi
class DebugApiKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val keys = ApiKeys(context)
        val (key, raw) = keys.create(intent.getStringExtra("name") ?: "debug")
        val wanted = intent.getStringExtra("tools").orEmpty()
        val tools = if (wanted == "all") Tools.all.map { it.name }.toSet()
        else wanted.split(',').map { it.trim() }.filter { Tools.find(it) != null }.toSet()
        keys.setTools(key.id, tools)
        keys.enabled = true
        ApiService.sync(context)
        Log.i("Loopr.ApiDebug", "key ${key.id} port ${keys.port} tools ${tools.size} $raw")
    }
}

package com.loopr.player.api

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.google.android.material.materialswitch.MaterialSwitch
import com.loopr.player.R
import com.loopr.player.ThemeManager
import com.loopr.player.databinding.ActivityPhoneapiGrantBinding

/**
 * PhoneAPI asks for a key (`com.phoneapi.action.GRANT_KEY`, provider contract §3), with
 * `startActivityForResult`. The key travels only as this activity's result: never a file, a
 * broadcast or the clipboard.
 *
 * Refuses (RESULT_CANCELED, nothing shown) any caller that is not PhoneAPI's release build.
 * Otherwise the owner picks what PhoneAPI's key may do from Loopr's own tool switches, every one
 * off, and on Allow Loopr mints the key and a proof key and switches the local API on, since that
 * is what they agreed to.
 */
@UnstableApi
class PhoneApiGrantActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneapiGrantBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applySaved(this)
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val caller = callingPackage
        if (intent?.action != PhoneApiProof.ACTION_GRANT_KEY || !PhoneApiLink.isTrusted(this, caller)) {
            Log.w(TAG, "key request refused (" +
                (caller?.let { "$it is not PhoneAPI's release build" } ?: "not started for a result") + ")")
            finish()
            return
        }
        val eventPort = intent.getIntExtra(EXTRA_EVENT_PORT, 0)
        val eventKey = intent.getStringExtra(EXTRA_EVENT_KEY)

        binding = ActivityPhoneapiGrantBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val keys = ApiKeys(this)
        binding.body.text = buildString {
            append(getString(R.string.phoneapi_body))
            if (!keys.enabled) append(' ').append(getString(R.string.phoneapi_turns_on))
            if (PhoneApiLink.linked(this@PhoneApiGrantActivity)) append(' ').append(getString(R.string.phoneapi_replaces))
        }

        // Loopr's own key switches, one per tool, every one off.
        val switches = Tools.all.map { tool ->
            MaterialSwitch(this).apply {
                text = tool.label
                isChecked = false
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                binding.tools.addView(this)
            } to tool.name
        }
        // A key that may do nothing is not worth minting.
        val sync = { binding.allow.isEnabled = switches.any { it.first.isChecked } }
        switches.forEach { (s, _) -> s.setOnCheckedChangeListener { _, _ -> sync() } }
        sync()

        binding.deny.setOnClickListener {
            Log.i(TAG, "key request declined")
            finish()
        }
        binding.allow.setOnClickListener {
            val tools = switches.filter { it.first.isChecked }.map { it.second }.toSet()
            val (apiKey, proofKey) = PhoneApiLink.grant(this, tools, eventPort, eventKey)
            keys.enabled = true
            ApiService.sync(this)
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra("api_key", apiKey)
                    .putExtra("proof_key", proofKey)
                    .putExtra("port", keys.port)
            )
            finish()
        }
    }

    companion object {
        private const val TAG = "Loopr.PhoneApi"
        private const val EXTRA_EVENT_PORT = "phoneapi_port"
        private const val EXTRA_EVENT_KEY = "phoneapi_event_key"
    }
}

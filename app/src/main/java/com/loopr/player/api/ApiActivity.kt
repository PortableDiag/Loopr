package com.loopr.player.api

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.loopr.player.R
import com.loopr.player.ThemeManager
import com.loopr.player.databinding.ActivityApiBinding

/**
 * Loopr › ⋮ › Local API: the switch, the port, and a key per caller with its own tool switches.
 *
 * A new key starts with every tool off. The owner picks what it may do straight after making it,
 * and can change that, or revoke the key, at any time; a change takes effect on the next call.
 */
class ApiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiBinding
    private lateinit var keys: ApiKeys

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applySaved(this)
        super.onCreate(savedInstanceState)
        binding = ActivityApiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        keys = ApiKeys(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.enabled.setOnCheckedChangeListener { _, on ->
            if (on == keys.enabled) return@setOnCheckedChangeListener
            keys.enabled = on
            ApiService.sync(this)
            // The service reports a port it could not take a moment later.
            binding.root.postDelayed({ render() }, 400)
            render()
        }
        binding.port.setOnClickListener { editPort() }
        binding.newKey.setOnClickListener { newKey() }
    }

    override fun onResume() {
        super.onResume()
        // On, but not listening: the service was not allowed back after the process was killed.
        // From here, in the foreground, it is.
        if (keys.enabled && !ApiService.running) ApiService.sync(this)
        render()
    }

    private fun render() {
        binding.enabled.isChecked = keys.enabled
        val port = keys.port
        binding.status.text = when {
            ApiKeys.failure != null -> ApiKeys.failure
            keys.enabled && ApiService.running -> getString(R.string.api_status_on, ApiKeys.BIND, port)
            keys.enabled -> getString(R.string.api_status_starting)
            else -> getString(R.string.api_status_off)
        }
        binding.port.text = getString(R.string.api_port, port)
        binding.help.text = getString(R.string.api_help, port)

        binding.keys.removeAllViews()
        val all = keys.keys()
        if (all.isEmpty()) binding.keys.addView(line(getString(R.string.api_no_keys), secondary = true))
        all.forEach { k ->
            val used = if (k.lastUsedAt > 0) getString(
                R.string.api_key_used,
                DateUtils.getRelativeTimeSpanString(k.lastUsedAt, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS), k.calls
            ) else getString(R.string.api_key_unused)
            val row = line("${k.name}  ·  …${k.tail}\n" +
                getString(R.string.api_key_tools, k.tools.size, Tools.all.size) + "  ·  " + used)
            row.setOnClickListener { keyMenu(k.id) }
            binding.keys.addView(row)
        }
    }

    private fun line(text: String, secondary: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        if (secondary) alpha = 0.7f
        minHeight = dp(56)
        setPadding(0, dp(8), 0, dp(8))
        setBackgroundResource(android.R.drawable.list_selector_background)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun input(value: String, type: Int): Pair<View, EditText> {
        val edit = EditText(this).apply { setText(value); inputType = type; setSelection(value.length) }
        val frame = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(edit) }
        return frame to edit
    }

    private fun editPort() {
        val (view, edit) = input(keys.port.toString(), InputType.TYPE_CLASS_NUMBER)
        AlertDialog.Builder(this)
            .setTitle(R.string.api_port_title)
            .setMessage(R.string.api_port_body)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val p = edit.text.toString().toIntOrNull() ?: return@setPositiveButton
                keys.port = p
                // A running server keeps the port it bound; restart it on the new one.
                if (keys.enabled) { stopService(android.content.Intent(this, ApiService::class.java)); ApiService.sync(this) }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun newKey() {
        val (view, edit) = input("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        edit.hint = getString(R.string.api_key_name_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.api_new_key)
            .setMessage(R.string.api_key_name_body)
            .setView(view)
            .setPositiveButton(R.string.api_create) { _, _ ->
                val (key, raw) = keys.create(edit.text.toString())
                render()
                showOnce(key.id, raw)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The only time the key is ever visible. Copying it is the obvious next step, so it is the button. */
    private fun showOnce(id: Long, raw: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.api_key_made)
            .setMessage(getString(R.string.api_key_made_body, raw))
            .setCancelable(false)
            .setPositiveButton(R.string.api_copy) { _, _ ->
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Loopr API key", raw))
                pickTools(id)
            }
            .setNegativeButton(R.string.api_next) { _, _ -> pickTools(id) }
            .show()
    }

    private fun keyMenu(id: Long) {
        val k = keys.keys().firstOrNull { it.id == id } ?: return
        val options = arrayOf(
            getString(R.string.api_key_choose_tools), getString(R.string.api_key_activity),
            getString(R.string.api_key_rename), getString(R.string.api_key_revoke)
        )
        AlertDialog.Builder(this)
            .setTitle("${k.name}  ·  …${k.tail}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickTools(id)
                    1 -> showActivity(id)
                    2 -> rename(id)
                    3 -> revoke(id)
                }
            }
            .show()
    }

    /** Every tool its own switch, off until ticked. */
    private fun pickTools(id: Long) {
        val k = keys.keys().firstOrNull { it.id == id } ?: return
        val labels = Tools.all.map { it.label }.toTypedArray()
        val checked = Tools.all.map { it.name in k.tools }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.api_tools_for, k.name))
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.api_save) { _, _ ->
                keys.setTools(id, Tools.all.filterIndexed { i, _ -> checked[i] }.map { it.name }.toSet())
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showActivity(id: Long) {
        val k = keys.keys().firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.api_activity_for, k.name))
            .setMessage(k.activity.reversed().joinToString("\n").ifEmpty { getString(R.string.api_key_unused) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun rename(id: Long) {
        val k = keys.keys().firstOrNull { it.id == id } ?: return
        val (view, edit) = input(k.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        AlertDialog.Builder(this)
            .setTitle(R.string.api_key_rename)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ -> keys.rename(id, edit.text.toString()); render() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun revoke(id: Long) {
        val k = keys.keys().firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.api_key_revoke)
            .setMessage(getString(R.string.api_revoke_body, k.name))
            .setPositiveButton(R.string.api_key_revoke) { _, _ -> keys.revoke(id); render() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

package com.loopr.player

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.loopr.player.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        /** When set, each picked video opens in its own player instead of reusing one. */
        const val KEY_MULTI_INSTANCE = "multi_instance"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VideoAdapter
    private var videos: List<VideoItem> = emptyList()

    private val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val requestPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadVideos() else showPermissionPrompt()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applySaved(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Bottom inset padding for the grid so last row clears the nav bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.recycler) { v, insets ->
            val sb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = sb.bottom + (24 * resources.displayMetrics.density).toInt())
            insets
        }

        val span = (resources.configuration.screenWidthDp / 180).coerceIn(2, 5)
        adapter = VideoAdapter(lifecycleScope) { item, pos -> openPlayer(item, pos) }
        binding.recycler.layoutManager = GridLayoutManager(this, span)
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)

        binding.grantButton.setOnClickListener { requestAccess() }
    }

    override fun onStart() {
        super.onStart()
        if (hasPermission()) loadVideos() else showPermissionPrompt()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestAccess() = requestPerm.launch(permission)

    private fun showPermissionPrompt() {
        binding.permissionView.visibility = View.VISIBLE
        binding.recycler.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_multi)?.isChecked = multiInstanceEnabled()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (onMenu(item)) true else super.onOptionsItemSelected(item)

    private fun onMenu(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> { if (hasPermission()) loadVideos(); true }
        R.id.action_theme -> { showThemeDialog(); true }
        R.id.action_multi -> { toggleMultiInstance(item); true }
        else -> false
    }

    private fun multiInstanceEnabled(): Boolean =
        getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE)
            .getBoolean(KEY_MULTI_INSTANCE, false)

    private fun toggleMultiInstance(item: MenuItem) {
        val enabled = !multiInstanceEnabled()
        getSharedPreferences(ThemeManager.PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_MULTI_INSTANCE, enabled).apply()
        item.isChecked = enabled
        Toast.makeText(
            this, if (enabled) R.string.multi_on else R.string.multi_off, Toast.LENGTH_SHORT
        ).show()
    }

    private fun showThemeDialog() {
        val options = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val current = ThemeManager.savedMode(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(options, current) { dialog, which ->
                dialog.dismiss()
                if (which != current) ThemeManager.setMode(this, which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openPlayer(item: VideoItem, position: Int) {
        PlayQueue.items = videos
        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = item.uri
            putExtra(PlayerActivity.EXTRA_TITLE, item.title)
            putExtra(PlayerActivity.EXTRA_INDEX, position)
            if (multiInstanceEnabled()) {
                // Each video gets its own task/instance, kept separate in recents.
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                )
            } else {
                // Reuse the single live player, swapping in the new video.
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
        startActivity(intent)
    }

    private fun loadVideos() {
        binding.permissionView.visibility = View.GONE
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { queryVideos() }
            videos = items
            adapter.submitList(items)
            binding.recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun queryVideos(): List<VideoItem> {
        val list = ArrayList<VideoItem>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, null, null, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri: Uri = ContentUris.withAppendedId(collection, id)
                list.add(
                    VideoItem(
                        id = id,
                        uri = uri,
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
        return list
    }
}

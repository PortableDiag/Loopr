package com.loopr.player

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.loopr.player.databinding.ActivityModelsBinding
import com.loopr.player.model.ModelItem
import com.loopr.player.model.ModelLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The 3D model library: Loopr's own Models folder, the folders the owner adds here, and files they
 * opened. Android's media permission doesn't reach `.vrm` / `.glb` / `.gltf`, so each source is
 * one the owner picked in Android's own picker and Loopr keeps.
 */
class ModelsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelsBinding
    private val adapter = Adapter()

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { ModelLibrary.addTree(this, uri); reload(true) }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            ModelLibrary.addFile(this, uri)
            reload(true)
            open(listOf(ModelLibrary.fromUri(this, uri)), 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applySaved(this)
        super.onCreate(savedInstanceState)
        binding = ActivityModelsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        val span = (resources.configuration.screenWidthDp / 180).coerceIn(2, 5)
        binding.recycler.layoutManager = GridLayoutManager(this, span)
        binding.recycler.adapter = adapter
        reload(false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_models, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_add_folder -> { pickFolder.launch(null); true }
        R.id.action_open_file -> { pickFile.launch(arrayOf("model/*", "application/octet-stream")); true }
        R.id.action_remove_folder -> { removeFolder(); true }
        R.id.action_refresh -> { reload(true); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun removeFolder() {
        val trees = ModelLibrary.grantedTrees(this)
        if (trees.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.models_none_to_remove).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val names = trees.map { t -> DocumentsContract.getTreeDocumentId(t).substringAfter(':').ifEmpty { t.toString() } }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.models_remove_folders)
            .setItems(names) { _, which -> ModelLibrary.removeTree(this, trees[which]); reload(true) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun reload(refresh: Boolean) {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { ModelLibrary.list(this@ModelsActivity, refresh) }
            adapter.items = items
            adapter.notifyDataSetChanged()
            val empty = items.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
            binding.emptyView.text = getString(R.string.models_empty, ModelLibrary.ownFolder(this@ModelsActivity)?.absolutePath ?: "")
        }
    }

    /** A model opens with its folder as the queue, as a video from a file manager does. */
    private fun open(queue: List<ModelItem>, index: Int) {
        ModelViewerActivity.pendingQueue = queue
        startActivity(Intent(this, ModelViewerActivity::class.java)
            .putExtra(ModelViewerActivity.EXTRA_INDEX, index)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        var items: List<ModelItem> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val badge: TextView = v.findViewById(R.id.duration)
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            h.title.text = item.name
            h.badge.text = item.name.substringAfterLast('.').uppercase()
            h.subtitle.text = listOfNotNull(
                item.folder.ifEmpty { null },
                item.sizeBytes.takeIf { it >= 0 }?.let { Formatter.formatShortFileSize(this@ModelsActivity, it) }
            ).joinToString(" · ")
            h.itemView.setOnClickListener {
                val folder = ModelLibrary.folderOf(this@ModelsActivity, item)
                open(folder, folder.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            }
            val cached = ModelLibrary.cachedThumb(item.id)
            if (cached != null) { h.thumb.setImageBitmap(cached); return }
            h.thumb.setImageResource(R.drawable.ic_video)
            h.thumb.tag = item.id
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { ModelLibrary.thumbnail(this@ModelsActivity, item) }
                if (bmp != null && h.thumb.tag == item.id) h.thumb.setImageBitmap(bmp)
            }
        }
    }
}

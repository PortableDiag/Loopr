package com.loopr.player.model

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.LruCache
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One 3D model file Loopr can open. */
data class ModelItem(
    /** Stable across runs (it comes from the uri), and in a range MediaStore's video ids never reach. */
    val id: Long,
    val uri: Uri,
    val name: String,
    /** The folder's name as a person reads it. */
    val folder: String,
    /** Identifies the folder, so two folders called "Avatars" stay apart. */
    val folderKey: String,
    val sizeBytes: Long,
    val modified: Long,
    /** A filesystem path when there is one: Loopr's own folder, or a granted folder on the phone's storage. */
    val path: String?,
    /** The document uri of the containing folder, for a `.gltf`'s side files; null for a single granted file. */
    val parent: Uri? = null
)

/**
 * The 3D models Loopr can see. Android's media permission covers photos and videos only, so models
 * come from places Loopr has been given explicitly:
 *
 * 1. **Loopr's own folder**, `Android/data/com.loopr.player/files/Models`, readable without any
 *    permission — the place to `adb push` models for scripts and agents.
 * 2. **Folders the owner adds** in the 3D models screen (Android's folder picker; the permission is
 *    kept across restarts), searched four levels deep.
 * 3. **Files the owner opened** through the picker, kept the same way.
 *
 * Plus whatever a file manager hands Loopr directly, which needs none of this.
 */
object ModelLibrary {

    private const val ID_BASE = 1L shl 40
    private const val MAX_DEPTH = 4
    private const val MAX_ITEMS = 5000
    /** A direct buffer this size is native memory, not heap; beyond it is not a model anyone views on a phone. */
    const val MAX_BYTES = 512L * 1024 * 1024
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    @Volatile private var cached: List<ModelItem>? = null
    private val thumbs = LruCache<Long, Bitmap>(96)

    fun idOf(uri: Uri): Long = ID_BASE + (uri.toString().hashCode().toLong() and 0xFFFFFFFFL)

    fun isModelId(id: Long): Boolean = id >= ID_BASE

    fun ownFolder(context: Context): File? =
        context.getExternalFilesDir("Models")?.also { it.mkdirs() }

    /** Folders the owner granted, by tree uri. */
    fun grantedTrees(context: Context): List<Uri> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri }

    private fun grantedFiles(context: Context): List<Uri> = context.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && !DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri }

    fun addTree(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        cached = null
    }

    fun addFile(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        cached = null
    }

    fun removeTree(context: Context, tree: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        cached = null
    }

    /** Every model, newest first (as the video library). Scans on first use or when [refresh]; off the main thread. */
    fun list(context: Context, refresh: Boolean = false): List<ModelItem> {
        cached?.takeIf { !refresh }?.let { return it }
        val out = LinkedHashMap<Long, ModelItem>()
        ownFolder(context)?.let { scanDir(it, 0, out) }
        val cr = context.contentResolver
        for (tree in grantedTrees(context)) {
            val rootDoc = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            scanTree(cr, tree, rootDoc, pathOf(tree.authority, rootDoc), 0, out)
        }
        for (uri in grantedFiles(context)) {
            if (out.size >= MAX_ITEMS) break
            describe(cr, uri)?.let { (name, size) ->
                if (ModelMeta.isModelName(name)) {
                    val id = idOf(uri)
                    out[id] = ModelItem(id, uri, name, "Opened files", "opened", size, 0L, null)
                }
            }
        }
        return out.values.sortedByDescending { it.modified }.also { cached = it }
    }

    fun find(context: Context, id: Long): ModelItem? =
        list(context).firstOrNull { it.id == id } ?: list(context, refresh = true).firstOrNull { it.id == id }

    /** The model at a filesystem path: in Loopr's folder, in a granted folder, or any file Loopr may read. */
    fun findByPath(context: Context, raw: String): ModelItem? {
        val path = normalisePath(raw)
        list(context).firstOrNull { it.path == path }?.let { return it }
        list(context, refresh = true).firstOrNull { it.path == path }?.let { return it }
        val f = File(path)
        if (f.isFile && f.canRead() && ModelMeta.isModelName(f.name)) return fileItem(f)
        return null
    }

    /** The models beside [item], in natural name order — the folder queue. */
    fun folderOf(context: Context, item: ModelItem): List<ModelItem> {
        val all = list(context).filter { it.folderKey == item.folderKey }
        val withItem = if (all.any { it.id == item.id }) all else all + item
        return withItem.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    fun normalisePath(raw: String): String {
        val p = raw.trim()
        return when {
            p.startsWith("/sdcard/") -> "/storage/emulated/0/" + p.removePrefix("/sdcard/")
            p.startsWith("/storage/self/primary/") -> "/storage/emulated/0/" + p.removePrefix("/storage/self/primary/")
            else -> p
        }
    }

    /** An item for a uri a file manager sent, which no scan will ever list. */
    fun fromUri(context: Context, uri: Uri, fallbackName: String? = null): ModelItem {
        if (uri.scheme == ContentResolver.SCHEME_FILE) uri.path?.let { return fileItem(File(it)) }
        val (name, size) = describe(context.contentResolver, uri) ?: ((fallbackName ?: uri.lastPathSegment ?: "Model") to -1L)
        return ModelItem(idOf(uri), uri, name, "Opened", "uri:" + (uri.authority ?: ""), size, 0L, null)
    }

    private fun fileItem(f: File): ModelItem {
        val uri = Uri.fromFile(f)
        return ModelItem(idOf(uri), uri, f.name, f.parentFile?.name ?: "", "file:" + (f.parent ?: ""), f.length(), f.lastModified(), f.absolutePath)
    }

    private fun scanDir(dir: File, depth: Int, out: MutableMap<Long, ModelItem>) {
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (out.size >= MAX_ITEMS) return
            if (c.isDirectory) { if (depth < MAX_DEPTH) scanDir(c, depth + 1, out) }
            else if (ModelMeta.isModelName(c.name)) fileItem(c).let { out[it.id] = it }
        }
    }

    private fun scanTree(cr: ContentResolver, tree: Uri, docId: String, path: String?, depth: Int, out: MutableMap<Long, ModelItem>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val folderUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val folderName = path?.substringAfterLast('/') ?: docId.substringAfterLast(':').substringAfterLast('/')
        val dirs = ArrayList<Pair<String, String>>()
        runCatching {
            cr.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (out.size >= MAX_ITEMS) return
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) dirs.add(id to name)
                        continue
                    }
                    if (!ModelMeta.isModelName(name)) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val item = ModelItem(
                        idOf(uri), uri, name, folderName.ifEmpty { "Models" }, "doc:$folderUri",
                        if (c.isNull(3)) -1 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4),
                        path?.let { "$it/$name" }, folderUri
                    )
                    out[item.id] = item
                }
            }
        }
        if (depth < MAX_DEPTH) for ((id, name) in dirs) scanTree(cr, tree, id, path?.let { "$it/$name" }, depth + 1, out)
    }

    /** `primary:Models/x` on the phone's own storage is `/storage/emulated/0/Models/x`. */
    private fun pathOf(authority: String?, docId: String): String? {
        if (authority != EXTERNAL_STORAGE) return null
        val volume = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "").trimEnd('/')
        val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    private fun describe(cr: ContentResolver, uri: Uri): Pair<String, Long>? = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else (c.getString(0) ?: "Model") to (if (c.isNull(1)) -1L else c.getLong(1))
        }
    }.getOrNull()

    fun open(context: Context, uri: Uri): InputStream =
        if (uri.scheme == ContentResolver.SCHEME_FILE) FileInputStream(uri.path!!)
        else context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Android gave no stream for $uri")

    /** The whole file in native memory, as Filament wants it. */
    fun readAll(context: Context, uri: Uri): ByteBuffer {
        val size = runCatching {
            if (uri.scheme == ContentResolver.SCHEME_FILE) File(uri.path!!).length()
            else context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
        if (size > MAX_BYTES) throw java.io.IOException("The model is ${size / (1024 * 1024)} MB; Loopr opens models up to ${MAX_BYTES / (1024 * 1024)} MB")
        open(context, uri).use { input ->
            if (size > 0) {
                val buf = ByteBuffer.allocateDirect(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                val chunk = ByteArray(256 * 1024)
                while (buf.hasRemaining()) {
                    val n = input.read(chunk, 0, minOf(chunk.size, buf.remaining()))
                    if (n < 0) break
                    buf.put(chunk, 0, n)
                }
                buf.flip()
                return buf
            }
            val bytes = input.readBytes()
            if (bytes.size > MAX_BYTES) throw java.io.IOException("The model is over ${MAX_BYTES / (1024 * 1024)} MB")
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(bytes).also { it.flip() }
        }
    }

    /** A `.gltf`'s side file (`scene.bin`, `textures/skin.png`), found next to it. */
    fun sideFile(context: Context, item: ModelItem, relative: String): ByteBuffer? {
        val rel = Uri.decode(relative).trimStart('/')
        if (rel.split('/').any { it == ".." }) return null
        item.path?.let { p ->
            val f = File(File(p).parentFile, rel)
            if (f.isFile && f.canRead()) return readAll(context, Uri.fromFile(f))
        }
        val parent = item.parent ?: return null
        val tree = parent
        var docId = DocumentsContract.getDocumentId(parent)
        val parts = rel.split('/')
        for ((i, part) in parts.withIndex()) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val next = context.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ), null, null, null)?.use { c ->
                var found: String? = null
                while (c.moveToNext()) if (c.getString(1) == part) { found = c.getString(0); break }
                found
            } ?: return null
            docId = next
            if (i == parts.lastIndex) return readAll(context, DocumentsContract.buildDocumentUriUsingTree(tree, docId))
        }
        return null
    }

    fun cachedThumb(id: Long): Bitmap? = thumbs.get(id)

    /** The model's own embedded thumbnail (VRM carries one), scaled for the grid; null for most glTF. */
    fun thumbnail(context: Context, item: ModelItem): Bitmap? {
        thumbs.get(item.id)?.let { return it }
        val bytes = runCatching { open(context, item.uri).use { ModelMeta.peekThumbnail(it) } }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        thumbs.put(item.id, bmp)
        return bmp
    }
}

/** `ep2` before `ep10`, case ignored: how a file manager orders a folder. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i; while (i < a.length && a[i].isDigit()) i++
                val sj = j; while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

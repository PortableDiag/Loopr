package com.loopr.player.model

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * What a VRM, GLB or glTF file says about itself, read without a renderer.
 *
 * VRM is a GLB with avatar extensions: `VRM` (0.x) or `VRMC_vrm` (1.0). Both carry a title, authors,
 * a licence and an embedded thumbnail, which is what the library shows and `loopr.players` reports.
 * Pure Kotlin on [org.json], so every rule here runs on the JVM.
 */
class ModelMeta(
    /** `VRM 1.0`, `VRM 0.x` or `glTF`, the names Loopr Desktop uses. */
    val format: String,
    val title: String?,
    val authors: List<String>,
    val version: String?,
    val license: String?,
    val triangles: Long,
    val vertices: Long,
    val textures: Int,
    /** The embedded thumbnail (PNG or JPEG bytes), or null. */
    val thumbnail: ByteArray?,
    /** Buffers and images a `.gltf` names outside itself; a GLB or VRM carries everything. */
    val externalUris: List<String>,
    /** Extensions the file requires; a renderer that lacks one may draw it wrongly. */
    val extensionsRequired: List<String>,
    /** The thumbnail's image index in `images`, or -1. */
    private val thumbnailImage: Int = -1
) {
    /** (offset, length) of the thumbnail inside the binary chunk, when it lives there. */
    internal fun thumbnailView(root: JSONObject): Pair<Int, Int>? {
        val image = root.optJSONArray("images")?.optJSONObject(thumbnailImage) ?: return null
        if (!image.has("bufferView")) return null
        val view = root.optJSONArray("bufferViews")?.optJSONObject(image.optInt("bufferView")) ?: return null
        if (view.optInt("buffer", 0) != 0) return null
        val len = view.optInt("byteLength", 0)
        val off = view.optInt("byteOffset", 0)
        return if (len > 0 && off >= 0) off to len else null
    }

    /** VRM 0.x avatars face −Z; glTF's front (and VRM 1.0's) is +Z. */
    val facesMinusZ: Boolean get() = format == VRM0

    companion object {
        const val VRM1 = "VRM 1.0"
        const val VRM0 = "VRM 0.x"
        const val GLTF = "glTF"

        val EXTENSIONS = setOf("vrm", "glb", "gltf")

        fun isModelName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

        private const val GLB_MAGIC = 0x46546C67 // "glTF"
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BIN = 0x004E4942

        /**
         * Reads [data] (a whole `.vrm`, `.glb` or `.gltf`) or throws [IllegalArgumentException] with a
         * sentence a person can act on.
         */
        fun parse(data: ByteBuffer): ModelMeta {
            val b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            b.position(0)
            val (json, bin) = if (b.remaining() >= 12 && b.getInt(0) == GLB_MAGIC) glb(b) else {
                val bytes = ByteArray(b.remaining()).also { b.get(it) }
                val text = String(bytes, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
                if (!text.startsWith("{")) throw IllegalArgumentException("Not a glTF, GLB or VRM file")
                text to null
            }
            val root = try { JSONObject(json) } catch (e: Exception) {
                throw IllegalArgumentException("The model's JSON could not be read: ${e.message}")
            }
            return read(root, bin)
        }

        /**
         * The same model with every `\/` in its JSON written as `/`. JSON allows both, and UniVRM
         * (which wrote most VRMs) escapes the slash, but Filament's loader compares `mimeType`
         * as raw text — so `"image\/png"` finds no PNG decoder and every texture is dropped,
         * leaving an avatar as a flat-coloured silhouette. Returns [data] itself when there is
         * nothing to change.
         */
        fun unescapeSlashes(data: ByteBuffer): ByteBuffer {
            val b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val glb = b.limit() >= 20 && b.getInt(0) == GLB_MAGIC && b.getInt(16) == CHUNK_JSON
            val jsonStart = if (glb) 20 else 0
            val jsonLen = if (glb) b.getInt(12) else b.limit()
            if (jsonLen < 0 || jsonStart + jsonLen > b.limit()) return data
            val bytes = ByteArray(jsonLen)
            b.duplicate().apply { position(jsonStart) }.get(bytes)
            val fixed = unescapeSlashes(bytes) ?: return data
            if (!glb) return ByteBuffer.allocateDirect(fixed.size).order(ByteOrder.LITTLE_ENDIAN).put(fixed).also { it.flip() }
            val padded = (fixed.size + 3) and 3.inv()
            val rest = b.limit() - (jsonStart + jsonLen)
            val out = ByteBuffer.allocateDirect(20 + padded + rest).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(GLB_MAGIC).putInt(2).putInt(20 + padded + rest)
            out.putInt(padded).putInt(CHUNK_JSON).put(fixed)
            repeat(padded - fixed.size) { out.put(' '.code.toByte()) }
            out.put(b.duplicate().apply { position(jsonStart + jsonLen) })
            out.flip()
            return out
        }

        /** `\/` → `/` outside of an escaped backslash; null when the text has none. */
        internal fun unescapeSlashes(json: ByteArray): ByteArray? {
            var found = false
            val out = java.io.ByteArrayOutputStream(json.size)
            var i = 0
            while (i < json.size) {
                val c = json[i]
                if (c == '\\'.code.toByte() && i + 1 < json.size) {
                    if (json[i + 1] == '/'.code.toByte()) { out.write('/'.code); found = true }
                    else { out.write(c.toInt()); out.write(json[i + 1].toInt()) }
                    i += 2
                    continue
                }
                out.write(c.toInt())
                i++
            }
            return if (found) out.toByteArray() else null
        }

        /**
         * Just the embedded thumbnail, read from a stream without loading the whole model: the GLB
         * header, its JSON, then a skip straight to the image's bytes. A library of hundreds of
         * avatars costs a few kilobytes each this way rather than their full size.
         */
        fun peekThumbnail(input: java.io.InputStream): ByteArray? {
            val s = java.io.DataInputStream(java.io.BufferedInputStream(input, 64 * 1024))
            fun le(): Int = Integer.reverseBytes(s.readInt())
            if (le() != GLB_MAGIC) return null
            le(); le() // version, length
            val jsonLen = le()
            if (le() != CHUNK_JSON || jsonLen <= 0 || jsonLen > 64 * 1024 * 1024) return null
            val json = ByteArray(jsonLen).also { s.readFully(it) }
            val meta = runCatching { read(JSONObject(String(json, Charsets.UTF_8)), null) }.getOrNull() ?: return null
            val root = JSONObject(String(json, Charsets.UTF_8))
            val view = meta.thumbnailView(root) ?: return meta.thumbnail // a data: URI was read already
            s.skipFully(((jsonLen + 3) and 3.inv()) - jsonLen)
            le() // BIN length
            if (le() != CHUNK_BIN) return null
            s.skipFully(view.first)
            return ByteArray(view.second).also { s.readFully(it) }
        }

        private fun java.io.DataInputStream.skipFully(n: Int) {
            var left = n.toLong()
            while (left > 0) {
                val k = skip(left)
                if (k <= 0) { if (read() < 0) throw java.io.EOFException(); left-- } else left -= k
            }
        }

        private fun glb(b: ByteBuffer): Pair<String, ByteBuffer?> {
            val version = b.getInt(4)
            if (version != 2) throw IllegalArgumentException("GLB version $version; only glTF 2.0 is supported")
            val length = b.getInt(8).toLong().coerceAtMost(b.limit().toLong()).toInt()
            var at = 12
            var json: String? = null
            var bin: ByteBuffer? = null
            while (at + 8 <= length) {
                val size = b.getInt(at)
                val type = b.getInt(at + 4)
                val start = at + 8
                if (size < 0 || start + size > length) throw IllegalArgumentException("The GLB is cut short")
                when (type) {
                    CHUNK_JSON -> {
                        val bytes = ByteArray(size)
                        b.duplicate().apply { position(start) }.get(bytes)
                        json = String(bytes, Charsets.UTF_8)
                    }
                    CHUNK_BIN -> if (bin == null) bin = b.duplicate().apply { position(start); limit(start + size) }.slice()
                }
                at = start + ((size + 3) and 3.inv())
            }
            return (json ?: throw IllegalArgumentException("The GLB has no JSON chunk")) to bin
        }

        private fun read(root: JSONObject, bin: ByteBuffer?): ModelMeta {
            val ext = root.optJSONObject("extensions") ?: JSONObject()
            val vrm1 = ext.optJSONObject("VRMC_vrm")
            val vrm0 = ext.optJSONObject("VRM")
            val images = root.optJSONArray("images") ?: JSONArray()

            var title: String? = null
            val authors = ArrayList<String>()
            var version: String? = null
            var license: String? = null
            var thumbImage = -1
            val format = when {
                vrm1 != null -> {
                    val m = vrm1.optJSONObject("meta") ?: JSONObject()
                    title = m.optString("name").ifBlank { null }
                    m.optJSONArray("authors")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(authors::add) }
                    version = m.optString("version").ifBlank { null }
                    license = m.optString("licenseUrl").ifBlank { null }
                    thumbImage = m.optInt("thumbnailImage", -1)
                    VRM1
                }
                vrm0 != null -> {
                    val m = vrm0.optJSONObject("meta") ?: JSONObject()
                    title = m.optString("title").ifBlank { null }
                    m.optString("author").takeIf { it.isNotBlank() }?.let(authors::add)
                    version = m.optString("version").ifBlank { null }
                    license = m.optString("licenseName").takeIf { it.isNotBlank() && it != "Other" }
                        ?: m.optString("otherLicenseUrl").ifBlank { null }
                    // 0.x names a texture, not an image.
                    val tex = m.optInt("texture", -1)
                    thumbImage = root.optJSONArray("textures")?.optJSONObject(tex)?.optInt("source", -1) ?: -1
                    VRM0
                }
                else -> {
                    val a = root.optJSONObject("asset")
                    title = a?.optJSONObject("extras")?.optString("title")?.ifBlank { null }
                    a?.optJSONObject("extras")?.optString("author")?.takeIf { it.isNotBlank() }?.let(authors::add)
                    a?.optJSONObject("extras")?.optString("license")?.ifBlank { null }?.let { license = it }
                    GLTF
                }
            }

            val accessors = root.optJSONArray("accessors") ?: JSONArray()
            var tris = 0L
            var verts = 0L
            val meshes = root.optJSONArray("meshes") ?: JSONArray()
            for (i in 0 until meshes.length()) {
                val prims = meshes.optJSONObject(i)?.optJSONArray("primitives") ?: continue
                for (j in 0 until prims.length()) {
                    val p = prims.optJSONObject(j) ?: continue
                    val posCount = accessors.optJSONObject(p.optJSONObject("attributes")?.optInt("POSITION", -1) ?: -1)?.optLong("count") ?: 0L
                    verts += posCount
                    val n = if (p.has("indices")) accessors.optJSONObject(p.optInt("indices"))?.optLong("count") ?: 0L else posCount
                    tris += when (p.optInt("mode", 4)) {
                        4 -> n / 3
                        5, 6 -> (n - 2).coerceAtLeast(0)
                        else -> 0
                    }
                }
            }

            val external = ArrayList<String>()
            fun collect(arr: JSONArray?) {
                if (arr == null) return
                for (i in 0 until arr.length()) {
                    val u = arr.optJSONObject(i)?.optString("uri").orEmpty()
                    if (u.isNotEmpty() && !u.startsWith("data:")) external.add(u)
                }
            }
            collect(root.optJSONArray("buffers"))
            collect(images)

            val required = ArrayList<String>()
            root.optJSONArray("extensionsRequired")?.let { for (i in 0 until it.length()) required.add(it.optString(i)) }

            return ModelMeta(
                format = format, title = title, authors = authors, version = version, license = license,
                triangles = tris, vertices = verts, textures = images.length(),
                thumbnail = if (thumbImage >= 0) imageBytes(root, images.optJSONObject(thumbImage), bin) else null,
                externalUris = external, extensionsRequired = required, thumbnailImage = thumbImage
            )
        }

        /** An image's bytes, from the GLB's binary chunk or a `data:` URI; null if it lives elsewhere. */
        private fun imageBytes(root: JSONObject, image: JSONObject?, bin: ByteBuffer?): ByteArray? {
            if (image == null) return null
            val uri = image.optString("uri")
            if (uri.startsWith("data:")) {
                val comma = uri.indexOf(',')
                if (comma < 0 || !uri.substring(0, comma).endsWith(";base64")) return null
                return runCatching { Base64.getDecoder().decode(uri.substring(comma + 1)) }.getOrNull()
            }
            if (!image.has("bufferView") || bin == null) return null
            val view = root.optJSONArray("bufferViews")?.optJSONObject(image.optInt("bufferView")) ?: return null
            if (view.optInt("buffer", 0) != 0) return null
            val off = view.optInt("byteOffset", 0)
            val len = view.optInt("byteLength", 0)
            if (off < 0 || len <= 0 || off + len > bin.limit()) return null
            val out = ByteArray(len)
            bin.duplicate().apply { position(off) }.get(out)
            return out
        }
    }
}

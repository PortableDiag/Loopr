package com.loopr.player.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** VRM and glTF files built in memory, so the parser's rules run without fixtures on disk. */
class ModelMetaTest {

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, 4, 5)

    /** A one-triangle GLB whose binary chunk holds 36 bytes of positions then [png]. */
    private fun glb(extensions: JSONObject?, textures: JSONArray? = null): ByteArray {
        val bin = ByteArray(36) + png
        val json = JSONObject()
            .put("asset", JSONObject().put("version", "2.0"))
            .put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size)))
            .put("bufferViews", JSONArray()
                .put(JSONObject().put("buffer", 0).put("byteOffset", 0).put("byteLength", 36))
                .put(JSONObject().put("buffer", 0).put("byteOffset", 36).put("byteLength", png.size)))
            .put("accessors", JSONArray().put(JSONObject().put("bufferView", 0).put("componentType", 5126).put("count", 3).put("type", "VEC3")))
            .put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(JSONObject().put("attributes", JSONObject().put("POSITION", 0))))))
            .put("images", JSONArray().put(JSONObject().put("bufferView", 1).put("mimeType", "image/png")))
            .apply { textures?.let { put("textures", it) } }
            .apply { extensions?.let { put("extensions", it) } }
        var j = json.toString().toByteArray()
        while (j.size % 4 != 0) j += ' '.code.toByte()
        var b = bin
        while (b.size % 4 != 0) b += 0
        val out = ByteBuffer.allocate(12 + 8 + j.size + 8 + b.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(out.capacity())
        out.putInt(j.size).putInt(0x4E4F534A).put(j)
        out.putInt(b.size).putInt(0x004E4942).put(b)
        return out.array()
    }

    private fun parse(bytes: ByteArray) = ModelMeta.parse(ByteBuffer.wrap(bytes))

    @Test
    fun `a VRM 0 x avatar names itself and faces backwards`() {
        val vrm0 = JSONObject().put("VRM", JSONObject().put("meta", JSONObject()
            .put("title", "Checker Box").put("author", "Loopr").put("version", "1").put("licenseName", "CC0").put("texture", 0)))
        val m = parse(glb(vrm0, JSONArray().put(JSONObject().put("source", 0))))
        assertEquals(ModelMeta.VRM0, m.format)
        assertEquals("Checker Box", m.title)
        assertEquals(listOf("Loopr"), m.authors)
        assertEquals("CC0", m.license)
        assertEquals(1L, m.triangles)
        assertEquals(3L, m.vertices)
        assertTrue(m.facesMinusZ)
        assertArrayEquals(png, m.thumbnail)
    }

    @Test
    fun `a VRM 1 0 avatar names its thumbnail image directly`() {
        val vrm1 = JSONObject().put("VRMC_vrm", JSONObject().put("meta", JSONObject()
            .put("name", "Seed").put("authors", JSONArray().put("A").put("B")).put("licenseUrl", "https://vrm.dev/licenses/1.0/").put("thumbnailImage", 0)))
        val m = parse(glb(vrm1))
        assertEquals(ModelMeta.VRM1, m.format)
        assertEquals("Seed", m.title)
        assertEquals(listOf("A", "B"), m.authors)
        assertEquals(false, m.facesMinusZ)
        assertArrayEquals(png, m.thumbnail)
    }

    @Test
    fun `a plain GLB is glTF with no thumbnail`() {
        val m = parse(glb(null))
        assertEquals(ModelMeta.GLTF, m.format)
        assertNull(m.thumbnail)
        assertTrue(m.externalUris.isEmpty())
    }

    @Test
    fun `a gltf names its side files`() {
        val json = """{"asset":{"version":"2.0"},"buffers":[{"uri":"scene.bin","byteLength":4}],
            "images":[{"uri":"textures/skin.png"},{"uri":"data:image/png;base64,AAAA"}]}"""
        val m = parse(json.toByteArray())
        assertEquals(listOf("scene.bin", "textures/skin.png"), m.externalUris)
    }

    @Test
    fun `the thumbnail is read from a stream without the whole file`() {
        val vrm1 = JSONObject().put("VRMC_vrm", JSONObject().put("meta", JSONObject().put("name", "x").put("thumbnailImage", 0)))
        assertArrayEquals(png, ModelMeta.peekThumbnail(ByteArrayInputStream(glb(vrm1))))
        assertNull(ModelMeta.peekThumbnail(ByteArrayInputStream(glb(null))))
        assertNull(ModelMeta.peekThumbnail(ByteArrayInputStream("not a model".toByteArray())))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a file that is not a model is refused with a reason`() {
        parse("hello".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a cut-short GLB is refused rather than read past its end`() {
        val whole = glb(null)
        // The header still claims the full length; the binary chunk claims bytes that are not there.
        parse(ByteArrayOutputStream().apply { write(whole, 0, whole.size - 20) }.toByteArray())
    }

    @Test
    fun `escaped slashes are written plainly so Filament finds its PNG decoder`() {
        val esc = """{"images":[{"mimeType":"image\/png"}],"x":"a\\/b"}"""
        val fixed = String(ModelMeta.unescapeSlashes(esc.toByteArray())!!)
        assertEquals("""{"images":[{"mimeType":"image/png"}],"x":"a\\/b"}""", fixed)
        assertNull(ModelMeta.unescapeSlashes("""{"a":"b/c"}""".toByteArray()))
        // A GLB keeps its binary chunk and a valid, padded layout.
        val vrm1 = JSONObject().put("VRMC_vrm", JSONObject().put("meta", JSONObject().put("name", "x").put("thumbnailImage", 0)))
        val raw = glb(vrm1)
        val text = String(raw, Charsets.ISO_8859_1).replace("image/png", "image\\/png")
        val escaped = text.toByteArray(Charsets.ISO_8859_1)
        // fix the JSON chunk length and total length for the extra byte(s) by re-building through parse
        val out = ModelMeta.unescapeSlashes(ByteBuffer.wrap(rebuild(escaped)))
        val back = ByteArray(out.remaining()).also { out.duplicate().get(it) }
        assertArrayEquals(png, ModelMeta.parse(ByteBuffer.wrap(back)).thumbnail)
        assertEquals(0, back.size % 4)
        assertTrue(!String(back, Charsets.ISO_8859_1).contains("\\/"))
    }

    /** Re-frames a GLB whose JSON text was edited in place (so its lengths are stale). */
    private fun rebuild(edited: ByteArray): ByteArray {
        val b = ByteBuffer.wrap(edited).order(ByteOrder.LITTLE_ENDIAN)
        val oldLen = b.getInt(12)
        val binAt = 20 + oldLen
        val grow = edited.size - (b.getInt(8))
        var json = edited.copyOfRange(20, binAt + grow)
        while (json.size % 4 != 0) json += ' '.code.toByte()
        val bin = edited.copyOfRange(binAt + grow, edited.size)
        val out = ByteBuffer.allocate(20 + json.size + bin.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(out.capacity()).putInt(json.size).putInt(0x4E4F534A).put(json).put(bin)
        return out.array()
    }

    @Test
    fun `extensions are matched without case`() {
        assertTrue(ModelMeta.isModelName("Avatar.VRM"))
        assertTrue(ModelMeta.isModelName("a.glb"))
        assertTrue(!ModelMeta.isModelName("a.mp4"))
    }
}

package com.loopr.player.api

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The router: who gets in, which tool a request means, and what a refusal looks like.
 *
 * Every rule that keeps an agent inside what its owner allowed lives here rather than in the
 * Android half, which is the reason for the split — these run on the JVM, against a fake that
 * records what reached it.
 */
class LocalApiTest {

    private class FakeKeys(var list: List<ApiKey>) : KeyStore {
        val lines = mutableListOf<String>()
        override fun keys() = list
        override fun used(id: Long, line: String) { lines += line }
    }

    private class FakeBackend : LooprBackend {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        var fail: ToolError? = null
        override val version = "test"
        override fun run(tool: String, args: JSONObject): Any {
            calls += tool to args
            fail?.let { throw it }
            return JSONObject().put("tool", tool).put("players", JSONArray().put(JSONObject().put("id", 7)))
        }
        override fun thumbnailJpeg(videoId: Long) = byteArrayOf(1, 2, 3)
    }

    private val raw = KeyHash.newKey()
    private val allTools = Tools.all.map { it.name }.toSet()
    private var now = 1_000_000L

    private fun setup(tools: Set<String> = allTools): Triple<LocalApi, FakeBackend, FakeKeys> {
        val keys = FakeKeys(listOf(ApiKey(1, "script", KeyHash.hash(raw), raw.takeLast(4), tools)))
        val backend = FakeBackend()
        return Triple(LocalApi(keys, backend, 8793) { now }, backend, keys)
    }

    private fun req(method: String, path: String, body: String = "", key: String? = raw, host: String = "127.0.0.1:8793"): ApiRequest {
        val headers = mutableMapOf("host" to host)
        if (key != null) headers["x-api-key"] = key
        val q = path.substringAfter('?', "")
        return ApiRequest(method, path.substringBefore('?'), Http.parseQuery(q), headers, body.toByteArray())
    }

    private fun json(r: ApiResponse) = JSONObject(String(r.body))

    @Test
    fun `health answers without a key and nothing else does`() {
        val (api, backend) = setup()
        assertEquals(200, api.handle(req("GET", "/api/health", key = null)).status)
        assertEquals(401, api.handle(req("GET", "/api/players", key = null)).status)
        assertEquals(401, api.handle(req("GET", "/api/players", key = "lp_wrong")).status)
        assertEquals(401, api.handle(req("GET", "/api/players", key = raw.drop(3))).status)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `bearer works as well as X-API-Key`() {
        val (api) = setup()
        val r = ApiRequest("GET", "/api/players", headers = mapOf("host" to "127.0.0.1", "authorization" to "Bearer $raw"))
        assertEquals(200, api.handle(r).status)
    }

    /** A page in the phone's browser that resolves its own name to 127.0.0.1 still sends its own Host. */
    @Test
    fun `a foreign Host is refused even with the right key`() {
        val (api, backend) = setup()
        assertEquals(403, api.handle(req("GET", "/api/players", host = "evil.example:8793")).status)
        assertEquals(200, api.handle(req("GET", "/api/players", host = "localhost:8793")).status)
        assertEquals(1, backend.calls.size)
    }

    @Test
    fun `a new key can do nothing`() {
        val (api, backend) = setup(tools = emptySet())
        for (t in Tools.all) {
            val r = api.handle(req("POST", "/api/tools/${t.name}", "{}"))
            assertEquals(t.name, 403, r.status)
            assertTrue(json(r).getString("error").contains("no tools switched on"))
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `each switch opens exactly its own tool`() {
        for (t in Tools.all) {
            val (api, backend) = setup(tools = setOf(t.name))
            for (other in Tools.all) {
                val status = api.handle(req("POST", "/api/tools/${other.name}", "{}")).status
                if (other == t) assertEquals(200, status) else assertEquals("${t.name} opened ${other.name}", 403, status)
            }
            assertEquals(listOf(t.name), backend.calls.map { it.first })
        }
    }

    @Test
    fun `the REST spellings reach the tool they stand for`() {
        val cases = listOf(
            Triple("GET", "/api/videos?q=cat&limit=5", "loopr.library"),
            Triple("GET", "/api/folders", "loopr.folders"),
            Triple("POST", "/api/open", "loopr.open"),
            Triple("GET", "/api/players", "loopr.players"),
            Triple("DELETE", "/api/players", "loopr.window"),
            Triple("PATCH", "/api/players/3", "loopr.options"),
            Triple("DELETE", "/api/players/3", "loopr.window"),
            Triple("GET", "/api/players/3/queue", "loopr.queue"),
            Triple("POST", "/api/players/3/pause", "loopr.playback"),
            Triple("POST", "/api/players/3/seek", "loopr.playback"),
            Triple("PUT", "/api/players/3/ab", "loopr.ab_loop"),
            Triple("DELETE", "/api/players/3/ab", "loopr.ab_loop"),
            Triple("POST", "/api/players/3/set_a", "loopr.ab_loop"),
            Triple("POST", "/api/players/3/float", "loopr.window"),
            Triple("GET", "/api/settings", "loopr.settings"),
            Triple("PATCH", "/api/settings", "loopr.change_settings")
        )
        for ((m, path, tool) in cases) {
            val (api, backend) = setup()
            val body = if (m == "GET" || m == "DELETE") "" else "{}"
            assertEquals("$m $path", 200, api.handle(req(m, path, body)).status)
            assertEquals("$m $path", tool, backend.calls.single().first)
        }
    }

    @Test
    fun `a player route carries its id and its action`() {
        val (api, backend) = setup()
        api.handle(req("POST", "/api/players/3/seek", """{"delta_ms":-10000}"""))
        api.handle(req("POST", "/api/players/3/seek", """{"position_ms":5000}"""))
        api.handle(req("DELETE", "/api/players/3"))
        val (seekBy, seek, close) = backend.calls.map { it.second }
        assertEquals(3, seekBy.getInt("player_id")); assertEquals("seek_by", seekBy.getString("action"))
        assertEquals("seek", seek.getString("action"))
        assertEquals("close", close.getString("action"))
    }

    @Test
    fun `refusals keep their meaning as a status`() {
        val expected = mapOf(
            ToolError.BAD_ARGS to 400, ToolError.NOT_FOUND to 404, ToolError.REFUSED to 409
        )
        for ((code, status) in expected) {
            val (api, backend) = setup()
            backend.fail = ToolError(code, "because")
            val r = api.handle(req("POST", "/api/players/1/play", "{}"))
            assertEquals(code, status, r.status)
            assertEquals("because", json(r).getString("error"))
            assertEquals(code, json(r).getString("hint"))
        }
    }

    @Test
    fun `a crash in a tool is an answer, not a dropped socket`() {
        val (api) = setup()
        val keys = FakeKeys(listOf(ApiKey(1, "s", KeyHash.hash(raw), "", allTools)))
        val boom = object : LooprBackend {
            override val version = "t"
            override fun run(tool: String, args: JSONObject): Any = throw IllegalStateException("kaput")
            override fun thumbnailJpeg(videoId: Long) = ByteArray(0)
        }
        val r = LocalApi(keys, boom, 1).handle(req("GET", "/api/players"))
        assertEquals(500, r.status)
        assertTrue(json(r).getString("error").contains("kaput"))
        assertNotEquals(null, api)
    }

    @Test
    fun `GET one player picks it out of the list`() {
        val (api) = setup()
        assertEquals(7, json(api.handle(req("GET", "/api/players/7"))).getInt("id"))
        assertEquals(404, api.handle(req("GET", "/api/players/8")).status)
    }

    @Test
    fun `the thumbnail is gated by its own switch and served as an image`() {
        val (open) = setup(tools = setOf("loopr.thumbnail"))
        val r = open.handle(req("GET", "/api/videos/42/thumbnail"))
        assertEquals(200, r.status)
        assertEquals("image/jpeg", r.contentType)
        assertEquals(3, r.body.size)
        val (shut) = setup(tools = setOf("loopr.library"))
        assertEquals(403, shut.handle(req("GET", "/api/videos/42/thumbnail")).status)
    }

    @Test
    fun `the rate cap stops a loop and lifts after a minute`() {
        val (api) = setup()
        repeat(LocalApi.CALLS_PER_MINUTE) { assertEquals(200, api.handle(req("GET", "/api/players")).status) }
        assertEquals(429, api.handle(req("GET", "/api/players")).status)
        now += 60_000
        assertEquals(200, api.handle(req("GET", "/api/players")).status)
    }

    @Test
    fun `every call is noted against its key`() {
        val (api, _, keys) = setup(tools = setOf("loopr.players"))
        api.handle(req("GET", "/api/players"))
        api.handle(req("POST", "/api/players/1/pause", "{}"))
        assertEquals(listOf("loopr.players ok", "loopr.playback not_allowed"), keys.lines)
    }

    @Test
    fun `MCP lists only the key's tools and calls by MCP name`() {
        val (api, backend) = setup(tools = setOf("loopr.players", "loopr.playback"))
        val list = json(api.handle(req("POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")))
        val names = list.getJSONObject("result").getJSONArray("tools").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
        assertEquals(listOf("loopr_players", "loopr_playback"), names)

        val call = json(api.handle(req("POST", "/mcp",
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"loopr_playback","arguments":{"action":"pause"}}}""")))
        assertFalse(call.getJSONObject("result").getBoolean("isError"))
        assertEquals("pause", backend.calls.single().second.getString("action"))

        val denied = json(api.handle(req("POST", "/mcp",
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"loopr_open","arguments":{}}}""")))
        assertTrue(denied.getJSONObject("result").getBoolean("isError"))
        assertEquals(1, backend.calls.size)
    }

    @Test
    fun `an MCP notification gets no body`() {
        val (api) = setup()
        assertEquals(202, api.handle(req("POST", "/mcp", """{"jsonrpc":"2.0","method":"notifications/initialized"}""")).status)
    }

    /** The reference and the router can drift apart; this walks one against the other. */
    @Test
    fun `every documented route routes`() {
        val (api) = setup()
        for (route in api.documentedRoutes) {
            val (m, path) = route.split(' ')
            val concrete = path.replace("{id}", "3").replace("{name}", "loopr.players")
            val body = when {
                path == "/mcp" -> """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
                m == "GET" || m == "DELETE" -> ""
                else -> "{}"
            }
            val status = api.handle(req(m, concrete, body)).status
            assertTrue("$route answered $status", status == 200 || (route == "GET /api/players/{id}" && status == 404))
        }
        val docs = String(api.handle(req("GET", "/api/docs")).body)
        for (t in Tools.all) assertTrue("docs miss ${t.name}", docs.contains(t.name))
    }

    @Test
    fun `tool schemas are valid JSON`() {
        for (t in Tools.all) JSONObject(t.schema)
    }

    @Test
    fun `keys are random, prefixed and only match themselves`() {
        val a = KeyHash.newKey()
        val b = KeyHash.newKey()
        assertNotEquals(a, b)
        assertTrue(a.startsWith(KeyHash.PREFIX))
        val stored = listOf(ApiKey(1, "a", KeyHash.hash(a), ""), ApiKey(2, "b", KeyHash.hash(b), ""))
        assertEquals(2L, KeyHash.match(stored, b)?.id)
        assertEquals(null, KeyHash.match(stored, a + "x"))
    }
}

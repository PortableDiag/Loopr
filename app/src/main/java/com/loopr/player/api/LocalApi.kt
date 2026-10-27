package com.loopr.player.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the tools actually do, behind one call. [AndroidBackend] is the real one; the router
 * below never sees an Android type, which is what lets its every decision be tested on the JVM.
 */
interface LooprBackend {
    val version: String

    /** Runs [tool] (already allowed for the caller) and returns its result, or throws [ToolError]. */
    fun run(tool: String, args: JSONObject): Any

    /** A library video's thumbnail as JPEG bytes, for the REST route that serves it as an image. */
    fun thumbnailJpeg(videoId: Long): ByteArray
}

/**
 * Loopr's local API: the DialX, TextX and NotifX shape.
 *
 * - **Loopback only**, and a request carrying any other `Host` is refused, so a web page that
 *   resolves its own name to 127.0.0.1 (DNS rebinding) cannot reach it.
 * - **A key per caller**, each with its own switches, every one off until the owner turns it on.
 * - **The API does everything the app does**: REST for scripts, MCP for agents, and
 *   `POST /api/tools/{name}` for either — all three run the same [Tools] through the same gate.
 */
class LocalApi(
    private val keys: KeyStore,
    private val backend: LooprBackend,
    private val port: Int,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** A script polling the player every second is 60; this leaves it room and stops a loop. */
        const val CALLS_PER_MINUTE = 240
        private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1")
        /** The main thread did not answer in time: worth a retry, unlike [INTERNAL]. */
        private const val UNAVAILABLE = "unavailable"
        private const val INTERNAL = "internal"
    }

    private val recentCalls = HashMap<Long, ArrayDeque<Long>>()

    fun handle(req: ApiRequest): ApiResponse {
        val host = req.header("host")?.substringBeforeLast(':')?.trim('[', ']')
        if (host != null && host !in LOCAL_HOSTS) return ApiResponse.error(403, "Only this phone's own address is served")
        if (req.method == "OPTIONS") return ApiResponse.error(405, "No cross-origin requests")
        if (req.path == "/api/health") {
            return ApiResponse.json(JSONObject().put("ok", true).put("app", "Loopr").put("version", backend.version))
        }

        val raw = req.header("x-api-key") ?: req.header("authorization")?.removePrefix("Bearer ")?.trim()
        val key = KeyHash.match(keys.keys(), raw)
            ?: return ApiResponse.error(401, "A valid key is required", "Loopr › ⋮ › Local API makes one; send it as X-API-Key")

        val p = req.path.split('/').drop(1) // "/api/players/3/play" -> [api, players, 3, play]
        val m = req.method
        return when {
            req.path == "/mcp" && m == "POST" -> mcp(key, req)
            req.path == "/mcp" -> ApiResponse.error(405, "POST JSON-RPC to /mcp")
            p.firstOrNull() != "api" -> notFound()

            m == "GET" && req.path == "/api/docs" -> ApiResponse.text(docs(key), type = "text/markdown; charset=utf-8")
            m == "GET" && req.path == "/api/me" -> ApiResponse.json(
                JSONObject().put("name", key.name).put("tools", JSONArray(Tools.all.map { it.name }.filter { it in key.tools }))
            )
            m == "GET" && req.path == "/api/tools" -> ApiResponse.json(JSONArray(Tools.all.map { t ->
                JSONObject().put("name", t.name).put("description", t.description)
                    .put("enabled", t.name in key.tools).put("input_schema", JSONObject(t.schema))
            }))
            m == "POST" && p.size == 3 && p[1] == "tools" -> rest(key, p[2], req.json())

            m == "GET" && req.path == "/api/videos" -> rest(key, "loopr.library", JSONObject()
                .putOpt("q", req.query["q"]).putOpt("folder", req.query["folder"])
                .put("limit", req.queryInt("limit", 50)).put("offset", req.queryInt("offset", 0)))
            m == "GET" && req.path == "/api/folders" -> rest(key, "loopr.folders", JSONObject())
            m == "GET" && p.size == 4 && p[1] == "videos" && p[3] == "thumbnail" -> thumbnail(key, p[2])
            m == "POST" && req.path == "/api/open" -> rest(key, "loopr.open", req.json())
            m == "GET" && req.path == "/api/settings" -> rest(key, "loopr.settings", JSONObject())
            m == "PATCH" && req.path == "/api/settings" -> rest(key, "loopr.change_settings", req.json())

            p.getOrNull(1) == "players" -> players(key, req, p)
            else -> notFound()
        }
    }

    /** `/api/players…`: each route is a spelling of one tool call with the player id filled in. */
    private fun players(key: ApiKey, req: ApiRequest, p: List<String>): ApiResponse {
        val m = req.method
        if (p.size == 2) return when (m) {
            "GET" -> rest(key, "loopr.players", JSONObject())
            "DELETE" -> rest(key, "loopr.window", JSONObject().put("action", "close_all"))
            else -> ApiResponse.error(405, "GET lists the players, DELETE closes them all")
        }
        val id = p[2].toIntOrNull() ?: return ApiResponse.error(400, "A player id is a number", "GET /api/players")
        val body = if (m == "GET" || m == "DELETE") JSONObject() else req.json()
        body.put("player_id", id)
        val action = p.getOrNull(3)
        return when {
            p.size == 3 && m == "GET" -> rest(key, "loopr.players", JSONObject(), onlyPlayer = id)
            p.size == 3 && m == "PATCH" -> rest(key, "loopr.options", body)
            p.size == 3 && m == "DELETE" -> rest(key, "loopr.window", body.put("action", "close"))
            p.size == 4 && action == "queue" && m == "GET" -> rest(key, "loopr.queue", body
                .put("offset", req.queryInt("offset", 0)).put("limit", req.queryInt("limit", 100)))
            p.size == 4 && action == "ab" && m == "PUT" -> rest(key, "loopr.ab_loop", body.put("action", "set"))
            p.size == 4 && action == "ab" && m == "DELETE" -> rest(key, "loopr.ab_loop", body.put("action", "clear"))
            p.size == 4 && action in setOf("set_a", "set_b") && m == "POST" -> rest(key, "loopr.ab_loop", body.put("action", action))
            p.size == 4 && m == "POST" && action == "seek" ->
                rest(key, "loopr.playback", body.put("action", if (body.has("delta_ms")) "seek_by" else "seek"))
            p.size == 4 && m == "POST" && action in PLAYBACK -> rest(key, "loopr.playback", body.put("action", action))
            p.size == 4 && m == "POST" && action in WINDOW -> rest(key, "loopr.window", body.put("action", action))
            else -> notFound()
        }
    }

    private val PLAYBACK = setOf("play", "pause", "toggle", "next", "previous", "jump")
    private val WINDOW = setOf("float", "expand", "pip", "rotate", "move", "resize", "close")

    /** One tool call through every gate: known, switched on for this key, under the rate cap. */
    private fun run(
        key: ApiKey, name: String, args: JSONObject,
        exec: (String, JSONObject) -> Any = backend::run
    ): JSONObject {
        val tool = Tools.find(name)
        val result = try {
            when {
                tool == null -> throw ToolError(ToolError.UNKNOWN_TOOL, "No tool named $name; GET /api/tools lists them")
                tool.name !in key.tools -> throw ToolError(
                    ToolError.NOT_ALLOWED,
                    "This key may not use ${tool.name} (${tool.label}). The phone's owner can switch it on in Loopr › ⋮ › Local API › ${key.name}." +
                        allowedNote(key)
                )
                !underRateCap(key.id) -> throw ToolError(ToolError.RATE_LIMITED, "More than $CALLS_PER_MINUTE calls in a minute; wait and try again")
                else -> JSONObject().put("ok", true).put("result", exec(tool.name, args))
            }
        } catch (e: ToolError) {
            failure(e.code, e.message)
        } catch (e: ApiException) {
            failure(if (e.status == 503) UNAVAILABLE else INTERNAL, e.message)
        } catch (e: Exception) {
            failure(INTERNAL, "${e.javaClass.simpleName}: ${e.message}")
        }
        keys.used(key.id, "${tool?.name ?: name} ${if (result.optBoolean("ok")) "ok" else result.getJSONObject("error").getString("code")}")
        return result
    }

    private fun failure(code: String, message: String?) =
        JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message ?: code))

    private fun allowedNote(key: ApiKey): String {
        val on = Tools.all.map { it.name }.filter { it in key.tools }
        return if (on.isEmpty()) " It has no tools switched on yet." else " It may use: ${on.joinToString()}."
    }

    private fun underRateCap(id: Long): Boolean = synchronized(recentCalls) {
        val now = clock()
        val q = recentCalls.getOrPut(id) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= 60_000) q.removeFirst()
        if (q.size >= CALLS_PER_MINUTE) return false
        q.addLast(now)
        true
    }

    private fun rest(key: ApiKey, name: String, args: JSONObject, onlyPlayer: Int? = null): ApiResponse {
        val r = run(key, name, args)
        if (!r.optBoolean("ok")) return toolFailure(r)
        val result = r.get("result")
        if (onlyPlayer != null && result is JSONObject) {
            val list = result.optJSONArray("players") ?: JSONArray()
            for (i in 0 until list.length()) {
                val one = list.getJSONObject(i)
                if (one.optInt("id") == onlyPlayer) return ApiResponse.json(one)
            }
            return ApiResponse.error(404, "No player $onlyPlayer is open", "GET /api/players")
        }
        return when (result) {
            is JSONArray -> ApiResponse.json(result)
            is JSONObject -> ApiResponse.json(result)
            else -> ApiResponse.json(JSONObject().put("result", result))
        }
    }

    private fun toolFailure(r: JSONObject): ApiResponse {
        val e = r.getJSONObject("error")
        val status = when (e.optString("code")) {
            ToolError.NOT_ALLOWED -> 403
            ToolError.BAD_ARGS -> 400
            ToolError.NOT_FOUND, ToolError.UNKNOWN_TOOL -> 404
            ToolError.REFUSED -> 409
            ToolError.RATE_LIMITED -> 429
            UNAVAILABLE -> 503
            else -> 500
        }
        return ApiResponse.error(status, e.optString("message"), e.optString("code"))
    }

    private fun thumbnail(key: ApiKey, rawId: String): ApiResponse {
        val id = rawId.toLongOrNull() ?: return ApiResponse.error(400, "A video id is a number", "GET /api/videos")
        var jpeg = ByteArray(0)
        val r = run(key, "loopr.thumbnail", JSONObject().put("video_id", id)) { _, _ ->
            jpeg = backend.thumbnailJpeg(id); JSONObject()
        }
        if (!r.optBoolean("ok")) return toolFailure(r)
        return ApiResponse(200, "image/jpeg", jpeg)
    }

    private fun notFound() = ApiResponse.error(404, "No such route", "GET /api/docs lists them")

    // ---------------- MCP: streamable HTTP, JSON responses, stateless ----------------

    private fun mcp(key: ApiKey, req: ApiRequest): ApiResponse {
        val msg = runCatching { req.json() }.getOrNull()
            ?: return ApiResponse.json(rpcError(JSONObject.NULL, -32700, "Parse error"))
        if (!msg.has("id")) return ApiResponse(202, "text/plain", ByteArray(0)) // a notification
        val id = msg.get("id")
        val params = msg.optJSONObject("params") ?: JSONObject()
        val result = when (msg.optString("method")) {
            "initialize" -> JSONObject()
                .put("protocolVersion", params.optString("protocolVersion").ifEmpty { "2025-06-18" })
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", "Loopr").put("version", backend.version))
                .put("instructions", "Tools of the phone's video player. Only the tools the phone's owner switched on for this key are listed. " +
                    "Most take a player_id from loopr_players.")
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", JSONArray(Tools.all.filter { it.name in key.tools }.map { t ->
                JSONObject().put("name", t.mcpName).put("title", t.label).put("description", t.description)
                    .put("inputSchema", JSONObject(t.schema))
            }))
            "tools/call" -> {
                val name = params.optString("name").ifEmpty { return ApiResponse.json(rpcError(id, -32602, "name is required")) }
                val r = run(key, name, params.optJSONObject("arguments") ?: JSONObject())
                val ok = r.optBoolean("ok")
                JSONObject()
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", (if (ok) r.get("result") else r.get("error")).toString())))
                    .put("isError", !ok)
            }
            else -> return ApiResponse.json(rpcError(id, -32601, "Method not found"))
        }
        return ApiResponse.json(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))
    }

    private fun rpcError(id: Any, code: Int, message: String) = JSONObject()
        .put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message))

    // ---------------- The reference, served by the build that answers it ----------------

    /** Every route [docs] lists, so a test can walk them and fail on one that does not route. */
    internal val documentedRoutes = listOf(
        "GET /api/health", "GET /api/me", "GET /api/tools", "GET /api/docs", "POST /api/tools/{name}",
        "GET /api/videos", "GET /api/folders", "GET /api/videos/{id}/thumbnail", "POST /api/open",
        "GET /api/players", "DELETE /api/players", "GET /api/players/{id}", "PATCH /api/players/{id}",
        "DELETE /api/players/{id}", "GET /api/players/{id}/queue",
        "POST /api/players/{id}/play", "POST /api/players/{id}/pause", "POST /api/players/{id}/toggle",
        "POST /api/players/{id}/seek", "POST /api/players/{id}/next", "POST /api/players/{id}/previous",
        "POST /api/players/{id}/jump", "PUT /api/players/{id}/ab", "DELETE /api/players/{id}/ab",
        "POST /api/players/{id}/set_a", "POST /api/players/{id}/set_b",
        "POST /api/players/{id}/float", "POST /api/players/{id}/expand", "POST /api/players/{id}/pip",
        "POST /api/players/{id}/rotate", "POST /api/players/{id}/move", "POST /api/players/{id}/resize",
        "POST /api/players/{id}/close", "GET /api/settings", "PATCH /api/settings", "POST /mcp"
    )

    private fun docs(key: ApiKey): String = buildString {
        appendLine("# Loopr local API ${backend.version}")
        appendLine()
        appendLine("Base: http://127.0.0.1:$port (this phone only; from a computer: `adb forward tcp:$port tcp:$port`).")
        appendLine("Auth: `X-API-Key: <key>` (or `Authorization: Bearer <key>`) on everything except `GET /api/health`.")
        appendLine("This key: **${key.name}**.${allowedNote(key)}")
        appendLine()
        appendLine("## Rules")
        appendLine("- Every tool is off for a new key until the phone's owner switches it on (Loopr › ⋮ › Local API). A call to one that is off answers 403 with what this key may use.")
        appendLine("- A refusal (409) says why and what is left, e.g. floating windows need Android's \"Display over other apps\", and three is the most at once.")
        appendLine("- Players are numbered; `GET /api/players` lists them. `player_id` may be left out while only one is open.")
        appendLine("- Positions are milliseconds. $CALLS_PER_MINUTE calls a minute per key.")
        appendLine()
        appendLine("## REST")
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| `GET /api/me` · `GET /api/tools` | this key and its tools; every tool, its schema, whether this key may use it |")
        appendLine("| `GET /api/videos?q=&folder=&limit=50&offset=0` | the library, newest first |")
        appendLine("| `GET /api/folders` | folders holding videos |")
        appendLine("| `GET /api/videos/{id}/thumbnail` | the thumbnail, as image/jpeg |")
        appendLine("| `POST /api/open` `{\"video_id\":42,\"queue\":\"folder\",\"window\":\"floating\",\"position_ms\":0,\"paused\":false}` | open a video; answers the new player |")
        appendLine("| `GET /api/players` · `GET /api/players/{id}` | open players and their state |")
        appendLine("| `GET /api/players/{id}/queue?offset=0&limit=100` | a player's queue |")
        appendLine("| `POST /api/players/{id}/play` · `pause` · `toggle` · `next` · `previous` | the transport buttons |")
        appendLine("| `POST /api/players/{id}/seek` `{\"position_ms\":90000}` or `{\"delta_ms\":-10000}` | seek, or rewind / skip forward |")
        appendLine("| `POST /api/players/{id}/jump` `{\"index\":3}` | play another video in the queue |")
        appendLine("| `PUT /api/players/{id}/ab` `{\"a_ms\":5000,\"b_ms\":9000}` · `DELETE …/ab` · `POST …/set_a` · `…/set_b` | the A-B loop |")
        appendLine("| `PATCH /api/players/{id}` `{\"speed\":1.5,\"muted\":true,\"resize\":\"crop\",\"repeat\":\"one\",\"shuffle\":false,\"zoom\":2}` | options |")
        appendLine("| `POST /api/players/{id}/float` · `expand` · `pip` · `rotate` · `close` | where the video is shown |")
        appendLine("| `POST /api/players/{id}/move` `{\"x\":0,\"y\":200}` · `…/resize` `{\"width\":600}` | a floating window's place and size |")
        appendLine("| `DELETE /api/players/{id}` · `DELETE /api/players` | close one player · every player |")
        appendLine("| `GET /api/settings` · `PATCH /api/settings` `{\"theme\":\"dark\",\"multiple_players\":true,\"floating_windows\":true}` | settings and permissions |")
        appendLine("| `POST /api/tools/{name}` | any tool, arguments as the body; answers `{ok, result}` or `{ok:false, error:{code, message}}` |")
        appendLine()
        appendLine("## MCP")
        appendLine("`POST /mcp`: JSON-RPC 2.0 (streamable HTTP, JSON responses, no session): `initialize`, `tools/list`, `tools/call`, `ping`.")
        appendLine("MCP names spell the dot as `_` (`loopr_players`). Only this key's tools are listed.")
        appendLine()
        appendLine("## Tools")
        Tools.all.forEach { appendLine("- `${it.name}`${if (it.name in key.tools) "" else " (off for this key)"}: ${it.description}") }
        appendLine()
        appendLine("## What only a person can do")
        appendLine("Android's own permission screens: letting Loopr see videos, \"Display over other apps\" and notifications. `GET /api/settings` says which are granted.")
    }
}

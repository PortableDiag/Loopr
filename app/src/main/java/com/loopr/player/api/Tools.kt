package com.loopr.player.api

/**
 * Every tool the API offers, one per thing the app can do.
 *
 * Each tool is its own switch on a key, and every switch starts **off**: a new key can do nothing
 * until the person who made it picks what it may do (the DialX, TextX and NotifX rule). The REST
 * routes, `POST /api/tools/{name}` and MCP all end up in the same tool, so there is one gate to get
 * right rather than three.
 *
 * The names carry a `loopr.` prefix so they stay unambiguous once PhoneAPI lists them alongside
 * every other app's; MCP spells the dot as `_`, since its tool names allow neither dots nor spaces
 * in every client.
 */
class Tool(
    val name: String,
    /** What the switch says in the key's tool list. */
    val label: String,
    val description: String,
    /** JSON Schema of the arguments, as text so this file needs no JSON library. */
    val schema: String
) {
    val mcpName: String get() = name.replace('.', '_')
}

object Tools {

    private const val PLAYER_ID =
        """"player_id":{"type":"integer","description":"From loopr.players. May be left out while exactly one player is open."}"""

    val all: List<Tool> = listOf(
        Tool(
            "loopr.library", "See the video library",
            "List or search the videos on the phone, newest first, as the library grid shows them.",
            """{"type":"object","properties":{
                "q":{"type":"string","description":"Words that must appear in the file name."},
                "folder":{"type":"string","description":"Only this folder (a name from loopr.folders)."},
                "limit":{"type":"integer","minimum":1,"maximum":500,"default":50},
                "offset":{"type":"integer","minimum":0,"default":0}}}"""
        ),
        Tool(
            "loopr.folders", "See the folders",
            "The folders that hold videos, with how many each holds.",
            """{"type":"object","properties":{}}"""
        ),
        Tool(
            "loopr.thumbnail", "See thumbnails",
            "A library video's thumbnail as a base64 JPEG.",
            """{"type":"object","required":["video_id"],"properties":{"video_id":{"type":"integer"}}}"""
        ),
        Tool(
            "loopr.players", "See what is playing",
            "Every open player, full screen or floating: what it is playing, where, and how it is set (speed, mute, resize, repeat, shuffle, A-B loop, zoom).",
            """{"type":"object","properties":{}}"""
        ),
        Tool(
            "loopr.queue", "See a player's queue",
            "The videos a player will step through with previous and next.",
            """{"type":"object","properties":{$PLAYER_ID,
                "offset":{"type":"integer","minimum":0,"default":0},
                "limit":{"type":"integer","minimum":1,"maximum":500,"default":100}}}"""
        ),
        Tool(
            "loopr.open", "Open videos",
            "Open a library video full screen or in a floating window. The queue is the whole library (as tapping it in the grid does) or just its folder.",
            """{"type":"object","properties":{
                "video_id":{"type":"integer","description":"From loopr.library."},
                "path":{"type":"string","description":"A file path the library knows, instead of video_id."},
                "queue":{"type":"string","enum":["library","folder","single"],"default":"library"},
                "window":{"type":"string","enum":["full","floating"],"default":"full"},
                "position_ms":{"type":"integer","minimum":0,"default":0},
                "paused":{"type":"boolean","default":false}}}"""
        ),
        Tool(
            "loopr.playback", "Play, pause, seek and skip",
            "Play, pause, seek, rewind or skip forward, or move through the queue.",
            """{"type":"object","required":["action"],"properties":{$PLAYER_ID,
                "action":{"type":"string","enum":["play","pause","toggle","seek","seek_by","next","previous","jump"]},
                "position_ms":{"type":"integer","minimum":0,"description":"For seek."},
                "delta_ms":{"type":"integer","description":"For seek_by; negative rewinds. The buttons use 10000."},
                "index":{"type":"integer","minimum":0,"description":"For jump: a queue position from loopr.queue."}}}"""
        ),
        Tool(
            "loopr.ab_loop", "Set the A-B loop",
            "Loop part of the current video: set both points, set A or B where it is now (as the chips do), or clear the loop. The loop belongs to the video and ends when the player moves to another.",
            """{"type":"object","required":["action"],"properties":{$PLAYER_ID,
                "action":{"type":"string","enum":["set","set_a","set_b","clear"]},
                "a_ms":{"type":"integer","minimum":0,"description":"For set."},
                "b_ms":{"type":"integer","minimum":1,"description":"For set; after a_ms."}}}"""
        ),
        Tool(
            "loopr.options", "Change speed, sound and picture",
            "Change how a player plays: speed, mute, resize mode, repeat, shuffle, zoom. Give only what should change. Repeat and shuffle are remembered for the next video opened, as the chips do.",
            """{"type":"object","properties":{$PLAYER_ID,
                "speed":{"type":"number","enum":[0.25,0.5,0.75,1,1.25,1.5,2]},
                "muted":{"type":"boolean"},
                "resize":{"type":"string","enum":["fit","crop","stretch"]},
                "repeat":{"type":"string","enum":["off","one","all"]},
                "shuffle":{"type":"boolean"},
                "zoom":{"type":"number","minimum":1,"maximum":5}}}"""
        ),
        Tool(
            "loopr.window", "Float, move and close players",
            "Move a video between full screen, a floating window and picture-in-picture, rotate full screen, move or resize a floating window, or close players.",
            """{"type":"object","required":["action"],"properties":{$PLAYER_ID,
                "action":{"type":"string","enum":["float","expand","pip","rotate","move","resize","close","close_all"]},
                "x":{"type":"integer","description":"For move, in pixels from the top left."},
                "y":{"type":"integer"},
                "width":{"type":"integer","description":"For resize, in pixels; the height follows the video."}}}"""
        ),
        Tool(
            "loopr.settings", "See settings",
            "The app's settings (theme, multiple players, floating windows) and the Android permissions Loopr holds.",
            """{"type":"object","properties":{}}"""
        ),
        Tool(
            "loopr.change_settings", "Change settings",
            "Change the theme, multiple players or floating windows. Give only what should change.",
            """{"type":"object","properties":{
                "theme":{"type":"string","enum":["system","light","dark"]},
                "multiple_players":{"type":"boolean"},
                "floating_windows":{"type":"boolean"}}}"""
        )
    )

    private val byName = all.associateBy { it.name }
    private val byMcpName = all.associateBy { it.mcpName }

    fun find(name: String): Tool? = byName[name] ?: byMcpName[name]
}

/**
 * A tool failing in a way the caller should hear about: a [code] a program can act on and a
 * message that says why, and what is left to try.
 */
class ToolError(val code: String, message: String) : Exception(message) {
    companion object {
        const val BAD_ARGS = "bad_args"
        const val NOT_FOUND = "not_found"
        /** The app could do it, but not now: a permission, a limit, the wrong kind of window. */
        const val REFUSED = "refused"
        const val NOT_ALLOWED = "not_allowed"
        const val UNKNOWN_TOOL = "unknown_tool"
        const val RATE_LIMITED = "rate_limited"
    }
}

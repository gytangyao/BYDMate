package com.bydmate.app.lyrics

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire format between BYDMate and a lyric display: JSON objects, one per line, over a plain TCP
 * connection BYDMate listens for.
 *
 * The shape is what keeps the display in step without a round trip per syllable. A track change
 * sends the metadata and, once the lookup lands, the **whole timeline** — after that the display is
 * offline-capable: it runs on its own clock and BYDMate only re-anchors position a few times a second
 * and answers a ping so both sides agree how the clocks differ. Line switches therefore cost the
 * display one frame of drawing, not one network hop, and a stalled WiFi does not stop the singing, it
 * only lets the two clocks drift until the next anchor corrects them.
 *
 * Kept free of Android so every frame is unit-tested and the display can be built against the same
 * contract.
 */
object DisplayProtocol {

    const val VERSION = 1

    /** mDNS service type a display browses for; the instance name is the head unit's model. */
    const val SERVICE_TYPE = "_bydmate-lyric._tcp"

    /** Single port for the whole feature; a display can also be pointed at it by hand. */
    const val DEFAULT_PORT = 48280

    /** Line-level state of a track, so a display can say what it is waiting for. */
    const val LYRICS_PENDING = "pending"
    const val LYRICS_READY = "ready"
    const val LYRICS_NONE = "none"

    fun hello(appName: String, appVersion: String): String = JSONObject()
        .put("t", "hello").put("proto", VERSION).put("app", appName).put("ver", appVersion)
        .toString()

    /**
     * The track now playing. [lyricState] is [LYRICS_PENDING] while the catalogs are asked, then
     * [LYRICS_READY] or [LYRICS_NONE]; a display keeps the previous song's words on screen through a
     * pending change only when [carryOver] tells it the same player is still the owner.
     *
     * The cover rides along rather than getting its own frame: this one is sent once per song and
     * again to a display that connects mid-track, which is exactly the audience a picture needs.
     */
    fun track(play: NowPlaying, lyricState: String): String = JSONObject()
        .put("t", "track")
        .put("key", play.key)
        .put("title", play.title)
        .put("artist", play.artist)
        .put("album", play.album ?: JSONObject.NULL)
        .put("pkg", play.packageName)
        .put("durMs", play.durationMs ?: JSONObject.NULL)
        .put("playing", play.playing)
        .put("positionKnown", play.positionKnown)
        .put("lyrics", lyricState)
        .put("cover", play.coverBase64 ?: JSONObject.NULL)
        .toString()

    /**
     * A track's full timeline. [lines] comes from [LyricTimeline.encodeLines]: per line
     * `[startMs, durationMs, text, translation?, words?]`.
     */
    fun lyrics(key: String, hit: LyricHit): String = JSONObject()
        .put("t", "lyrics")
        .put("key", key)
        .put("src", hit.sourceId)
        .put("srcLabel", hit.label)
        .put("words", hit.timeline.hasWordTiming)
        .put("lines", JSONArray(hit.timeline.encodeLines().map { row -> JSONArray(row) }))
        .toString()

    /** No catalog has this track; the display stops waiting and says so. */
    fun lyricsMissing(key: String): String = JSONObject()
        .put("t", "lyrics").put("key", key).put("src", JSONObject.NULL).put("lines", JSONArray()).toString()

    /**
     * Where the song is. `at` is the head unit's `elapsedRealtime()` at [positionMs], so the display
     * can convert it to its own clock with the skew measured by [ping]/[pong] and keep counting
     * between anchors.
     */
    fun anchor(play: NowPlaying, atUptimeMs: Long = play.atUptimeMs): String = JSONObject()
        .put("t", "anchor")
        .put("key", play.key)
        .put("pos", play.positionMs)
        .put("at", atUptimeMs)
        .put("playing", play.playing)
        .put("speed", play.speed.toDouble())
        .toString()

    /** State went away: the switch is off, the car stopped playing, nothing has a session. */
    fun idle(reason: String): String = JSONObject().put("t", "idle").put("reason", reason).toString()

    fun pong(clientToken: Long, serverUptimeMs: Long): String = JSONObject()
        .put("t", "pong").put("c", clientToken).put("s", serverUptimeMs).toString()

    /** True when a frame from a client is the clock probe; its `c` is echoed in [pong]. */
    fun pingToken(line: String): Long? = frameOf(line)?.takeIf { it.optString("t") == "ping" }?.optLong("c")

    /** A client's self-reported name (`{"t":"hello","name":"小度X6"}`), for the status line. */
    fun clientName(line: String): String? = frameOf(line)
        ?.takeIf { it.optString("t") == "hello" }
        ?.optString("name")?.takeIf { it.isNotBlank() }

    private fun frameOf(line: String): JSONObject? = runCatching { JSONObject(line) }.getOrNull()

    /**
     * Whether [frame] is the periodic position anchor, which replaces any queued one. Read by parsing
     * rather than by prefix: `JSONObject` iterates its keys by hash, so the field written first is not
     * the field that lands first in the string.
     */
    fun isAnchor(frame: String): Boolean = frameOf(frame)?.optString("t") == "anchor"
}

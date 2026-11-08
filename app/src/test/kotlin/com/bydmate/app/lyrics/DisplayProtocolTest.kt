package com.bydmate.app.lyrics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract the X6 display is built against. Every frame is checked by reading it back the way a
 * display would: one JSON object per line, and nothing a renderer has to guess at.
 */
class DisplayProtocolTest {

    private val play = NowPlaying(
        packageName = "com.tencent.qqmusic",
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        mediaId = "001234",
        durationMs = 254_000L,
        positionMs = 12_345L,
        positionKnown = true,
        playing = true,
        speed = 1f,
        atUptimeMs = 9_000_000L,
    )

    private val hit = LyricHit(
        timeline = LyricTimeline.parse(original = "[00:10.00]第一句\n[00:20.00]第二句", translated = "[00:10]первая"),
        sourceId = "netease",
        label = "网易云音乐",
        songRef = "netease:181",
    )

    @Test fun `hello names the app and the protocol the display must match`() {
        val frame = JSONObject(DisplayProtocol.hello("BYDMate", "4.0.11"))
        assertEquals("hello", frame.getString("t"))
        assertEquals(DisplayProtocol.VERSION, frame.getInt("proto"))
        assertEquals("BYDMate", frame.getString("app"))
        assertEquals("4.0.11", frame.getString("ver"))
    }

    @Test fun `a track frame is one line and carries what a header needs`() {
        val frame = JSONObject(DisplayProtocol.track(play, DisplayProtocol.LYRICS_PENDING))
        assertEquals("track", frame.getString("t"))
        assertEquals(play.key, frame.getString("key"))
        assertEquals("晴天", frame.getString("title"))
        assertEquals("叶惠美", frame.getString("album"))
        assertEquals(254_000L, frame.getLong("durMs"))
        assertTrue(frame.getBoolean("playing"))
        assertTrue(frame.getBoolean("positionKnown"))
        assertEquals("pending", frame.getString("lyrics"))
        assertFalse(DisplayProtocol.track(play, "ready").contains("\n"))
    }

    @Test fun `a session with no album or duration sends nulls not absent keys`() {
        val unknown = play.copy(album = null, durationMs = null, positionKnown = false)
        val frame = JSONObject(DisplayProtocol.track(unknown, DisplayProtocol.LYRICS_NONE))
        assertTrue(frame.isNull("album"))
        assertTrue(frame.isNull("durMs"))
        assertFalse(frame.getBoolean("positionKnown"))
    }

    // A cover is a track's, not the connection's: it rides the one frame per song so a display that
    // connects mid-track is handed it by `need`, and a player with no artwork is an explicit null.
    @Test fun `the cover rides the track frame it belongs to`() {
        val painted = play.copy(coverBase64 = "/9j/4AAQSkZJRg")
        assertEquals("/9j/4AAQSkZJRg", JSONObject(DisplayProtocol.track(painted, "ready")).getString("cover"))
        assertTrue(JSONObject(DisplayProtocol.track(play, "ready")).isNull("cover"))
    }

    @Test fun `the whole timeline travels in one frame`() {
        val frame = JSONObject(DisplayProtocol.lyrics(play.key, hit))
        assertEquals("lyrics", frame.getString("t"))
        assertEquals("netease", frame.getString("src"))
        assertEquals("网易云音乐", frame.getString("srcLabel"))
        assertFalse(frame.getBoolean("words"))
        val lines = frame.getJSONArray("lines")
        assertEquals(2, lines.length())
        val first = lines.getJSONArray(0)
        assertEquals(10_000L, first.getLong(0))
        assertEquals("第一句", first.getString(2))
        assertEquals("первая", first.getString(3))
    }

    @Test fun `word rows are offsets from their own line`() {
        val timed = LyricHit(
            timeline = LyricTimeline.parse(wordByWord = "[10000,4000](10000,1000,0)晴(11000,3000,1)天"),
            sourceId = "netease", label = "网易云音乐", songRef = "netease:181",
        )
        val lines = JSONObject(DisplayProtocol.lyrics(play.key, timed)).getJSONArray("lines")
        assertTrue(lines.optJSONObject(0) != null || lines.getJSONArray(0).length() > 0)
        val row = lines.getJSONArray(0)
        assertEquals(10_000L, row.getLong(0))
        assertTrue(JSONObject(DisplayProtocol.lyrics(play.key, timed)).getBoolean("words"))
        val words = row.getJSONArray(4)
        assertEquals(0L, words.getJSONArray(0).getLong(0))
        assertEquals(1_000L, words.getJSONArray(1).getLong(0))
        assertEquals("天", words.getJSONArray(1).getString(2))
    }

    @Test fun `a missing lyric is a frame with no lines`() {
        val frame = JSONObject(DisplayProtocol.lyricsMissing(play.key))
        assertEquals("lyrics", frame.getString("t"))
        assertTrue(frame.isNull("src"))
        assertEquals(0, frame.getJSONArray("lines").length())
    }

    @Test fun `an anchor says where the song was at the head unit clock`() {
        val frame = JSONObject(DisplayProtocol.anchor(play))
        assertEquals("anchor", frame.getString("t"))
        assertEquals(play.key, frame.getString("key"))
        assertEquals(12_345L, frame.getLong("pos"))
        assertEquals(9_000_000L, frame.getLong("at"))
        assertTrue(frame.getBoolean("playing"))
        assertEquals(1.0, frame.getDouble("speed"), 0.0001)
    }

    @Test fun `an anchor is a frame that replaces a queued one`() {
        assertTrue(DisplayProtocol.isAnchor(DisplayProtocol.anchor(play)))
        assertFalse(DisplayProtocol.isAnchor(DisplayProtocol.track(play, "ready")))
        assertFalse(DisplayProtocol.isAnchor(DisplayProtocol.lyrics(play.key, hit)))
        assertFalse(DisplayProtocol.isAnchor(DisplayProtocol.idle("no session")))
        assertFalse(DisplayProtocol.isAnchor("{not json"))
    }

    @Test fun `a look frame carries the cabin's mode and backlight`() {
        val frame = JSONObject(DisplayProtocol.look(DisplayProtocol.THEME_DARK, 51, auto = false))
        assertEquals("look", frame.getString("t"))
        assertEquals("dark", frame.getString("theme"))
        assertEquals(51, frame.getInt("brightness"))
        assertFalse(frame.getBoolean("auto"))
        assertFalse(frame.toString().contains("\n"))
    }

    @Test fun `daylight and an unreadable backlight are both said explicitly`() {
        val frame = JSONObject(DisplayProtocol.look(DisplayProtocol.THEME_LIGHT, null, auto = true))
        assertEquals("light", frame.getString("theme"))
        assertTrue(frame.isNull("brightness"))
        assertTrue(frame.getBoolean("auto"))
    }

    @Test fun `a look replaces a queued one, and only its own kind`() {
        assertTrue(DisplayProtocol.isLook(DisplayProtocol.look("dark", 10, auto = false)))
        assertFalse(DisplayProtocol.isLook(DisplayProtocol.anchor(play)))
        assertFalse(DisplayProtocol.isLook(DisplayProtocol.track(play, "ready")))
        assertFalse(DisplayProtocol.isLook("{not json"))
        assertFalse(DisplayProtocol.isAnchor(DisplayProtocol.look("dark", 10, auto = false)))
    }

    @Test fun `a ping reads its token and a hello its name`() {
        assertEquals(42L, DisplayProtocol.pingToken("""{"t":"ping","c":42}"""))
        assertNull(DisplayProtocol.pingToken("""{"t":"pong","c":42}"""))
        assertNull(DisplayProtocol.pingToken("garbage"))
        assertEquals("小度X6", DisplayProtocol.clientName("""{"t":"hello","name":"小度X6"}"""))
        assertNull(DisplayProtocol.clientName("""{"t":"hello","name":"  "}"""))
    }

    @Test fun `a pong answers with the client token and the server clock`() {
        val frame = JSONObject(DisplayProtocol.pong(7L, 1234L))
        assertEquals("pong", frame.getString("t"))
        assertEquals(7L, frame.getLong("c"))
        assertEquals(1234L, frame.getLong("s"))
    }

    @Test fun `an idle frame explains itself`() {
        val frame = JSONObject(DisplayProtocol.idle("switch off"))
        assertEquals("idle", frame.getString("t"))
        assertEquals("switch off", frame.getString("reason"))
    }

    @Test fun `the service name and port are what a display is configured with`() {
        assertEquals("_bydmate-lyric._tcp", DisplayProtocol.SERVICE_TYPE)
        assertEquals(48_280, DisplayProtocol.DEFAULT_PORT)
        assertEquals(1, DisplayProtocol.VERSION)
    }

    // A display decodes `lines` positionally, so the first three slots must mean the same thing in a
    // plain and a word-timed payload; a plain row simply stops before the slots it has no data for.
    @Test fun `plain and word-timed rows share the leading slots`() {
        val plain = JSONArray(LyricTimeline.parse(original = "[00:01.00]a\n[00:02.00]b").encodeLines().first())
        val timed = JSONArray(LyricTimeline.parse(wordByWord = "[0,1000](0,1000,0)a\n[1000,1000](1000,1000,0)b").encodeLines().first())
        assertEquals(1_000L, plain.getLong(0))
        assertEquals(1_000L, plain.getLong(1))
        assertEquals("a", plain.getString(2))
        assertEquals(3, plain.length())
        assertEquals(0L, timed.getLong(0))
        assertEquals(1_000L, timed.getLong(1))
        assertEquals("a", timed.getString(2))
        assertTrue(timed.isNull(3))
        val words = timed.getJSONArray(4)
        assertEquals(0L, words.getJSONArray(0).getLong(0))
        assertEquals("a", words.getJSONArray(0).getString(2))
    }
}

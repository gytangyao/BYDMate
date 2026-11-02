package com.bydmate.app.lyrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cadence the display is fed at, checked frame by frame. These are the latency rules of the whole
 * feature: one frame per song change, position that is fresh without being per-poll, a screen that
 * survives a session blinking out, and no catalog search for a track already answered.
 */
class DisplaySchedulerTest {

    private val hit = LyricHit(
        timeline = LyricTimeline.parse("[00:10.00]a\n[00:20.00]b"),
        sourceId = "netease", label = "网易云音乐", songRef = "netease:1",
    )

    @Test fun `a new song is one track frame and its first anchor`() {
        val scheduler = DisplayScheduler()
        val frames = scheduler.observe(qingTian(), 1_000L)
        assertEquals(listOf("track", "anchor"), frames.map { JSONObject(it).getString("t") })
        assertEquals(DisplayProtocol.LYRICS_PENDING, JSONObject(frames.first()).getString("lyrics"))
        assertEquals("pending", scheduler.lyricState)
    }

    @Test fun `the same song re-anchors on the timer, not every poll`() {
        val scheduler = DisplayScheduler()
        val song = qingTian()
        assertEquals(2, scheduler.observe(song, 1_000L).size)
        assertEquals(0, scheduler.observe(song.copy(positionMs = 1_500L), 1_500L).size)
        assertEquals(0, scheduler.observe(song.copy(positionMs = 2_400L), 2_400L).size)
        assertEquals(listOf("anchor"), scheduler.observe(song.copy(positionMs = 3_000L), 3_000L)
            .map { JSONObject(it).getString("t") })
    }

    // A player that publishes the title before its picture would otherwise lose the picture for the
    // whole song: the song frame already went out with no cover, and the key never changes again.
    @Test fun `a cover that arrives late brings the song frame back once`() {
        val scheduler = DisplayScheduler()
        val song = qingTian()
        assertEquals(listOf("track", "anchor"), scheduler.observe(song, 1_000L)
            .map { JSONObject(it).getString("t") })
        val withCover = song.copy(coverBase64 = "/9j/4AAQ", positionMs = 1_500L)
        val landed = scheduler.observe(withCover, 1_500L)
        assertEquals(listOf("track"), landed.map { JSONObject(it).getString("t") })
        assertEquals("/9j/4AAQ", JSONObject(landed.first()).getString("cover"))
        assertEquals(0, scheduler.observe(withCover.copy(positionMs = 2_000L), 2_000L).size)
    }

    // The worse failure is not a cover that is missing but a cover that is the previous song's: the
    // metadata still carries that bitmap when the new title lands, and the right one a poll later. Same
    // key either way, so only a picture that differs from the one already sent can dislodge it.
    @Test fun `a cover replaced by the right one brings the song frame back`() {
        val scheduler = DisplayScheduler()
        val song = qingTian().copy(coverBase64 = "/9j/WRONG")
        assertEquals(2, scheduler.observe(song, 1_000L).size)
        val corrected = song.copy(coverBase64 = "/9j/RIGHT", positionMs = 1_500L)
        val landed = scheduler.observe(corrected, 1_500L)
        assertEquals(listOf("track"), landed.map { JSONObject(it).getString("t") })
        assertEquals("/9j/RIGHT", JSONObject(landed.first()).getString("cover"))
        assertEquals(0, scheduler.observe(corrected.copy(positionMs = 2_000L), 2_000L).size)
    }

    // The display counts position itself between anchors, so the instant it must learn about is the
    // one where counting stops.
    @Test fun `a pause or resume re-anchors at once`() {
        val scheduler = DisplayScheduler()
        val playing = qingTian()
        scheduler.observe(playing, 1_000L)
        val paused = scheduler.observe(playing.copy(playing = false), 1_500L)
        assertEquals(1, paused.size)
        assertFalse(JSONObject(paused.first()).getBoolean("playing"))
        val resumed = scheduler.observe(playing.copy(positionMs = 9_000L), 1_800L)
        assertEquals(1, resumed.size)
        assertEquals(9_000L, JSONObject(resumed.first()).getLong("pos"))
    }

    @Test fun `a source that publishes no position never anchors`() {
        val scheduler = DisplayScheduler()
        val frames = scheduler.observe(qingTian().copy(positionKnown = false), 1_000L)
        assertEquals(listOf("track"), frames.map { JSONObject(it).getString("t") })
    }

    @Test fun `a song on screen is looked up once`() {
        val scheduler = DisplayScheduler()
        scheduler.observe(qingTian(), 1_000L)
        val request = scheduler.lookupFor()
        assertNotNull(request)
        assertEquals(qingTian().key, request?.key)
        assertEquals("晴天", request?.query?.title)
        assertEquals("com.tencent.qqmusic", request?.packageName)
        scheduler.onLyrics(request!!.key, hit)
        assertNull(scheduler.lookupFor())
    }

    @Test fun `the timeline lands only for the song still on screen`() {
        val scheduler = DisplayScheduler()
        val first = qingTian()
        scheduler.observe(first, 1_000L)
        scheduler.observe(qingTian(title = "稻香"), 2_000L)
        // A late answer for the song the driver skipped past is remembered, not shown.
        assertEquals(0, scheduler.onLyrics(first.key, hit).size)
        assertNotNull(scheduler.lookupFor())
        val forNow = scheduler.onLyrics(qingTian(title = "稻香").key, hit)
        assertEquals(listOf("lyrics"), forNow.map { JSONObject(it).getString("t") })
        assertEquals("ready", scheduler.lyricState)
    }

    @Test fun `a track already answered is replayed with its song frame`() {
        val scheduler = DisplayScheduler()
        val first = qingTian()
        scheduler.observe(first, 1_000L)
        scheduler.onLyrics(first.key, hit)
        val other = qingTian(title = "稻香")
        scheduler.observe(other, 2_000L)
        scheduler.onLyrics(other.key, null)
        val back = scheduler.observe(first, 3_000L)
        assertEquals(listOf("track", "lyrics", "anchor"), back.map { JSONObject(it).getString("t") })
        assertEquals("ready", JSONObject(back.first()).getString("lyrics"))
        assertEquals(2, scheduler.lines)
        assertEquals("netease", scheduler.lyricSource)
    }

    @Test fun `no catalog has it, and the display stops waiting`() {
        val scheduler = DisplayScheduler()
        val song = qingTian()
        scheduler.observe(song, 1_000L)
        val frames = scheduler.onLyrics(song.key, null)
        assertEquals("none", scheduler.lyricState)
        assertTrue(JSONObject(frames.first()).isNull("src"))
        assertEquals(0, scheduler.lines)
    }

    @Test fun `a session that blinks out keeps the words on screen`() {
        val scheduler = DisplayScheduler()
        scheduler.observe(qingTian(), 1_000L)
        assertEquals(0, scheduler.observe(null, 3_000L).size)
        assertEquals(0, scheduler.observe(null, 5_500L).size)
        val idle = scheduler.observe(null, 6_100L)
        assertEquals(listOf("idle"), idle.map { JSONObject(it).getString("t") })
        assertEquals(0, scheduler.observe(null, 7_000L).size)
    }

    @Test fun `a resume inside the grace period continues without a new track frame`() {
        val scheduler = DisplayScheduler()
        val song = qingTian()
        assertEquals(2, scheduler.observe(song, 1_000L).size)
        assertEquals(0, scheduler.observe(null, 2_000L).size)
        assertEquals(listOf("anchor"), scheduler.observe(song.copy(positionMs = 3_000L), 2_500L)
            .map { JSONObject(it).getString("t") })
    }

    @Test fun `a snapshot is the song then its words then where it is`() {
        val scheduler = DisplayScheduler()
        val song = qingTian()
        scheduler.observe(song, 1_000L)
        scheduler.onLyrics(song.key, hit)
        assertEquals(listOf("track", "lyrics", "anchor"),
            scheduler.snapshot().map { JSONObject(it).getString("t") })
    }

    @Test fun `a snapshot with no words pending sends no lyrics frame`() {
        val scheduler = DisplayScheduler()
        scheduler.observe(qingTian(), 1_000L)
        assertEquals(listOf("track", "anchor"), scheduler.snapshot().map { JSONObject(it).getString("t") })
    }

    @Test fun `a snapshot with no song clears the display`() {
        assertEquals(listOf("idle"), DisplayScheduler().snapshot().map { JSONObject(it).getString("t") })
    }

    @Test fun `the idle frame carries its reason`() {
        val frame = JSONObject(DisplayProtocol.idle("no session"))
        assertEquals("no session", frame.getString("reason"))
    }

    private fun qingTian(title: String = "晴天") = NowPlaying(
        packageName = "com.tencent.qqmusic",
        title = title,
        artist = "周杰伦",
        album = "叶惠美",
        mediaId = "001234",
        durationMs = 254_000L,
        positionMs = 1_000L,
        positionKnown = true,
        playing = true,
        speed = 1f,
        atUptimeMs = 1_000L,
    )
}

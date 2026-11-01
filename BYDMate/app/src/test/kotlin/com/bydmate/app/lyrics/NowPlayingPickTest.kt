package com.bydmate.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingPickTest {

    private fun session(
        pkg: String,
        state: Int?,
        title: String?,
        artist: String? = "Artist",
        positionMs: Long? = 1_000L,
        durationMs: Long? = 200_000L,
        speed: Float = 1f,
    ) = SessionInfo(pkg, state, title, artist, "Album", "media-1", positionMs, durationMs, speed)

    @Test fun `a playing session owns the screen`() {
        val picked = NowPlayingPick.pick(
            listOf(session("com.spotify.music", STATE_PAUSED, "Paused song"),
                session("com.tencent.qqmusic", STATE_PLAYING, "Playing song")),
            nowUptimeMs = 5_000L,
        )
        assertEquals("com.tencent.qqmusic", picked?.packageName)
        assertEquals("Playing song", picked?.title)
        assertTrue(picked!!.playing)
        assertEquals(5_000L, picked.atUptimeMs)
    }

    // The stock players and a Bluetooth stream are the normal case here, unlike the cluster card.
    @Test fun `bluetooth avrcp metadata is a source`() {
        val picked = NowPlayingPick.pick(listOf(session("com.android.bluetooth", STATE_PLAYING, "Phone song")))
        assertEquals("com.android.bluetooth", picked?.packageName)
    }

    @Test fun `a call or the assistant never takes the screen`() {
        val noise = listOf(
            session("com.android.server.telecom", STATE_PLAYING, "Incoming call"),
            session("com.byd.autovoice", STATE_PLAYING, "我在"),
            session("com.baidu.duershow", STATE_PLAYING, "小度小度"),
        )
        assertNull(NowPlayingPick.pick(noise))
        val music = session("com.netease.cloudmusic", STATE_PLAYING, "晴天")
        assertEquals("晴天", NowPlayingPick.pick(noise + music)?.title)
    }

    @Test fun `an untitled session is not a song`() {
        assertNull(NowPlayingPick.pick(listOf(session("com.spotify.music", STATE_PLAYING, null))))
        assertNull(NowPlayingPick.pick(listOf(session("com.spotify.music", STATE_PLAYING, "   "))))
        assertNull(NowPlayingPick.pick(emptyList()))
    }

    @Test fun `a player that keeps its title while paused keeps the screen`() {
        val picked = NowPlayingPick.pick(
            listOf(session("com.spotify.music", STATE_PAUSED, "Still this"),
                session("com.iqyio.kuwo", STATE_NONE, "Never started")),
            nowUptimeMs = 1_000L,
        )
        assertEquals("com.spotify.music", picked?.packageName)
        assertFalse(picked!!.playing)
    }

    // Two paused sessions: the one that had the screen last keeps it, so a pause does not flip
    // between players every poll.
    @Test fun `the last owner wins a tie of non-playing sessions`() {
        val sessions = listOf(
            session("com.spotify.music", STATE_PAUSED, "First in list"),
            session("com.tencent.qqmusic", STATE_PAUSED, "The owner"),
        )
        assertEquals("The owner", NowPlayingPick.pick(sessions, lastOwner = "com.tencent.qqmusic")?.title)
        assertEquals("First in list", NowPlayingPick.pick(sessions, lastOwner = null)?.title)
    }

    @Test fun `buffering outranks a pause from another player`() {
        val picked = NowPlayingPick.pick(
            listOf(session("com.spotify.music", STATE_PAUSED, "Pause"),
                session("com.netease.cloudmusic", STATE_BUFFERING, "Loading")),
            lastOwner = "com.android.bluetooth",
        )
        assertEquals("com.netease.cloudmusic", picked?.packageName)
    }

    @Test fun `a source without a position is known to have none`() {
        val picked = NowPlayingPick.pick(listOf(session("com.android.bluetooth", STATE_PLAYING, "Song", positionMs = null)))
        assertFalse(picked!!.positionKnown)
        assertEquals(0L, picked.positionMs)
    }

    @Test fun `a zero or negative duration reads as unknown`() {
        assertNull(NowPlayingPick.pick(listOf(session("radio.app", STATE_PLAYING, "Live", durationMs = 0L)))?.durationMs)
        assertNull(NowPlayingPick.pick(listOf(session("radio.app", STATE_PLAYING, "Live", durationMs = -1L)))?.durationMs)
    }

    @Test fun `the search query carries the session's own identity`() {
        val play = NowPlayingPick.pick(listOf(session("com.tencent.qqmusic", STATE_PLAYING, "晴天", "周杰伦")))!!
        assertEquals(TrackMatch.trackKey("com.tencent.qqmusic", "晴天", "周杰伦", 200_000L), play.key)
        assertEquals("晴天", play.searchQuery.title)
        assertEquals("media-1", play.searchQuery.mediaId)
        assertEquals(200_000L, play.searchQuery.durationMs)
    }

    @Test fun `a session that reports no speed still plays at one`() {
        val play = NowPlayingPick.pick(listOf(session("com.spotify.music", STATE_PLAYING, "Song", speed = 0f)))!!
        assertEquals(1f, play.speed)
    }

    private companion object {
        const val STATE_NONE = 0
        const val STATE_PAUSED = 2
        const val STATE_PLAYING = 3
        const val STATE_BUFFERING = 6
    }
}

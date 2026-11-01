package com.bydmate.app.lyrics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one backpressure rule the display link runs on: a stale position frame is worse than none, and
 * everything else has to survive a slow reader.
 */
class DisplayOutboxTest {

    private val outbox = DisplayOutbox()

    @Test fun `frames leave in the order they arrived`() {
        outbox.push("one")
        outbox.push("two")
        assertEquals("one", outbox.poll(0))
        assertEquals("two", outbox.poll(0))
        assertEquals(0, outbox.size)
    }

    @Test fun `an anchor replaces every anchor still waiting`() {
        outbox.push(anchorAt(1_000L))
        outbox.push("track")
        outbox.push(anchorAt(2_000L))
        assertEquals(2, outbox.size)
        assertEquals("track", outbox.poll(0))
        assertEquals(2_000L, JSONObject(outbox.poll(0).orEmpty()).getLong("pos"))
    }

    @Test fun `a song and its timeline are never dropped for a newer anchor`() {
        outbox.push("track")
        outbox.push("lyrics")
        outbox.push(anchorAt(9_000L))
        assertEquals(listOf("track", "lyrics", anchorAt(9_000L)),
            listOf(outbox.poll(0), outbox.poll(0), outbox.poll(0)))
    }

    @Test fun `a display that stops reading is refused, not buffered without end`() {
        val tiny = DisplayOutbox(maxFrames = 2)
        assertTrue(tiny.push("a"))
        assertTrue(tiny.push("b"))
        assertFalse(tiny.push("c"))
        assertEquals(1L, tiny.dropped)
    }

    @Test fun `an empty queue answers a poll with nothing after its timeout`() {
        val started = System.currentTimeMillis()
        assertNull(outbox.poll(20))
        assertTrue(System.currentTimeMillis() - started >= 10)
    }

    private fun anchorAt(positionMs: Long) =
        DisplayProtocol.anchor(play().copy(positionMs = positionMs, atUptimeMs = positionMs))

    private fun play() = NowPlaying(
        packageName = "com.tencent.qqmusic", title = "晴天", artist = "周杰伦",
        album = null, mediaId = null, durationMs = 254_000L, positionMs = 5_000L,
        positionKnown = true, playing = true, speed = 1f, atUptimeMs = 1_000L,
    )
}

package com.bydmate.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatchTest {

    private val studioDuration = 254_000L

    @Test fun `case punctuation and spacing carry no weight`() {
        assertEquals("晴天", TrackMatch.normalize(" 晴 天 "))
        assertEquals("晴天live", TrackMatch.normalize("晴天 (Live)"))
        assertEquals("", TrackMatch.normalize("——· "))
        assertEquals("", TrackMatch.normalize(null))
    }

    @Test fun `a full title artist and duration match is accepted`() {
        val score = TrackMatch.score("晴天", "周杰伦", studioDuration, "晴天", "周杰伦", studioDuration)
        assertEquals(205, score)
        assertTrue(score >= TrackMatch.ACCEPT_SCORE)
    }

    @Test fun `a different artist is punished harder than a missing one`() {
        val wrong = TrackMatch.score("晴天", "周杰伦", studioDuration, "晴天", "蔡依林", studioDuration)
        val unnamed = TrackMatch.score("晴天", "周杰伦", studioDuration, "晴天", "", studioDuration)
        assertTrue(wrong < unnamed)
        assertTrue(unnamed >= TrackMatch.ACCEPT_SCORE)
    }

    @Test fun `a title that shares nothing cannot be rescued by artist and duration`() {
        // −80 for the title is the most a perfect artist and duration (+105) can partly repay, so an
        // unrelated title stays under the bar instead of being dropped before either is looked at.
        val unrelated = TrackMatch.score("晴天", "周杰伦", studioDuration, "雨下一整晚", "周杰伦", studioDuration)
        assertEquals(25, unrelated)
        assertFalse(unrelated >= TrackMatch.ACCEPT_SCORE)
        assertEquals(Int.MIN_VALUE, TrackMatch.score("", "周杰伦", studioDuration, "晴天", "周杰伦", studioDuration))
    }

    @Test fun `the duration bands are two, five and fifteen seconds`() {
        fun at(deltaMs: Long) = TrackMatch.score("晴天", "周杰伦", studioDuration, "晴天", "周杰伦", studioDuration + deltaMs)
        assertEquals(205, at(2_000))
        assertEquals(195, at(2_001))
        assertEquals(195, at(5_000))
        assertEquals(175, at(5_001))
        assertEquals(175, at(15_000))
        assertEquals(150, at(15_001))
    }

    @Test fun `duration disagreement eats the score`() {
        val tight = TrackMatch.score("晴天", "周杰伦", studioDuration, "晴天", "周杰伦", studioDuration + 4_000)
        val far = TrackMatch.score("晴天", "周杰伦", studioDuration, "晴天", "周杰伦", studioDuration + 40_000)
        assertTrue(tight > far)
        // Score alone still likes a 40 s-off candidate once title and artist agree: the gate that
        // keeps it out is isPlausible, which the next test covers.
        assertTrue(far >= TrackMatch.ACCEPT_SCORE)
    }

    // A sped-up or slowed edit keeps the title and the artist and only moves the duration: it must
    // never be the track a studio recording is cached under.
    @Test fun `a speed edit of the same title is rejected by duration`() {
        assertFalse(TrackMatch.isPlausible("晴天", "周杰伦", studioDuration, "晴天", "周杰伦", 200_000L))
        assertTrue(TrackMatch.isPlausible("晴天", "周杰伦", studioDuration, "晴天", "周杰伦", studioDuration + 10_000))
    }

    @Test fun `a live or backing upload of a studio title is not plausible`() {
        assertFalse(TrackMatch.isPlausible("晴天", "周杰伦", studioDuration, "晴天 (Live)", "周杰伦", studioDuration))
        assertFalse(TrackMatch.isPlausible("晴天", "周杰伦", studioDuration, "晴天 翻唱", "周杰伦", studioDuration))
        assertFalse(TrackMatch.isPlausible("晴天 (Live)", "周杰伦", studioDuration, "晴天", "周杰伦", studioDuration))
        assertTrue(TrackMatch.hasVersionNoise("晴天（伴奏）"))
        assertTrue(TrackMatch.hasVersionNoise("Sunny (Sped Up)"))
        assertFalse(TrackMatch.hasVersionNoise("晴天"))
    }

    // Full-width brackets and both kinds of dash appear in the sessions real players publish.
    @Test fun `a trailing artist the player glued to the title comes off`() {
        assertEquals("晴天", TrackMatch.titleFromSession("晴天 - 周杰伦", "周杰伦"))
        assertEquals("晴天", TrackMatch.titleFromSession("晴天－周杰伦", "周杰伦"))
        assertEquals("晴天 - 周杰伦", TrackMatch.titleFromSession("晴天 - 周杰伦", "蔡依林"))
        assertEquals("Live is life", TrackMatch.titleFromSession("Live is life", null))
    }

    @Test fun `plausibility needs the artist to agree when both name one`() {
        assertFalse(TrackMatch.isPlausible("晴天", "周杰伦", studioDuration, "晴天", "蔡依林", studioDuration))
        assertTrue(TrackMatch.isPlausible("晴天", "周杰伦", studioDuration, "晴天", "周杰伦/Jacky Cheung", studioDuration))
    }

    @Test fun `an unknown duration cannot veto a candidate`() {
        assertTrue(TrackMatch.isPlausible("Blue", "DJ", -1L, "Blue", "DJ", 180_000L))
        assertTrue(TrackMatch.isPlausible("Blue", "DJ", 180_000L, "Blue", "DJ", 0L))
    }

    @Test fun `an empty title matches nothing`() {
        assertFalse(TrackMatch.isPlausible("  ", "DJ", studioDuration, "晴天", "周杰伦", studioDuration))
        assertFalse(TrackMatch.isPlausible("晴天", "周杰伦", studioDuration, "", "周杰伦", studioDuration))
    }

    @Test fun `the key survives a poll of the same song and changes with it`() {
        val a = TrackMatch.trackKey("com.tencent.qqmusic", "晴天", "周杰伦", studioDuration)
        val b = TrackMatch.trackKey("com.tencent.qqmusic", "晴天 (Live)", "周杰伦", studioDuration)
        assertEquals(a, TrackMatch.trackKey("com.tencent.qqmusic", "晴天", "周杰伦", studioDuration + 900))
        assertNotEquals(a, b)
        // Seconds, not milliseconds: a player that reports 254_999 is still on the same track.
        assertTrue(a.endsWith("|254"))
    }
}

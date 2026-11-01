package com.bydmate.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTimelineTest {

    private val lrc = """
        [00:00.00]标题
        [00:10.20]第一句
        [00:14.00]第二句
        [01:00.00]最后一句
    """.trimIndent()

    @Test fun `plain lrc lines come out in song order`() {
        val timeline = LyricTimeline.parse(lrc)
        assertEquals(4, timeline.size)
        assertEquals(listOf(0L, 10_200L, 14_000L, 60_000L), timeline.lines.map { it.startMs })
        assertEquals("第一句", timeline.lines[1].text)
        assertFalse(timeline.hasWordTiming)
    }

    @Test fun `repeated time tags share one text`() {
        val timeline = LyricTimeline.parse("[00:01.00][00:02.00][00:03.00]chorus")
        assertEquals(listOf(1_000L, 2_000L, 3_000L), timeline.lines.map { it.startMs })
        assertTrue(timeline.lines.all { it.text == "chorus" })
    }

    @Test fun `offset tag shifts the whole file`() {
        val timeline = LyricTimeline.parse("[offset:-1500]\n[00:10.00]line")
        assertEquals(8_500L, timeline.lines.single().startMs)
    }

    @Test fun `a negative offset cannot push a line before the song`() {
        val timeline = LyricTimeline.parse("[offset:-30000]\n[00:10.00]line")
        assertEquals(0L, timeline.lines.single().startMs)
    }

    // Verified against the live endpoint 2026-11-01: the marker precedes the text it owns, and the
    // text between the line tag and the first marker is untimed padding.
    @Test fun `every word marker owns the text that follows it`() {
        val timeline = LyricTimeline.parse(wordByWord = "[0,4000](0,800,0)晴(900,1000,1)天(2000,2000,2)在下雨")
        val line = timeline.lines.single()
        assertEquals("晴天在下雨", line.text)
        assertEquals(listOf("晴", "天", "在下雨"), line.words.map { it.text })
        assertEquals(listOf(0L, 900L, 2000L), line.words.map { it.startMs })
        assertTrue(timeline.hasWordTiming)
    }

    @Test fun `untimed padding before the first marker is dropped`() {
        val timeline = LyricTimeline.parse(wordByWord = "[0,4000]    (500,3500,0)晴天")
        assertEquals("晴天", timeline.lines.single().text)
        assertEquals(500L, timeline.lines.single().words.single().startMs)
    }

    @Test fun `word timing wins over the plain payload it ships with`() {
        val timeline = LyricTimeline.parse(
            original = "[00:00.00]晴天在下雨",
            wordByWord = "[0,4000](0,4000,0)晴天在下雨",
        )
        assertEquals(1, timeline.size)
        assertEquals(1, timeline.lines.single().words.size)
    }

    @Test fun `a translation payload attaches to the line stamped closest to it`() {
        val timeline = LyricTimeline.parse(
            original = "[00:10.00]first\n[00:20.00]second",
            translated = "[00:10]первый\n[00:20]второй",
        )
        assertEquals("первый", timeline.lines[0].translation)
        assertEquals("второй", timeline.lines[1].translation)
    }

    @Test fun `a translation seconds away is not the same line`() {
        val timeline = LyricTimeline.parse(
            original = "[00:10.00]first\n[00:50.00]second",
            translated = "[00:30]далеко",
        )
        assertNull(timeline.lines.first().translation)
    }

    @Test fun `json credit lines are not lyrics`() {
        val timeline = LyricTimeline.parse("""
            {"credits":[{"id":1}]}
            [00:01.00]one
            [00:02.00]two
        """.trimIndent())
        assertEquals(listOf("one", "two"), timeline.lines.map { it.text })
    }

    @Test fun `lineIndexAt is the last line started and minus one during the intro`() {
        val timeline = LyricTimeline.parse("[00:10.00]one\n[00:20.00]two\n[00:30.00]three")
        assertEquals(-1, timeline.lineIndexAt(9_999L))
        assertEquals(0, timeline.lineIndexAt(10_000L))
        assertEquals(1, timeline.lineIndexAt(25_000L))
        assertEquals(2, timeline.lineIndexAt(3_600_000L))
    }

    @Test fun `a cue before the first line names it as next`() {
        val cue = LyricTimeline.parse("[00:10.00]one\n[00:20.00]two").cue(5_000L)
        assertNull(cue.current)
        assertEquals("one", cue.next?.text)
        assertEquals(5_000L, cue.upcomingInMs)
    }

    @Test fun `a plain line is measured to the next one`() {
        val cue = LyricTimeline.parse(lrc).cue(11_000L)
        assertEquals("第一句", cue.current?.text)
        // 800 ms into a line that stands for 3.8 s.
        assertTrue(cue.lineProgress > 0.2f && cue.lineProgress < 0.25f)
    }

    @Test fun `word progress walks the line`() {
        val timeline = LyricTimeline.parse(wordByWord = "[0,4000](0,1000,0)ab(1000,1000,1)cd")
        val cue = timeline.cue(1_500L)
        assertEquals(1, cue.wordIndex)
        assertEquals("cd", cue.wordText)
        assertEquals(0.5f, cue.wordProgress, 0.001f)
    }

    @Test fun `a long gap between timed lines is an intermission`() {
        val timeline = LyricTimeline.parse(wordByWord = "[0,3000](0,3000,0)первая\n[15000,3000](15000,3000,0)вторая")
        assertEquals(12_000L, timeline.cue(10_000L).intermissionMs)
        assertNull(timeline.cue(1_000L).intermissionMs)
    }

    @Test fun `one line is not a lyric`() {
        assertFalse(LyricTimeline.parse("[00:01.00]only").isUsable())
    }

    @Test fun `a provider placeholder is not a lyric`() {
        assertFalse(LyricTimeline.parse("[00:01.00]纯音乐，请欣赏\n[00:20.00]纯音乐，请欣赏").isUsable())
        assertFalse(LyricTimeline.parse("[00:01.00]Instrumental\n[00:20.00]Instrumental").isUsable())
        assertTrue(LyricTimeline.parse("[00:01.00]нон-стоп\n[00:20.00]текст").isUsable())
    }

    @Test fun `quality rewards word timing and a translation`() {
        val plain = LyricTimeline.parse(lrc).qualityScore()
        val timed = LyricTimeline.parse(wordByWord = "[0,1000](0,1000,0)a\n[1000,1000](1000,1000,0)b").qualityScore()
        assertTrue(timed > plain)
    }

    @Test fun `encoded lines put duration text translation and words in fixed slots`() {
        val timeline = LyricTimeline.parse(
            original = "[00:10.00]hi\n[00:40.00]there",
            translated = "[00:10]привет",
        )
        val first = timeline.encodeLines().first()
        assertEquals(10_000L, first[0])
        // Stands until the next line, not the 5 s default.
        assertEquals(30_000L, first[1])
        assertEquals("hi", first[2])
        assertEquals("привет", first[3])
        assertEquals(4, first.size)

        val words = LyricTimeline.parse(wordByWord = "[0,2000](0,1000,0)a(1000,1000,1)b")
            .encodeLines().single()
        // The line keeps its whole text in slot 2; the word list beside it is what the fill is drawn from.
        assertEquals("ab", words[2])
        assertNull(words[3])
        assertEquals(listOf(0L, 1000L, "a"), (words[4] as List<*>)[0])
        assertEquals(listOf(1000L, 1000L, "b"), (words[4] as List<*>)[1])
    }

    @Test fun `the last line of a truncated lyric holds instead of blinking out`() {
        val timeline = LyricTimeline.parse("[00:01.00]one\n[00:02.00]two")
        assertEquals(5_000L, timeline.lines.last().visibleDurationMs(null))
    }

    @Test fun `empty payloads parse to nothing`() {
        assertTrue(LyricTimeline.parse(null).empty)
        assertTrue(LyricTimeline.parse("").empty)
        assertTrue(LyricTimeline.parse("no time tags at all").empty)
    }
}

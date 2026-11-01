package com.bydmate.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A remembered timeline, keyed by the song.
 *
 * [MatchedLyricCache.key] is the interesting half: it has to separate two recordings of the same
 * words and nothing else, so every test here is a pair that must or must not collide.
 */
class MatchedLyricCacheTest {

    @Rule @JvmField val folder = TemporaryFolder()

    private lateinit var disk: LyricDiskCache
    private lateinit var cache: MatchedLyricCache

    @Before fun setUp() {
        disk = LyricDiskCache(folder.root)
        cache = MatchedLyricCache(disk)
    }

    private fun key(
        provider: String = "netease",
        title: String = "晴天",
        artist: String = "周杰伦",
        durationMs: Long = 254_000L,
        directId: String = "",
        packageName: String = "com.netease.cloudmusic",
    ) = MatchedLyricCache.key(provider, title, artist, durationMs, directId, packageName)

    @Test fun `the same song in the same catalog is the same key`() {
        assertEquals(key(), key(title = "  晴天 "))
        assertEquals(64, key().length)
    }

    @Test fun `a version marker in the title is a different recording`() {
        assertNotEquals(key(title = "晴天 (Live)"), key(title = "晴天"))
        assertNotEquals(key(durationMs = 254_000L), key(durationMs = 255_000L))
        assertNotEquals(key(provider = "qqmusic"), key(provider = "netease"))
        assertNotEquals(key(directId = "181775989"), key(directId = ""))
    }

    @Test fun `case and repeated spaces are the same song`() {
        assertEquals(key(title = "Lemon"), key(title = "LEMON"))
        assertEquals(key(title = "晴天  周杰伦"), key(title = "晴天 周杰伦"))
    }

    @Test fun `no credit to go on means the player has to be`() {
        // Two unknown players of the same untitled track are not the same track.
        assertNotEquals(key(artist = "", packageName = "com.a"), key(artist = "", packageName = "com.b"))
        // Once an artist names the song, who played it stops mattering.
        assertEquals(key(packageName = "com.a"), key(packageName = "com.b"))
    }

    @Test fun `a stored timeline comes back with its words and its translation`() {
        val timeline = LyricTimeline.parse(
            original = "[00:10.00]故事的小黄花",
            translated = "[00:10]the petals fall",
            wordByWord = "[10000,3000](10000,1000,0)故事(11000,2000,0)的小黄花\n" +
                "[20000,3000](20000,3000,0)从出生那年就飘着",
        )
        assertTrue(timeline.hasWordTiming)
        cache.write("song", timeline)
        val stored = cache.read("song")
        assertEquals(timeline.size, stored.size)
        assertEquals("the petals fall", stored.lines[0].translation)
        assertEquals(2, stored.lines[0].words.size)
        assertEquals("的小黄花", stored.lines[0].words[1].text)
        assertEquals(20_000L, stored.lines[1].startMs)
        assertEquals(timeline.cue(11_500L).wordIndex, stored.cue(11_500L).wordIndex)
    }

    @Test fun `a lyric not worth showing is not remembered`() {
        cache.write("one", LyricTimeline.parse(original = "[00:10.00]只有一行"))
        assertTrue(cache.read("one").empty)
        cache.write("boiler", LyricTimeline.parse(original = "[00:10.00]纯音乐，请欣赏\n[00:20.00] "))
        assertTrue(cache.read("boiler").empty)
    }

    @Test fun `damaged storage reads back as nothing rather than half a song`() {
        disk.write("broken", """[{"startMs":1}]""")
        assertTrue(cache.read("broken").empty)
        assertTrue(cache.read("absent").empty)
    }

    @Test fun `word timing is what a background upgrade is for`() {
        val plain = LyricTimeline.parse(original = "[00:10.00]故事的小黄花\n[00:14.00]从出生那年就飘着")
        assertTrue(cache.needsUpgrade("k", "netease", plain))
        assertTrue(cache.needsUpgrade("k", "kugou", plain))
        cache.markUpgradeChecked("k")
        assertFalse("once tried, never tried again", cache.needsUpgrade("k", "netease", plain))
    }

    @Test fun `a timeline that already has words and a translation is finished`() {
        val full = LyricTimeline.parse(
            original = "[00:10.00]故事的小黄花\n[00:14.00]从出生那年就飘着",
            translated = "[00:10]the petals fall\n[00:14]from the year i was born",
            wordByWord = "[10000,4000](10000,1000,0)故事(11000,3000,1)的(12000,2000,2)小黄花\n" +
                "[14000,3000](14000,3000,0)从出生那年就飘着",
        )
        assertTrue(full.hasWordTiming)
        assertTrue(full.hasTranslation)
        assertFalse(cache.needsUpgrade("k", "netease", full))
    }

    @Test fun `the catalogs with nothing left to give are not upgraded`() {
        val plain = LyricTimeline.parse(original = "[00:10.00]故事的小黄花\n[00:14.00]从出生那年就飘着")
        // Soda's words arrive with its lines, and QQ's word channel is not implemented.
        assertFalse(cache.needsUpgrade("k", "soda", plain))
        assertFalse(cache.needsUpgrade("k", "qqmusic", plain))
    }

    @Test fun `a matched timeline lives in the same bounded store as the payloads`() {
        cache.write("song", LyricTimeline.parse(original = "[00:10.00]故事的小黄花\n[00:14.00]从出生那年"))
        assertEquals(1, File(folder.root, "lyrics").listFiles()!!.size)
    }
}

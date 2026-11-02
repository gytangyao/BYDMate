package com.bydmate.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strings one track is worth searching for.
 *
 * The published metadata always goes first, because that is the string the player's own session id
 * belongs to; everything after it is a rewrite, and a rewrite that finds nothing costs only a request.
 */
class CatalogQueriesTest {

    private val streaming = TrackQuery("晴天", "周杰伦", 254_000L, null, "com.android.mediacenter")

    private fun termsOf(title: String, artist: String = ""): List<SearchTerm> =
        CatalogQueries.terms(streaming.copy(title = title, artist = artist))

    private fun assertTerms(expected: List<Pair<String, String>>, actual: List<SearchTerm>) {
        assertEquals(expected.map { SearchTerm(it.first, it.second) }, actual)
    }

    @Test fun `clean streaming metadata is searched exactly once`() {
        assertTerms(listOf("晴天" to "周杰伦"), CatalogQueries.terms(streaming))
    }

    @Test fun `a blank title is not searched at all`() {
        assertTrue(CatalogQueries.terms(streaming.copy(title = "   ")).isEmpty())
    }

    @Test fun `a file name gives both credit orders`() {
        val terms = termsOf("03. 周杰伦 - 晴天.flac")
        assertTerms(
            listOf(
                "03. 周杰伦 - 晴天.flac" to "",
                "晴天" to "周杰伦",
                "周杰伦" to "晴天",
            ),
            terms,
        )
    }

    @Test fun `the artist inside the title is taken out even when a field already names one`() {
        // A podcast-style session: the credit is in both places, and only the split form can match.
        assertTerms(
            listOf("周杰伦 - 晴天" to "周杰伦", "晴天" to "周杰伦"),
            termsOf("周杰伦 - 晴天", "周杰伦"),
        )
    }

    @Test fun `a path and a quality tag survive only in the published term`() {
        // The published string is always asked first — it is the one the session id belongs to — so the
        // path and the tag go out once, and the cleaned rewrite behind it is what a catalog can match.
        assertTerms(
            listOf("/storage/emulated/0/Music/晴天 [FLAC 无损]" to "", "晴天" to ""),
            termsOf("/storage/emulated/0/Music/晴天 [FLAC 无损]"),
        )
    }

    @Test fun `a track number alone is stripped`() {
        assertTerms(
            listOf("1. 晴天" to "周杰伦", "晴天" to "周杰伦"),
            termsOf("1. 晴天", "周杰伦"),
        )
    }

    @Test fun `a compact separator splits only a file-style title`() {
        // No extension, no track number, no spaces around the dash: an artist credit is left alone.
        assertTerms(listOf("周杰伦-晴天" to "周杰伦"), termsOf("周杰伦-晴天", "周杰伦"))
        assertTerms(listOf("周杰伦_晴天" to "", "晴天" to "周杰伦", "周杰伦" to "晴天"), termsOf("周杰伦_晴天"))
    }

    @Test fun `traditional metadata is searched again in simplified`() {
        assertTerms(listOf("紅豆" to "王菲", "红豆" to "王菲"), termsOf("紅豆", "王菲"))
        assertTerms(
            listOf("愛情轉移" to "陳奕迅", "爱情转移" to "陈奕迅", "爱情转移" to "陳奕迅", "愛情轉移" to "陈奕迅"),
            termsOf("愛情轉移", "陳奕迅"),
        )
    }

    @Test fun `rewrites that come back the same are not asked twice`() {
        // The cleaned title is the raw one, and its split is the metadata the session already gave.
        assertTerms(listOf("晴天" to "周杰伦"), termsOf("晴天", "周杰伦"))
    }

    @Test fun `only the published metadata may carry the session id`() {
        val query = streaming.copy(
            title = "03. 周杰伦 - 晴天.flac",
            mediaId = "181775989",
            terms = CatalogQueries.terms(streaming.copy(title = "03. 周杰伦 - 晴天.flac")),
        )
        assertEquals(2, query.terms.size)
        assertEquals("181775989", query.mediaIdFor(0))
        assertNull(query.mediaIdFor(1))
    }

    @Test fun `file name cleanup leaves a plain title untouched`() {
        assertEquals("晴天", CatalogQueries.cleanFileTitle("  晴天  "))
        assertEquals("周杰伦_晴天", CatalogQueries.cleanFileTitle("02 - 周杰伦_晴天.mp3"))
    }
}

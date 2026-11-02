package com.bydmate.app.lyrics

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Both catalogs, end to end against their real request and response shapes served locally: what a
 * source asks for, what it accepts, and what it remembers. `android.util.Log` is a no-op in these
 * tests, so no Robolectric is needed and every assertion is about the pipeline's decisions.
 */
class LyricSourcesTest {

    @Rule @JvmField val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cache: LyricDiskCache
    private val query = TrackQuery("晴天", "周杰伦", 254_000L, null, "com.android.mediacenter")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        cache = LyricDiskCache(folder.root)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun netEase(): NetEaseLyricSource = NetEaseLyricSource(OkHttpClient(), cache).apply {
        searchUrl = server.url("/search").toString()
        lyricUrl = server.url("/lyric").toString()
    }

    private fun qq(): QQMusicLyricSource = QQMusicLyricSource(OkHttpClient(), cache).apply {
        searchUrl = server.url("/search").toString()
        lyricUrl = server.url("/lyric").toString()
    }

    private fun serve(vararg bodies: String) = bodies.forEach { server.enqueue(MockResponse().setBody(it)) }

    /** A catalog that reached us and declined, which is a different fact from a song without words. */
    private suspend fun assertRefused(source: LyricSource) {
        try {
            source.lookup(query)
            fail("expected CatalogRefused")
        } catch (expected: CatalogRefused) {
            // the whole of the assertion
        }
    }

    /** The endpoints the server saw, oldest first; drains the recorded queue. */
    private fun recordedPaths(): List<String> =
        (0 until server.requestCount).mapNotNull {
            server.takeRequest(5, TimeUnit.MILLISECONDS)?.requestUrl?.encodedPath
        }

    @Test fun `netease searches then asks for the song it matched`() = runBlocking {
        serve(NETEASE_SEARCH_ONE, NETEASE_LYRIC)
        val hit = netEase().lookup(query)
        assertEquals("netease", hit?.sourceId)
        assertEquals("网易云音乐", hit?.label)
        assertEquals("netease:181775989", hit?.songRef)
        assertEquals(2, hit!!.timeline.size)
        assertEquals(listOf("/search", "/lyric"), recordedPaths())
    }

    @Test fun `the search is a web form and the lyric call a signed params`() = runBlocking {
        serve(NETEASE_SEARCH_ONE, NETEASE_LYRIC)
        netEase().lookup(query)
        val search = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/search", search.requestUrl!!.encodedPath)
        assertTrue(search.body.readUtf8().let { it.contains("s=") && it.contains("type=1") })
        assertEquals("https://music.163.com/", search.getHeader("Referer"))
        val lyric = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue(lyric.body.readUtf8().startsWith("params="))
        assertEquals("https://music.163.com/", lyric.getHeader("Origin"))
    }

    @Test fun `a netease session id goes straight to its lyric`() = runBlocking {
        serve(NETEASE_LYRIC)
        val fromSession = query.copy(packageName = "com.netease.cloudmusic", mediaId = "181775989")
        val hit = netEase().lookup(fromSession)
        assertEquals("netease:181775989", hit?.songRef)
        assertEquals(listOf("/lyric"), recordedPaths())
    }

    @Test fun `another app session id is not a netease song id`() = runBlocking {
        serve(NETEASE_SEARCH_ONE, NETEASE_LYRIC)
        assertNotNull(netEase().lookup(query.copy(packageName = "com.android.bluetooth", mediaId = "181775989")))
        assertEquals(listOf("/search", "/lyric"), recordedPaths())
    }

    @Test fun `a session id is only read as a song id when it stands alone`() {
        assertEquals(181_775_989L, NetEaseLyricSource.numericId("181775989"))
        assertEquals(-1L, NetEaseLyricSource.numericId(null))
        assertEquals(-1L, NetEaseLyricSource.numericId("  "))
        assertEquals(-1L, NetEaseLyricSource.numericId("123"))
        assertEquals(-1L, NetEaseLyricSource.numericId("f812345678abcd"))
        assertEquals(-1L, NetEaseLyricSource.numericId("0:254"))
        assertEquals(-1L, NetEaseLyricSource.numericId("12-34"))
    }

    @Test fun `the payload already on disk is not asked for again`() = runBlocking {
        serve(NETEASE_SEARCH_ONE, NETEASE_LYRIC, NETEASE_SEARCH_ONE)
        assertNotNull(netEase().lookup(query))
        assertNotNull(netEase().lookup(query))
        // The search runs again — only the payload is cached — so the lyric endpoint saw one call.
        assertEquals(listOf("/search", "/lyric", "/search"), recordedPaths())
        assertTrue(cache.read("netease:181775989")!!.contains("故事的小黄花"))
    }

    @Test fun `a song with no lyric is a miss for an hour, not a re-ask`() = runBlocking {
        serve(NETEASE_SEARCH_ONE, """{"code":200,"lrc":{"lyric":""}}""", NETEASE_SEARCH_ONE)
        assertNull(netEase().lookup(query))
        assertNull(netEase().lookup(query))
        assertEquals(listOf("/search", "/lyric", "/search"), recordedPaths())
        assertTrue(cache.readNegative("netease:181775989"))
    }

    @Test fun `an unusable payload is not stored as a hit`() = runBlocking {
        serve(NETEASE_SEARCH_ONE, """{"code":200,"lrc":{"lyric":"[00:10.00]纯音乐，请欣赏"}}""")
        assertNull(netEase().lookup(query))
        assertNull(cache.read("netease:181775989"))
    }

    @Test fun `word timing wins over the plain lines`() = runBlocking {
        // Real newlines here: JSONObject writes them as the \n escapes a catalog sends.
        val lrc = "[00:10.00]故事的小黄花\n[00:14.00]从出生那年就飘着"
        val yrc = "[10000,4000](10000,1000,0)故事(11000,3000,1)的(12000,2000,2)小黄花\n" +
            "[14000,3000](14000,3000,0)从出生那年就飘着"
        val payload = JSONObject()
            .put("code", 200)
            .put("lrc", JSONObject().put("lyric", lrc))
            .put("yrc", JSONObject().put("lyric", yrc))
            .put("tlyric", JSONObject().put("lyric", "[00:10]the petals fall"))
            .toString()
        serve(NETEASE_SEARCH_ONE, payload)
        val hit = netEase().lookup(query)
        assertTrue(hit!!.timeline.hasWordTiming)
        assertTrue(hit.timeline.hasTranslation)
        assertEquals(3, hit.timeline.lines.first().words.size)
        assertEquals("的", hit.timeline.lines.first().words[1].text)
        assertEquals("the petals fall", hit.timeline.lines.first().translation)
    }

    @Test fun `a search result the artist disagrees with is not asked for`() = runBlocking {
        // Both searches get an answer: an empty queue would close the connection, and a catalog that
        // will not answer is a refusal, not the miss this test is about.
        serve(
            """{"result":{"songs":[{"id":1,"name":"晴天","artists":[{"name":"其他歌手"}],"duration":254000}]}}""",
            """{"result":{"songs":[]}}""",
        )
        assertNull(netEase().lookup(query))
        // The term is retried without the artist, but the rejected candidate never reaches /lyric.
        assertEquals(listOf("/search", "/search"), recordedPaths())
    }

    @Test fun `a search with no songs is a miss`() = runBlocking {
        serve("""{"result":{"songs":[]}}""", """{"result":{"songs":[]}}""")
        assertNull(netEase().lookup(query))
        assertEquals(listOf("/search", "/search"), recordedPaths())
    }

    @Test fun `a title alone is searched when the term with the artist finds nothing`() = runBlocking {
        serve("""{"result":{"songs":[]}}""", NETEASE_SEARCH_ONE, NETEASE_LYRIC)
        assertNotNull(netEase().lookup(query))
        assertEquals(3, server.requestCount)
    }

    @Test fun `a catalog that answers 503 refused`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody(""))
        assertRefused(netEase())
        assertEquals(1, server.requestCount)
    }

    @Test fun `a code other than two hundred is a refusal, not a lyric`() = runBlocking {
        serve(NETEASE_SEARCH_ONE, """{"code":404,"msg":"not found"}""")
        assertRefused(netEase())
        assertEquals(listOf("/search", "/lyric"), recordedPaths())
    }

    @Test fun `a rate-limited search asks nothing further`() = runBlocking {
        // 「操作频繁，请稍候再试」 with an HTTP 200 on the wire. Reading it as an empty result set would
        // send a second, narrower search into the same wall and then blacklist the track behind it.
        serve("""{"code":405,"msg":"操作频繁，请稍候再试"}""")
        assertRefused(netEase())
        assertEquals(listOf("/search"), recordedPaths())
    }

    @Test fun `a rate-limited lyric is asked for again`() = runBlocking {
        serve(NETEASE_SEARCH_ONE, """{"code":405,"msg":"操作频繁，请稍候再试"}""", NETEASE_SEARCH_ONE, NETEASE_LYRIC)
        assertRefused(netEase())
        assertFalse("a refusal is not a song without words", cache.readNegative("netease:181775989"))
        assertNotNull(netEase().lookup(query))
    }

    @Test fun `a body that is not json is a refusal`() = runBlocking {
        serve("not json at all")
        assertRefused(netEase())
        assertNull(cache.read("netease:181775989"))
    }

    @Test fun `qq picks the studio original over a cover in its list`() = runBlocking {
        serve(
            """{"data":{"song":{"list":[""" +
                """{"songmid":"cover01","songname":"晴天 (翻唱)","singer":[{"name":"周杰伦"}],"interval":254},""" +
                """{"songmid":"orig01","songname":"晴天","singer":[{"name":"周杰伦"}],"interval":254}]""" +
                """}}}""",
            QQ_LYRIC,
        )
        val hit = qq().lookup(query)
        assertEquals("qq:orig01", hit?.songRef)
        assertEquals("QQ 音乐", hit?.label)
        assertEquals(listOf("/search", "/lyric"), recordedPaths())
    }

    @Test fun `qq sends its search as a get with the term encoded`() = runBlocking {
        serve("""{"data":{"song":{"list":[]}}}""")
        assertNull(qq().lookup(query))
        val search = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", search.method)
        assertEquals("晴天 周杰伦", search.requestUrl!!.queryParameter("w"))
        assertEquals("https://y.qq.com/", search.getHeader("Referer"))
    }

    @Test fun `qq entity escaped text arrives as text`() = runBlocking {
        serve(QQ_SEARCH_ONE, """{"lyric":"[00:10.00]故事&#58;的小黄花\n[00:14.00]从&apos;出生那年就飘着","trans":""}""")
        val hit = qq().lookup(query)
        assertEquals("故事:的小黄花", hit!!.timeline.lines.first().text)
    }

    @Test fun `a vip track is a miss, not a crash`() = runBlocking {
        serve(QQ_SEARCH_ONE, """{"lyric":""}""")
        assertNull(qq().lookup(query))
        assertTrue(cache.readNegative("qq:orig01"))
    }

    @Test fun `qq measures its interval in seconds`() = runBlocking {
        serve(QQ_SEARCH_SHORT, QQ_LYRIC, QQ_SEARCH_SHORT)
        assertNotNull(qq().lookup(query.copy(durationMs = 180_000L)))
        // 180 s is not the 254 s track the head unit published, so it is not even asked for.
        assertNull(qq().lookup(query))
        assertEquals(listOf("/search", "/lyric", "/search"), recordedPaths())
    }

    @Test fun `the cache is bounded and drops an entry past its cap`() {
        val small = LyricDiskCache(folder.root, maxEntries = 2)
        small.write("a", "one")
        small.write("b", "two")
        small.write("c", "three")
        assertEquals(2, lyricsDir().size)
        assertEquals("three", small.read("c"))
    }

    @Test fun `an empty body is only ever written by the negative cache`() {
        val store = LyricDiskCache(folder.root)
        store.write("key", "")
        assertNull(store.read("key"))
        store.writeNegative("key")
        assertTrue(store.readNegative("key"))
    }

    @Test fun `a cache key from a session never reaches outside its own directory`() {
        val store = LyricDiskCache(folder.root)
        store.write("../../escape.json", "text")
        assertEquals("text", store.read("../../escape.json"))
        assertFalse(File(folder.root, "escape.json").exists())
        assertEquals(1, lyricsDir().size)
    }

    private fun lyricsDir() = File(folder.root, "lyrics").listFiles() ?: emptyArray()

    private companion object {
        const val NETEASE_SEARCH_ONE =
            """{"result":{"songs":[{"id":181775989,"name":"晴天","artists":[{"name":"周杰伦"}],"duration":254000}]}}"""
        const val NETEASE_LYRIC =
            """{"code":200,"lrc":{"lyric":"[00:10.00]故事的小黄花\n[00:14.00]从出生那年就飘着"}}"""
        const val QQ_SEARCH_ONE =
            """{"data":{"song":{"list":[{"songmid":"orig01","songname":"晴天","singer":[{"name":"周杰伦"}],"interval":254}]}}}"""
        const val QQ_SEARCH_SHORT =
            """{"data":{"song":{"list":[{"songmid":"short1","songname":"晴天","singer":[{"name":"周杰伦"}],"interval":180}]}}}"""
        const val QQ_LYRIC =
            """{"lyric":"[00:10.00]故事的小黄花\n[00:14.00]从出生那年就飘着"}"""
    }
}

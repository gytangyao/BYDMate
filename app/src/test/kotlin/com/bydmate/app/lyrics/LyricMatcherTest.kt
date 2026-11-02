package com.bydmate.app.lyrics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * All six catalogs at once, answers taken by endpoint rather than by queue position.
 *
 * The two big catalogs really do run together, so which one won has to be a statement about
 * precedence, not about which request arrived first. Every host resolves to the loopback: a catalog
 * whose endpoint was not pointed at the mock server fails instead of going out to the internet from a
 * test, which is what matters for the four catalogs whose lyric files come out of their own records.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LyricMatcherTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private lateinit var server: MockWebServer
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    private val delays = mutableMapOf<String, Long>()

    private lateinit var matcher: LyricMatcher

    private val query = TrackQuery("晴天", "周杰伦", 254_000L, null, "com.android.mediacenter")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                seen += path
                val response = routes.entries.sortedByDescending { it.key.length }
                    .firstOrNull { path.startsWith(it.key) }?.value?.invoke(request)
                    ?: MockResponse().setResponseCode(404)
                return delays[path]?.let { response.setBodyDelay(it, TimeUnit.MILLISECONDS) } ?: response
            }
        }
        server.start()
        val loopback = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("127.0.0.1"))
        }).build()
        matcher = LyricMatcher(context, loopback).apply {
            netEase.searchUrl = server.url("/netease/search").toString()
            netEase.lyricUrl = server.url("/netease/lyric").toString()
            qqMusic.searchUrl = server.url("/qq/search").toString()
            qqMusic.lyricUrl = server.url("/qq/lyric").toString()
            kugou.searchUrl = server.url("/kugou/search").toString()
            kugou.lyricSearchUrl = server.url("/kugou/lyric").toString()
            kugou.downloadUrl = server.url("/kugou/download").toString()
            kuwo.searchUrl = server.url("/kuwo/search").toString()
            kuwo.wordUrl = server.url("/kuwo/word").toString()
            kuwo.webUrl = server.url("/kuwo/web").toString()
            soda.searchUrl = server.url("/soda/search").toString()
            soda.shareUrl = server.url("/soda/share?track_id=").toString()
            soda.carShareUrl = server.url("/soda/car?track_id=").toString()
            migu.searchUrl = server.url("/migu/search").toString()
            migu.resourceUrl = server.url("/migu/resource").toString()
        }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun answer(path: String, body: String) {
        routes[path] = { MockResponse().setBody(body) }
    }

    private fun stall(path: String, millis: Long) {
        delays[path] = millis
    }

    /** Every catalog reached and saying it has no words for this track. */
    private fun everyCatalogAnswersNothing() {
        answer("/netease/search", """{"result":{"songs":[]}}""")
        answer("/qq/search", """{"data":{"song":{"list":[]}}}""")
        answer("/kugou/search", """{"data":{"lists":[]}}""")
        answer("/kuwo/search", """{"abslist":[]}""")
        answer("/soda/search", """{"result_groups":[]}""")
        answer("/migu/search", """{"code":"000000","songResultData":{"resultList":[]}}""")
    }

    private fun netEaseHasIt() {
        answer("/netease/search", NETEASE_SEARCH)
        answer("/netease/lyric", NETEASE_LYRIC)
    }

    private fun qqHasIt() {
        answer("/qq/search", QQ_SEARCH)
        answer("/qq/lyric", QQ_LYRIC)
    }

    private fun kugouHasIt() {
        answer("/kugou/search", KUGOU_SEARCH)
        answer("/kugou/lyric", """{"candidates":[{"id":"L1","accesskey":"A1"}]}""")
        routes["/kugou/download"] = { request ->
            val plain = request.requestUrl?.queryParameter("fmt") == "lrc"
            val content = if (plain) KUGOU_LRC else "not-a-krc-payload"
            MockResponse().setBody(JSONObject().put("content", content).toString())
        }
    }

    private fun kuwoHasIt() {
        answer("/kuwo/search", KUWO_SEARCH)
        answer("/kuwo/word", "no envelope here, just an error page")
        answer("/kuwo/web", KUWO_WEB)
    }

    private fun sodaHasIt() {
        answer("/soda/search", SODA_SEARCH)
        answer("/soda/share", sodaSharePage())
    }

    private fun miguHasIt() {
        answer("/migu/search", MIGU_SEARCH)
        // The resource record names where the lyric file really sits, so the url has to carry this
        // server's port; every catalog host still resolves to the loopback.
        answer(
            "/migu/resource",
            JSONObject().put(
                "resource",
                JSONArray().put(JSONObject().put("lrcUrl", "http://music.migu.cn:${server.port}/migu/lrc")),
            ).toString(),
        )
        answer("/migu/lrc", MIGU_LRC)
    }

    @Test fun `both big catalogs are asked and precedence decides, not speed`() = runBlocking {
        // QQ answers at once, NetEase 200 ms later: the order the answers arrive in must not decide.
        stall("/netease/search", 200L)
        stall("/netease/lyric", 200L)
        netEaseHasIt()
        qqHasIt()
        assertEquals("netease", matcher.match(query).hit?.sourceId)
        assertTrue(seen.contains("/qq/search"))
    }

    @Test fun `the playing app catalog goes to the front of the queue`() = runBlocking {
        stall("/qq/search", 100L)
        qqHasIt()
        val hit = matcher.match(query, "qqmusic").hit
        assertEquals("qqmusic", hit?.sourceId)
        assertEquals("qq:orig01", hit?.songRef)
    }

    @Test fun `the player's own catalog is asked alone, before the others are woken`() = runBlocking {
        // Priority is serial, so a hit from it means the other five were never disturbed.
        kuwoHasIt()
        val hit = matcher.match(query, "kuwo").hit
        assertEquals("kuwo", hit?.sourceId)
        assertEquals(listOf("/kuwo/search", "/kuwo/word/mobi.s", "/kuwo/web"), seen)
    }

    @Test fun `a song only kugou has is still found`() = runBlocking {
        netEaseHasIt()
        qqHasIt()
        answer("/netease/lyric", """{"code":200,"lrc":{"lyric":""}}""")
        answer("/qq/lyric", """{"lyric":""}""")
        kugouHasIt()
        val outcome = matcher.match(query)
        assertEquals("kugou", outcome.hit?.sourceId)
        assertEquals("酷狗音乐", outcome.hit?.label)
        assertEquals("kugou:HASH01", outcome.hit?.songRef)
    }

    @Test fun `every catalog is asked about a song only the last one has`() = runBlocking {
        everyCatalogAnswersNothing()
        miguHasIt()
        assertEquals("migu", matcher.match(query).hit?.sourceId)
        for (prefix in listOf("/netease/search", "/qq/search", "/kugou/search", "/kuwo/search", "/soda/search", "/migu/search")) {
            assertTrue("$prefix was not asked", seen.any { it.startsWith(prefix) })
        }
    }

    @Test fun `an unknown preferred source keeps the default order`() = runBlocking {
        netEaseHasIt()
        assertEquals("netease", matcher.match(query, "com.example.radio").hit?.sourceId)
    }

    @Test fun `a catalog with nothing usable hands the track to the next`() = runBlocking {
        netEaseHasIt()
        answer("/netease/lyric", """{"code":200,"lrc":{"lyric":""}}""")
        qqHasIt()
        assertEquals("qqmusic", matcher.match(query).hit?.sourceId)
    }

    @Test fun `every catalog answering nothing is a miss worth remembering`() = runBlocking {
        // Both said, in so many words: this track has no text. An empty payload is the answer; a 404 is not.
        everyCatalogAnswersNothing()
        val outcome = matcher.match(query)
        assertNull(outcome.hit)
        assertTrue(outcome.answered)
        assertTrue(LyricDiskCache(context.cacheDir).readNegative(trackKey()))
    }

    @Test fun `a catalog that will not answer leaves the track owed`() = runBlocking {
        // Nothing is configured: every endpoint answers 404, which is a refusal and not a verdict.
        val outcome = matcher.match(query)
        assertNull(outcome.hit)
        assertFalse("a 404 is a refusal, not a song without words", outcome.answered)
        assertFalse(LyricDiskCache(context.cacheDir).readNegative(trackKey()))
    }

    @Test fun `one catalog refusing does not stop another from answering`() = runBlocking {
        qqHasIt()
        val outcome = matcher.match(query)
        assertEquals("qqmusic", outcome.hit?.sourceId)
        assertTrue(outcome.answered)
    }

    @Test fun `a title with nothing in it is not searched`() = runBlocking {
        val outcome = matcher.match(query.copy(title = "   "))
        assertNull(outcome.hit)
        assertTrue("nothing to look up is an answer in itself: the engine must not retry it", outcome.answered)
        assertTrue(seen.isEmpty())
    }

    @Test fun `a session id reaches only its own catalog`() = runBlocking {
        netEaseHasIt()
        qqHasIt()
        val fromNetEase = query.copy(packageName = "com.netease.cloudmusic", mediaId = "181775989")
        // The engine resolves the playing package to its catalog and hands that id to the lookup.
        val hit = matcher.match(fromNetEase, matcher.sourceForPackage(fromNetEase.packageName)).hit
        assertEquals("netease", hit?.sourceId)
        // NetEase read the id straight off the session and answered, so the fan-out was never needed:
        // the preferred catalog is asked alone, and no other catalog had to search for the same song.
        assertEquals(1, seen.count { it == "/netease/lyric" })
        assertEquals(0, seen.count { it == "/netease/search" })
        assertEquals(0, seen.count { it == "/qq/search" })
    }

    @Test fun `a remembered match needs no request at all`() = runBlocking {
        // Words and a translation both present, so nothing is left for a background upgrade to fetch
        // and the absence of traffic is a statement about the cache rather than about timing.
        answer("/netease/search", NETEASE_SEARCH)
        answer("/netease/lyric", NETEASE_LYRIC_FULL)
        assertNotNull(matcher.match(query).hit)
        seen.clear()
        val again = matcher.match(query)
        assertEquals("netease", again.hit?.sourceId)
        assertEquals("cached:netease", again.hit?.songRef)
        assertTrue(again.hit!!.timeline.hasWordTiming)
        assertTrue(seen.isEmpty())
    }

    @Test fun `a remembered timeline without words is upgraded behind the answer`() = runBlocking {
        netEaseHasIt()
        val first = matcher.match(query).hit
        assertNotNull(first)
        assertFalse("the first answer is the line-timed one", first!!.timeline.hasWordTiming)
        // The same song answered with word timing now: the upgrade asks it again on its own thread,
        // behind the answer already on screen, so the driver never waits for it.
        answer("/netease/lyric", NETEASE_LYRIC_WORDS)
        val second = matcher.match(query).hit
        assertEquals("cached:netease", second?.songRef)
        assertFalse(second!!.timeline.hasWordTiming)
        val key = MatchedLyricCache.key(
            "netease", query.title, query.artist, query.durationMs, "", query.packageName,
        )
        val disk = LyricDiskCache(context.cacheDir)
        var latest = MatchedLyricCache(disk).read(key)
        var rounds = 0
        while (!latest.hasWordTiming && rounds < 60) {
            Thread.sleep(50)
            rounds++
            latest = MatchedLyricCache(disk).read(key)
        }
        assertTrue("the background upgrade never landed", latest.hasWordTiming)
    }

    @Test fun `a playing app maps to the catalog it can be asked`() {
        assertEquals("qqmusic", matcher.sourceForPackage("com.tencent.qqmusic"))
        assertEquals("qqmusic", matcher.sourceForPackage("com.tencent.qqmusiccar"))
        assertEquals("netease", matcher.sourceForPackage("com.netease.cloudmusic"))
        assertEquals("netease", matcher.sourceForPackage("com.netease.cloudmusic.iot"))
        assertEquals("kugou", matcher.sourceForPackage("com.kugou.android"))
        assertEquals("kugou", matcher.sourceForPackage("com.kugou.android.auto"))
        assertEquals("kuwo", matcher.sourceForPackage("cn.kuwo.player"))
        assertEquals("kuwo", matcher.sourceForPackage("cn.kuwo.kwmusiccar"))
        assertEquals("soda", matcher.sourceForPackage("com.luna.music"))
        assertEquals("soda", matcher.sourceForPackage("com.luna.music.car"))
        assertEquals("migu", matcher.sourceForPackage("cmccwm.mobilemusic"))
        assertNull(matcher.sourceForPackage("com.spotify.music"))
        assertNull(matcher.sourceForPackage("com.android.bluetooth"))
    }

    @Test fun `a catalog names itself for the status line`() {
        assertEquals("网易云音乐", matcher.labelOf("netease"))
        assertEquals("QQ 音乐", matcher.labelOf("qqmusic"))
        assertEquals("酷狗音乐", matcher.labelOf("kugou"))
        assertEquals("酷我音乐", matcher.labelOf("kuwo"))
        assertEquals("汽水音乐", matcher.labelOf("soda"))
        assertEquals("咪咕音乐", matcher.labelOf("migu"))
        assertEquals("unknown", matcher.labelOf("unknown"))
    }

    private fun trackKey() =
        TrackMatch.trackKey(query.packageName, query.title, query.artist, query.durationMs)

    private companion object {
        const val NETEASE_SEARCH =
            """{"result":{"songs":[{"id":181775989,"name":"晴天","artists":[{"name":"周杰伦"}],"duration":254000}]}}"""
        const val NETEASE_LYRIC =
            """{"code":200,"lrc":{"lyric":"[00:10.00]故事的小黄花\n[00:14.00]从出生那年就飘着"}}"""
        const val NETEASE_LYRIC_FULL = """{"code":200,"lrc":{"lyric":"[00:10.00]故事的小黄花""" +
            """\n[00:14.00]从出生那年就飘着"},"tlyric":{"lyric":"[00:10.00]the petals""" +
            """\n[00:14.00]from the year i was born"},"yrc":{"lyric":"[10000,4000]""" +
            """(10000,1000,0)故事(11000,3000,1)的小黄花\n[14000,3000](14000,3000,0)从出生那年就飘着"}}"""
        const val NETEASE_LYRIC_WORDS = """{"code":200,"lrc":{"lyric":"[00:10.00]故事的小黄花""" +
            """\n[00:14.00]从出生那年就飘着"},"yrc":{"lyric":"[10000,4000](10000,1000,0)故事""" +
            """(11000,3000,1)的小黄花\n[14000,3000](14000,3000,0)从出生那年就飘着"}}"""
        const val QQ_SEARCH =
            """{"data":{"song":{"list":[{"songmid":"orig01","songname":"晴天","singer":[{"name":"周杰伦"}],"interval":254}]}}}"""
        const val QQ_LYRIC = """{"lyric":"[00:10.00]从出生那年就飘着\n[00:14.00]故事的小黄花"}"""
        const val KUGOU_SEARCH =
            """{"data":{"lists":[{"SongName":"晴天","SingerName":"周杰伦","Duration":254,"FileHash":"HASH01"}]}}"""
        val KUGOU_LRC: String = Base64.getEncoder().encodeToString(
            "[00:10.00]故事的小黄花\n[00:14.00]从出生那年就飘着\n".toByteArray(),
        )
        const val KUWO_SEARCH =
            """{"abslist":[{"MUSICRID":"MUSIC_123456","NAME":"晴天","ARTIST":"周杰伦","DURATION":254}]}"""
        const val KUWO_WEB =
            """{"status":200,"data":{"lrclist":[{"time":"10.00","lineLyric":"故事的小黄花"},""" +
                """{"time":"14.00","lineLyric":"从出生那年就飘着"}]}}"""
        const val SODA_SEARCH = """{"result_groups":[{"data":[{"entity":{"track":{"id":"71555444",""" +
            """"name":"晴天","artists":[{"name":"周杰伦"}],"duration":254000}}}]}"""
        const val MIGU_SEARCH = """{"code":"000000","songResultData":{"resultList":[[{"name":"晴天",""" +
            """singerList":[{"name":"周杰伦"}],"duration":254,"copyrightId":"CID0001"}]]}}"""
        const val MIGU_LRC = "[00:10.00]故事的小黄花\n[00:14.00]从出生那年就飘着\n"

        fun sodaSharePage(): String {
            val sentences = JSONArray()
                .put(
                    JSONObject().put("startMs", 10_000L).put("endMs", 13_000L).put("text", "故事的小黄花")
                        .put("words", JSONArray().put(JSONObject().put("startMs", 10_000L)
                            .put("endMs", 13_000L).put("text", "故事的小黄花"))),
                )
                .put(
                    JSONObject().put("startMs", 14_000L).put("endMs", 17_000L).put("text", "从出生那年就飘着")
                        .put("words", JSONArray().put(JSONObject().put("startMs", 14_000L)
                            .put("endMs", 17_000L).put("text", "从出生那年就飘着"))),
                )
            val router = JSONObject().put("loaderData", JSONObject().put("track_page",
                JSONObject().put("audioWithLyricsOption", JSONObject().put("lyrics",
                    JSONObject().put("sentences", sentences)))))
            return "<html><body><script>window._ROUTER_DATA = $router;</script></body></html>"
        }
    }
}

package com.bydmate.app.lyrics

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The four catalogs beyond NetEase and QQ Music, end to end against their real request and response
 * shapes served locally.
 *
 * Every host resolves to the loopback, so a port that was not wired to the mock server fails instead
 * of reaching the internet — Migu's lyric files come out of its resource record as absolute urls on
 * `*.migu.cn`, and those are the one place a test can accidentally go looking for a real song.
 */
class CatalogSourcesTest {

    @Rule @JvmField val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cache: LyricDiskCache
    private val query = TrackQuery("晴天", "周杰伦", 254_000L, null, "com.android.mediacenter")

    /** path prefix → the answer for it; anything not listed answers 404, which is a refusal. */
    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    private val seen = Collections.synchronizedList(mutableListOf<String>())

    private val loopback = OkHttpClient.Builder().dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            listOf(InetAddress.getByName("127.0.0.1"))
    }).build()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                seen += path
                val route = routes.entries.sortedByDescending { it.key.length }.firstOrNull { path.startsWith(it.key) }
                return route?.value?.invoke(request) ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        cache = LyricDiskCache(folder.root)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun answer(path: String, body: String) {
        routes[path] = { MockResponse().setBody(body) }
    }

    private fun answerBytes(path: String, body: ByteArray) {
        routes[path] = { MockResponse().setBody(okio.Buffer().write(body)) }
    }

    private fun refuse(path: String, code: Int = 503) {
        routes[path] = { MockResponse().setResponseCode(code) }
    }

    private fun pathsFor(prefix: String) = seen.count { it.startsWith(prefix) }

    private suspend fun assertRefused(source: LyricSource) {
        try {
            source.lookup(query)
            fail("expected CatalogRefused")
        } catch (expected: CatalogRefused) {
            // the whole of the assertion
        }
    }

    // ---- 酷狗 -------------------------------------------------------------------------------

    private fun kugou() = KugouLyricSource(loopback, cache).apply {
        searchUrl = server.url("/kugou/search").toString()
        lyricSearchUrl = server.url("/kugou/lyric").toString()
        downloadUrl = server.url("/kugou/download").toString()
    }

    private val kugouSearch =
        """{"data":{"lists":[{"SongName":"晴天","SingerName":"周杰伦","Duration":254,"FileHash":"HASH01"}]}}"""

    private val krcText = """
        [10000,3000]<0,1500,0>故事<1500,1500,0>的小黄花
        [20000,3000]<0,3000,0>从出生那年就飘着
    """.trimIndent() + "\n"

    private val kugouLrc = "[00:10.00]故事的小黄花\n[00:20.00]从出生那年就飘着\n"

    private fun serveKugou(krc: String = krcText, lrc: String = kugouLrc) {
        answer("/kugou/search", kugouSearch)
        answer("/kugou/lyric", """{"candidates":[{"id":"L1","accesskey":"A1"}]}""")
        routes["/kugou/download"] = { request ->
            val content = if (request.requestUrl?.queryParameter("fmt") == "krc") encodeKrc(krc) else encodeLrc(lrc)
            MockResponse().setBody(JSONObject().put("content", content).toString())
        }
    }

    @Test fun `kugou searches, names the lyric, then downloads both payloads`() {
        serveKugou()
        val hit = runBlocking { kugou().lookup(query) }
        assertEquals("kugou", hit?.sourceId)
        assertEquals("酷狗音乐", hit?.label)
        assertEquals("kugou:HASH01", hit?.songRef)
        assertTrue(hit!!.timeline.hasWordTiming)
        assertEquals(listOf("/kugou/search", "/kugou/lyric", "/kugou/download", "/kugou/download"), seen)
    }

    @Test fun `kugou stores the word payload and the plain one apart from each other`() {
        serveKugou()
        assertNotNull(runBlocking { kugou().lookup(query) })
        assertNotNull(cache.read("kugou:HASH01:krc"))
        assertNotNull(cache.read("kugou:HASH01:original"))
        assertNotNull(cache.read("kugou:HASH01:translation-checked"))
        assertTrue(cache.read("kugou:HASH01:krc")!!.contains("(10000,1500,0)"))
    }

    @Test fun `kugou keeps its payloads and downloads neither again`() {
        serveKugou()
        assertNotNull(runBlocking { kugou().lookup(query) })
        seen.clear()
        assertNotNull(runBlocking { kugou().lookup(query) })
        assertEquals(listOf("/kugou/search"), seen)
    }

    @Test fun `a candidate kugou scores below the bar is never downloaded`() {
        answer(
            "/kugou/search",
            """{"data":{"lists":[{"SongName":"晴天","SingerName":"其他歌手","Duration":254,"FileHash":"HASH01"}]}}""",
        )
        assertNull(runBlocking { kugou().lookup(query) })
        assertEquals(listOf("/kugou/search"), seen)
    }

    @Test fun `kugou without a lyric record has nothing to download`() {
        answer("/kugou/search", kugouSearch)
        answer("/kugou/lyric", """{"error_code":0,"candidates":[]}""")
        assertNull(runBlocking { kugou().lookup(query) })
        assertEquals(0, pathsFor("/kugou/download"))
    }

    @Test fun `a kugou lyric search that will not answer is a refusal`() {
        answer("/kugou/search", kugouSearch)
        refuse("/kugou/lyric")
        runBlocking { assertRefused(kugou()) }
        assertEquals(0, pathsFor("/kugou/download"))
    }

    @Test fun `kugou reads its translation out of the krc header`() {
        val rows = JSONArray().put(JSONArray().put("the petals fall")).put(JSONArray().put("from birth"))
        val language = JSONObject().put(
            "content", JSONArray().put(JSONObject().put("type", 1).put("lyricContent", rows)),
        ).toString()
        val withHeader = "[language:${Base64.getEncoder().encodeToString(language.toByteArray())}]\n" + krcText
        serveKugou(krc = withHeader)
        val hit = runBlocking { kugou().lookup(query) }
        assertTrue(hit!!.timeline.hasTranslation)
        assertEquals("the petals fall", hit.timeline.lines[0].translation)
    }

    // ---- 酷我 -------------------------------------------------------------------------------

    private fun kuwo() = KuwoLyricSource(loopback, cache).apply {
        searchUrl = server.url("/kuwo/search").toString()
        wordUrl = server.url("/kuwo/word").toString()
        webUrl = server.url("/kuwo/web").toString()
    }

    private val kuwoSearch =
        """{"abslist":[{"MUSICRID":"MUSIC_123456","NAME":"晴天","ARTIST":"周杰伦","DURATION":254}]}"""

    private val kuwoWebEmpty = """{"status":200,"msg":"ok","data":{"lrclist":[]}}"""

    private val kuwoWordLyric = """
        [kuwo:13]
        [00:10.00]<1000,-1000>故事<3000,1000>的小黄花
        [00:20.00]<0,3000>从出生那年就飘着
    """.trimIndent() + "\n"

    @Test fun `kuwo asks its word channel and keeps the timing`() {
        answer("/kuwo/search", kuwoSearch)
        answerBytes("/kuwo/word", encodeKuwo(kuwoWordLyric))
        val hit = runBlocking { kuwo().lookup(query) }
        assertEquals("kuwo:123456", hit?.songRef)
        assertEquals("酷我音乐", hit?.label)
        assertTrue(hit!!.timeline.hasWordTiming)
        assertEquals(2, hit.timeline.size)
        assertEquals(listOf("/kuwo/search", "/kuwo/word/mobi.s"), seen)
    }

    @Test fun `a word channel with nothing in it falls back to the web lyric`() {
        answer("/kuwo/search", kuwoSearch)
        answer("/kuwo/word", "tp=nothing\r\n\r\nnot an envelope at all")
        answer(
            "/kuwo/web",
            """{"status":200,"data":{"lrclist":[{"time":"10.00","lineLyric":"故事的小黄花"},""" +
                """{"time":"20.00","lineLyric":"从出生那年就飘着"}]}}""",
        )
        val hit = runBlocking { kuwo().lookup(query) }
        assertFalse(hit!!.timeline.hasWordTiming)
        assertEquals("故事的小黄花", hit.timeline.lines[0].text)
        assertEquals(listOf("/kuwo/search", "/kuwo/word/mobi.s", "/kuwo/web"), seen)
    }

    @Test fun `two failures close the word channel and the next song does not wait`() {
        answer("/kuwo/search", kuwoSearch)
        refuse("/kuwo/word")
        answer("/kuwo/web", kuwoWebEmpty)
        val source = kuwo()
        runBlocking {
            assertNull(source.lookup(query))
            assertNull(source.lookup(query))
            assertEquals(2, pathsFor("/kuwo/word"))
            seen.clear()
            assertNull(source.lookup(query))
        }
        assertEquals(0, pathsFor("/kuwo/word"))
        assertEquals(1, pathsFor("/kuwo/web"))
    }

    @Test fun `a session rid is asked without a search`() {
        answerBytes("/kuwo/word", encodeKuwo(kuwoWordLyric))
        val fromSession = query.copy(packageName = "cn.kuwo.player", mediaId = "MUSIC_123456")
        assertNotNull(runBlocking { kuwo().lookup(fromSession) })
        assertEquals(listOf("/kuwo/word/mobi.s"), seen)
    }

    @Test fun `a kuwo status other than success is a refusal, not a song without words`() {
        answer("/kuwo/search", kuwoSearch)
        answer("/kuwo/word", "no envelope here, just an error page")
        answer("/kuwo/web", """{"status":500,"msg":"busy"}""")
        runBlocking { assertRefused(kuwo()) }
    }

    @Test fun `kuwo asks at most three of the candidates it found`() {
        val four = JSONObject().put(
            "abslist",
            JSONArray().apply {
                for (index in 0 until 4) {
                    put(JSONObject().put("MUSICRID", "MUSIC_10000$index").put("NAME", "晴天")
                        .put("ARTIST", "周杰伦").put("DURATION", 254))
                }
            },
        ).toString()
        answer("/kuwo/search", four)
        answer("/kuwo/word", "no envelope here, just an error page")
        answer("/kuwo/web", kuwoWebEmpty)
        assertNull(runBlocking { kuwo().lookup(query) })
        assertEquals(3, pathsFor("/kuwo/word"))
    }

    @Test fun `kuwo reads its own ids and undoes its own escaping`() {
        assertEquals("123456", KuwoLyricSource.trackId("MUSIC_123456"))
        assertEquals("123456", KuwoLyricSource.trackId(" 123456 "))
        assertEquals("", KuwoLyricSource.trackId("MUSIC_"))
        assertEquals("", KuwoLyricSource.trackId("0"))
        assertEquals("", KuwoLyricSource.trackId(null))
        assertEquals("", KuwoLyricSource.trackId("f812345678abcd"))
        assertEquals("A&B", KuwoLyricSource.decode("""A\\u0026B"""))
        assertEquals("Don't", KuwoLyricSource.decode("Don&#39;t"))
        assertEquals("晴天\"", KuwoLyricSource.decode("晴天&quot;"))
    }

    @Test fun `kuwo stamps its web lines from fractional seconds`() {
        val items = JSONArray()
            .put(JSONObject().put("time", "10.5").put("lineLyric", "故事的小黄花"))
            .put(JSONObject().put("time", "-1").put("lineLyric", "不该出现"))
            .put(JSONObject().put("time", "20").put("lineLyric", "  "))
        assertEquals("[00:10.500]故事的小黄花\n", KuwoLyricSource.lines(items))
    }

    // ---- 汽水 -------------------------------------------------------------------------------

    private fun soda() = SodaLyricSource(loopback, cache).apply {
        searchUrl = server.url("/soda/search").toString()
        shareUrl = server.url("/soda/share?track_id=").toString()
        carShareUrl = server.url("/soda/car?track_id=").toString()
    }

    private val sodaSearch = """{"result_groups":[{"data":[{"entity":{"track":{"id":"71555444",""" +
        """"name":"晴天","artists":[{"name":"周杰伦"}],"duration":254000}}}]}]}"""

    private fun sharePage(sentences: JSONArray): String {
        val router = JSONObject().put(
            "loaderData",
            JSONObject().put(
                "track_page",
                JSONObject().put(
                    "audioWithLyricsOption",
                    JSONObject().put("lyrics", JSONObject().put("sentences", sentences)),
                ),
            ),
        )
        return "<html><body><script>window._ROUTER_DATA = $router;</script></body></html>"
    }

    private val sodaSentences = JSONArray()
        .put(
            JSONObject()
                .put("startMs", 10_000L).put("endMs", 13_000L).put("text", "故事的小黄花")
                .put("translation", "the petals fall")
                .put(
                    "words",
                    JSONArray()
                        .put(JSONObject().put("startMs", 10_000L).put("endMs", 11_000L).put("text", "故事"))
                        .put(JSONObject().put("startMs", 11_000L).put("endMs", 13_000L).put("text", "的小黄花")),
                ),
        )
        .put(
            JSONObject()
                .put("startMs", 20_000L).put("endMs", 23_000L).put("text", "从出生那年就飘着")
                .put(
                    "words",
                    JSONArray().put(JSONObject().put("startMs", 20_000L).put("endMs", 23_000L).put("text", "从出生那年就飘着")),
                ),
        )

    @Test fun `soda's share page carries lines, words and a translation at once`() {
        answer("/soda/search", sodaSearch)
        answer("/soda/share", sharePage(sodaSentences))
        val hit = runBlocking { soda().lookup(query) }
        assertEquals("soda:71555444", hit?.songRef)
        assertEquals("汽水音乐", hit?.label)
        assertTrue(hit!!.timeline.hasWordTiming)
        assertTrue(hit.timeline.hasTranslation)
        assertEquals("the petals fall", hit.timeline.lines[0].translation)
        assertEquals(listOf("/soda/search", "/soda/share"), seen)
    }

    @Test fun `a car session reads the car share page`() {
        answer("/soda/search", sodaSearch)
        answer("/soda/car", sharePage(sodaSentences))
        assertNotNull(runBlocking { soda().lookup(query.copy(packageName = "com.luna.music.car")) })
        assertEquals(listOf("/soda/search", "/soda/car"), seen)
    }

    @Test fun `a song with no words is marked checked, not asked again`() {
        answer("/soda/search", sodaSearch)
        answer("/soda/share", sharePage(JSONArray()))
        val source = soda()
        assertNull(runBlocking { source.lookup(query) })
        seen.clear()
        assertNull(runBlocking { source.lookup(query) })
        assertEquals(listOf("/soda/search"), seen)
    }

    @Test fun `a page without router data is a refusal`() {
        answer("/soda/search", sodaSearch)
        answer("/soda/share", "<html><body>请登录</body></html>")
        runBlocking { assertRefused(soda()) }
    }

    @Test fun `soda's own ids come out of a media uri`() {
        assertEquals("71555444", SodaLyricSource.trackId("71555444"))
        assertEquals("71555444", SodaLyricSource.trackId("offline_track_id=71555444"))
        assertEquals("", SodaLyricSource.trackId("0:254"))
        assertEquals("", SodaLyricSource.trackId(""))
    }

    @Test fun `a soda session id goes straight to its share page`() {
        answer("/soda/share", sharePage(sodaSentences))
        val fromSession = query.copy(packageName = "com.luna.music", mediaId = "71555444")
        assertNotNull(runBlocking { soda().lookup(fromSession) })
        assertEquals(listOf("/soda/share"), seen)
    }

    // ---- 咪咕 -------------------------------------------------------------------------------

    private fun migu() = MiguLyricSource(loopback, cache).apply {
        searchUrl = server.url("/migu/search").toString()
        resourceUrl = server.url("/migu/resource").toString()
    }

    private val miguSearch = """{"code":"000000","songResultData":{"resultList":[[{"name":"晴天","""" +
        """singerList":[{"name":"周杰伦"}],"duration":254,"copyrightId":"CID0001"}]]}}"""

    private val mrcText = """
        [10000,3000](10000,1500)故事(11500,1500)的小黄花
        [20000,3000](20000,3000)从出生那年就飘着
    """.trimIndent() + "\n"

    private fun miguAddress(path: String) = "http://music.migu.cn:${server.port}$path"

    private fun serveMiguResource(mrc: String = miguAddress("/migu/mrc"), lrc: String = "", trc: String = "") {
        answer("/migu/search", miguSearch)
        answer(
            "/migu/resource",
            JSONObject().put(
                "resource",
                JSONArray().put(
                    JSONObject().put("mrcUrl", mrc).put("lrcUrl", lrc).put("trcUrl", trc),
                ),
            ).toString(),
        )
    }

    @Test fun `migu signs its search and decrypts the mrc behind the record`() {
        serveMiguResource()
        answerBytes("/migu/mrc", encryptMigu(mrcText).toByteArray())
        val hit = runBlocking { migu().lookup(query) }
        assertEquals("migu:CID0001", hit?.songRef)
        assertEquals("咪咕音乐", hit?.label)
        assertTrue(hit!!.timeline.hasWordTiming)
        assertEquals(listOf("/migu/search", "/migu/resource", "/migu/mrc"), seen)
    }

    @Test fun `the search signature covers the query, the salt, the key and the timestamp`() {
        serveMiguResource()
        answerBytes("/migu/mrc", encryptMigu(mrcText).toByteArray())
        runBlocking { migu().lookup(query) }
        val search = (0 until server.requestCount).map { server.takeRequest(5, TimeUnit.SECONDS) }
            .first { it?.requestUrl?.encodedPath == "/migu/search" }!!
        val timestamp = search.getHeader("timestamp")
        assertNotNull(timestamp)
        assertNotNull(search.getHeader("sign"))
        assertEquals(
            md5("晴天 周杰伦" + SEARCH_SECRET + APP_KEY + DEVICE_ID + timestamp),
            search.getHeader("sign"),
        )
        assertEquals(DEVICE_ID, search.getHeader("deviceId"))
    }

    @Test fun `a translation on migu's own host is read too`() {
        serveMiguResource(trc = miguAddress("/migu/trc"))
        answerBytes("/migu/mrc", encryptMigu(mrcText).toByteArray())
        answer("/migu/trc", "[00:10.00]the petals fall\n[00:20.00]from the year i was born\n")
        val hit = runBlocking { migu().lookup(query) }
        assertTrue(hit!!.timeline.hasTranslation)
        assertEquals("the petals fall", hit.timeline.lines[0].translation)
    }

    @Test fun `a record that names someone else's host is not fetched`() {
        serveMiguResource(mrc = "http://elsewhere.example.com/mrc", lrc = "http://elsewhere.example.com/lrc")
        assertNull(runBlocking { migu().lookup(query) })
        assertEquals(listOf("/migu/search", "/migu/resource"), seen)
    }

    @Test fun `a plain lrc is enough when there is no mrc`() {
        serveMiguResource(mrc = "", lrc = miguAddress("/migu/lrc"))
        answer("/migu/lrc", "[00:10.00]故事的小黄花\n[00:20.00]从出生那年就飘着\n")
        val hit = runBlocking { migu().lookup(query) }
        assertNotNull(hit)
        assertFalse(hit!!.timeline.hasWordTiming)
        assertEquals(listOf("/migu/search", "/migu/resource", "/migu/lrc"), seen)
    }

    @Test fun `a business code that is not success is a miss`() {
        answer("/migu/search", """{"code":"999999","msg":"down"}""")
        assertNull(runBlocking { migu().lookup(query) })
        assertEquals(listOf("/migu/search"), seen)
    }

    @Test fun `a migu search that will not answer is a refusal`() {
        refuse("/migu/search")
        runBlocking { assertRefused(migu()) }
    }

    @Test fun `the decrypted mrc is cached and the record is not asked again`() {
        serveMiguResource()
        answerBytes("/migu/mrc", encryptMigu(mrcText).toByteArray())
        val source = migu()
        assertNotNull(runBlocking { source.lookup(query) })
        seen.clear()
        assertNotNull(runBlocking { source.lookup(query) })
        assertEquals(listOf("/migu/search"), seen)
    }

    @Test fun `a session id that is not an id is not sent as a resource`() {
        serveMiguResource()
        answerBytes("/migu/mrc", encryptMigu(mrcText).toByteArray())
        val fromSession = query.copy(packageName = "cmccwm.mobilemusic", mediaId = "0:254")
        assertNotNull(runBlocking { migu().lookup(fromSession) })
        assertEquals(1, pathsFor("/migu/search"))
    }

    @Test fun `migu reads seconds and milliseconds alike`() {
        answer(
            "/migu/search",
            """{"code":"000000","songResultData":{"resultList":[[{"name":"晴天",""" +
                """"singerList":[{"name":"周杰伦"}],"duration":254000,"copyrightId":"CID0001"}]]}}""",
        )
        answer("/migu/resource", """{"resource":[{"lrcUrl":"${miguAddress("/migu/lrc")}"}]}""")
        answer("/migu/lrc", "[00:10.00]故事的小黄花\n[00:20.00]从出生那年就飘着\n")
        assertNotNull(runBlocking { migu().lookup(query) })
    }

    @Test fun `a candidate whose duration is wrong is not asked for`() {
        answer(
            "/migu/search",
            """{"code":"000000","songResultData":{"resultList":[[{"name":"晴天","singerList":[{"name":"周杰伦"}],""" +
                """"duration":180,"copyrightId":"CID0001"}]]}}""",
        )
        assertNull(runBlocking { migu().lookup(query.copy(durationMs = 320_000L)) })
        assertEquals(listOf("/migu/search"), seen)
    }

    // ---- helpers -----------------------------------------------------------------------------

    private companion object {
        const val SEARCH_SECRET = "6cdc72a439cef99a3418d2a78aa28c73"
        const val APP_KEY = "yyapp2d16148780a1dcc7408e06336b98cfd50"
        const val DEVICE_ID = "963B7AA0D21511ED807EE5846EC87D20"
        val KRC_KEY = byteArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, -50, -46, 110, 105)
        val KUWO_KEY = "yeelion".toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
        const val TEA_DELTA = 0x9E3779B9L
        val TEA_KEY = longArrayOf(27303562373562475L, 18014862372307051L, 22799692160172081L, 34058940340699235L)

        fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray())
            .joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it.toInt() and 0xFF) }

        fun deflate(data: ByteArray): ByteArray {
            val deflater = java.util.zip.Deflater()
            deflater.setInput(data)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
            return out.toByteArray()
        }

        fun encodeKrc(krc: String): String {
            val compressed = deflate(krc.toByteArray())
            val body = ByteArray(compressed.size) { i ->
                (compressed[i].toInt() xor KRC_KEY[i % KRC_KEY.size].toInt()).toByte()
            }
            return Base64.getEncoder().encodeToString(ByteArray(4) + body)
        }

        fun encodeLrc(lrc: String): String = Base64.getEncoder().encodeToString(lrc.toByteArray())

        fun encodeKuwo(lyric: String): ByteArray {
            val plain = lyric.toByteArray()
            val xored = ByteArray(plain.size) { i ->
                (plain[i].toInt() xor KUWO_KEY[i % KUWO_KEY.size].toInt()).toByte()
            }
            return "tp=content=16646\r\n\r\n".toByteArray(java.nio.charset.StandardCharsets.US_ASCII) +
                deflate(Base64.getEncoder().encode(xored))
        }

        fun mix(current: Long, previous: Long, sum: Long, key: Long): Long =
            ((current xor sum) + (previous xor key)) xor
                (((previous shr 5) xor (current shl 2)) + ((current shr 3) xor (previous shl 4)))

        fun encryptMigu(plain: String): String {
            val utf16 = plain.toByteArray(java.nio.charset.StandardCharsets.UTF_16LE)
            val blocks = Math.max(2, (utf16.size + 7) / 8)
            val bytes = ByteArray(blocks * 8)
            utf16.copyInto(bytes)
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val data = LongArray(blocks) { buffer.getLong(it * 8) }
            var sum = 0L
            repeat(6 + 52 / blocks) {
                sum += TEA_DELTA
                val round = ((sum ushr 2) and 3L).toInt()
                for (index in 0 until blocks) {
                    val carry = data[(index + 1) % blocks]
                    val previous = data[(index - 1 + blocks) % blocks]
                    data[index] += mix(carry, previous, sum, TEA_KEY[(index and 3) xor round])
                }
            }
            return data.joinToString("") { String.format(java.util.Locale.ROOT, "%016x", it) }
        }
    }
}

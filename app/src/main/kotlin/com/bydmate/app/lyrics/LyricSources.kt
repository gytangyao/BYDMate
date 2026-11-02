package com.bydmate.app.lyrics

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * One track, as the head unit's media session describes it, handed to a catalog.
 *
 * [mediaId] is whatever id the playing app published in its session; only a catalog that is the
 * playing app itself can read it, so [NetEaseLyricSource] takes it and the others ignore it.
 *
 * [terms] is every way of asking for this song, published metadata first ([CatalogQueries]); scoring
 * runs against the term being searched, since that is the string the catalog matched.
 */
data class TrackQuery(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val mediaId: String?,
    val packageName: String,
    val terms: List<SearchTerm> = listOf(SearchTerm(title.trim(), artist.trim())),
) {
    /** Only the first term may use the session id: it is the one that id was read alongside. */
    fun mediaIdFor(index: Int): String? = if (index == 0) mediaId else null
}

/** A catalog's answer: the timeline, and who produced it for the status line and the cache key. */
data class LyricHit(
    val timeline: LyricTimeline,
    val sourceId: String,
    val label: String,
    val songRef: String,
)

/** A music catalog BYDMate can look a track up in. */
interface LyricSource {
    val id: String
    val label: String

    /** The track's timeline, or null when this catalog has nothing usable. Throws [CatalogRefused]
     *  when it did not answer at all, which is a different fact and owes the song another try.
     *
     *  [refresh] means "ask the catalog, not the disk": the background upgrade exists to catch a song
     *  whose word timings the catalog only published last month, and reading its own stored payload back
     *  would answer that with the same line-timed lyric and learn nothing.
     */
    suspend fun lookup(query: TrackQuery, refresh: Boolean = false): LyricHit?
}

/**
 * The catalog reached us and declined: a transport failure, or a 200 carrying 「操作频繁」.
 *
 * Not the same answer as a null, which means the catalog searched and the song has no words. A miss
 * is worth remembering for an hour; a refusal is worth retrying, because the same request a minute
 * later usually goes through.
 */
class CatalogRefused(override val message: String) : IllegalStateException(message)

/** Every catalog puts its keyword in a query string, and every one of them wants UTF-8 percent escapes. */
internal fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

/**
 * Response cache, keyed by the catalog's own song id.
 *
 * A cache of the raw payload rather than of a parsed timeline, so a fix to the parser reaches songs
 * already stored, and a payload with no lyric is not stored as if it were one. [maxEntries] bounds
 * the damage a long drive through a radio station's queue can do; entries past it are dropped by
 * [delete], which the caller runs after a store.
 */
class LyricDiskCache(directory: File, private val maxEntries: Int = MAX_ENTRIES) {

    private val dir = File(directory, "lyrics").apply { if (!isDirectory) mkdirs() }

    fun read(key: String): String? {
        val file = File(dir, safeName(key))
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS) {
            file.delete()
            return null
        }
        return runCatching { file.readText() }.getOrNull()
    }

    /** Only the negative cache is allowed to store an empty body; a hit always has real text. */
    fun write(key: String, payload: String) {
        if (payload.isEmpty()) return
        runCatching {
            val file = File(dir, safeName(key))
            file.parentFile?.mkdirs()
            file.writeText(payload)
            evictIfNeeded()
        }.onFailure { if (it is CancellationException) throw it }
    }

    fun writeNegative(key: String) {
        runCatching {
            val file = File(dir, safeName("neg:$key"))
            file.parentFile?.mkdirs()
            file.writeText("")
            evictIfNeeded()
        }
    }

    fun readNegative(key: String): Boolean {
        val file = File(dir, safeName("neg:$key"))
        if (!file.isFile) return false
        if (System.currentTimeMillis() - file.lastModified() > NEGATIVE_MAX_AGE_MS) {
            file.delete()
            return false
        }
        return true
    }

    /** A catalog's song id is not a safe file name: titles come from untrusted sessions. */
    private fun safeName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }
        return "$digest.json"
    }

    private fun evictIfNeeded() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= maxEntries) return
        files.take(files.size - maxEntries).forEach { it.delete() }
    }

    companion object {
        /** Three weeks: a song the driver plays again keeps its words without a lookup. */
        private const val MAX_AGE_MS = 21L * 24 * 60 * 60 * 1000
        /** A miss is re-tried after an hour, not on every repeat of a live radio title. */
        private const val NEGATIVE_MAX_AGE_MS = 60L * 60 * 1000

        /** Six catalogs' payloads plus the matched timelines: roughly a song a day for two years. */
        private const val MAX_ENTRIES = 1_200
    }
}

/** The few HTTP pieces every catalog needs, on the app's shared client. */
internal class LyricHttp(private val client: OkHttpClient) {

    private val scoped = client.newBuilder()
        .callTimeout(CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    /**
     * Kuwo's word channel is decoration — the plain lyric behind it is just as singable — so it gets
     * three seconds to connect and four to answer, once. On the shared client a network that cannot
     * reach that host would burn two retries before the endpoint that does work was asked.
     */
    private val quick = client.newBuilder()
        .connectTimeout(3_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(4_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(8_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Blocking GET/POST-form on [Dispatchers.IO]; null on any failure, cancellation rethrown. */
    suspend fun request(
        url: String,
        form: Map<String, String>? = null,
        referer: String? = null,
        origin: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String? = body(url, form, referer, origin, headers, scoped)?.let { String(it, Charsets.UTF_8) }

    /** Same, for the catalogs whose payload is a compressed binary envelope rather than text. */
    suspend fun requestBytes(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        quick: Boolean = false,
    ): ByteArray? = body(url, null, referer, null, headers, if (quick) this.quick else scoped)

    private suspend fun body(
        url: String,
        form: Map<String, String>?,
        referer: String?,
        origin: String?,
        headers: Map<String, String>,
        via: OkHttpClient,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val formBody = form?.entries?.fold(FormBody.Builder()) { acc, (key, value) -> acc.add(key, value) }?.build()
        val request = Request.Builder().url(url).apply {
            if (referer != null) header("Referer", referer)
            if (origin != null) header("Origin", origin)
            headers.forEach { (key, value) -> header(key, value) }
            header("User-Agent", USER_AGENT)
        }.let { builder ->
            if (formBody != null) builder.post(formBody) else builder.get()
        }.build()
        try {
            via.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "$url -> ${response.code}")
                    return@withContext null
                }
                response.body?.bytes()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.d(TAG, "${url.hostOf()} failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun String.hostOf(): String = runCatching { java.net.URI(this).host }.getOrDefault(this)

    companion object {
        private const val TAG = "LyricHttp"
        private const val CALL_TIMEOUT_MS = 8_000L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}

/**
 * NetEase Cloud Music: a public search for the id, then the eapi lyric call for the payload.
 *
 * The search is the same one the web client issues; the lyric request goes through [NetEaseEapi]
 * because the endpoint only answers a signed form. The payload carries the plain LRC plus, when the
 * label supplied them, a translation (`tlyric`) and word timing (`yrc`) — all three into
 * [LyricTimeline.parse].
 *
 * A session id is used only when NetEase itself is the player: the id in another app's session is
 * meaningless here, and automotive sessions often publish hex ids whose leading digits would read as
 * a song id and land the driver on an unrelated track.
 */
internal class NetEaseLyricSource(
    http: OkHttpClient,
    private val cache: LyricDiskCache,
) : LyricSource {

    private val net = LyricHttp(http)

    override val id: String get() = SOURCE_ID
    override val label: String get() = "网易云音乐"

    // Overridable so a test can answer from MockWebServer; the production values are the companions.
    internal var searchUrl = SEARCH_URL
    internal var lyricUrl = LYRIC_URL

    override suspend fun lookup(query: TrackQuery, refresh: Boolean): LyricHit? {
        val direct = if (query.packageName in NETEASE_PACKAGES) numericId(query.mediaId) else -1L
        if (direct > 0) lyricById(direct, refresh)?.let { return it }
        for (term in query.terms) {
            val searched = search(term, query.durationMs) ?: throw CatalogRefused("netease search")
            if (searched <= 0L || searched == direct) continue
            lyricById(searched, refresh)?.let { return it }
        }
        return null
    }

    /** The id to ask for: `-1` when NetEase answered and offered nothing usable, null when it refused. */
    private suspend fun search(term: SearchTerm, durationMs: Long): Long? {
        if (term.title.isBlank()) return -1L
        val withArtist = searchOne("${term.title} ${term.artist}".trim(), term, durationMs) ?: return null
        if (withArtist != -1L) return withArtist
        if (term.artist.isBlank()) return -1L
        return searchOne(term.title, term, durationMs)
    }

    /**
     * The song chosen for [term]: its id, or `-1` when the catalog answered and offered nothing
     * usable. Null means it did not answer at all, which is not a search that found nothing — asking
     * the same narrower question of a server that is failing costs a request nobody can serve.
     */
    private suspend fun searchOne(keyword: String, term: SearchTerm, durationMs: Long): Long? {
        val response = net.request(
            searchUrl,
            form = mapOf("s" to keyword, "type" to "1", "limit" to "20", "offset" to "0"),
            referer = "https://music.163.com/",
            origin = "https://music.163.com/",
        ) ?: return null
        val root = runCatching { JSONObject(response) }.getOrNull() ?: return null
        // A refusal — 405 is the one that answers a burst of lookups with 「操作频繁」 — is not a search
        // that found nothing. Reading it as one would ask the narrower question next and blacklist the
        // song behind it.
        if (root.optInt("code", 200) != 200) return null
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return -1L
        var bestScore = Int.MIN_VALUE
        var bestId = -1L
        for (index in 0 until songs.length()) {
            val song = songs.optJSONObject(index) ?: continue
            val candidateTitle = song.optString("name")
            val candidateArtist = song.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            val candidateDuration = song.optLong("duration", -1L)
            if (!TrackMatch.isPlausible(term.title, term.artist, durationMs, candidateTitle, candidateArtist, candidateDuration)) {
                continue
            }
            val score = TrackMatch.score(term.title, term.artist, durationMs, candidateTitle, candidateArtist, candidateDuration)
            if (score > bestScore) {
                bestScore = score
                bestId = song.optLong("id", -1L)
            }
        }
        return bestId.takeIf { bestScore >= TrackMatch.ACCEPT_SCORE && it > 0 } ?: -1L
    }

    private suspend fun lyricById(songId: Long, refresh: Boolean): LyricHit? {
        val key = "netease:$songId"
        if (!refresh) {
            cache.read(key)?.let { stored -> parsePayload(songId, stored)?.let { return it } }
            if (cache.readNegative(key)) return null
        }
        val body = NetEaseEapi.formBody(songId) ?: throw CatalogRefused("netease sign")
        val response = net.request(
            lyricUrl,
            form = mapOf("params" to body.removePrefix("params=")),
            referer = "https://music.163.com/",
            origin = "https://music.163.com/",
        ) ?: throw CatalogRefused("netease lyric")
        if (refused(response)) throw CatalogRefused("netease lyric refused")
        val hit = parsePayload(songId, response)
        if (hit != null) cache.write(key, response) else cache.writeNegative(key)
        return hit
    }

    /** NetEase answering without answering: a rate limit, not a song without words. */
    private fun refused(response: String): Boolean = runCatching {
        JSONObject(response).optInt("code", 200) != 200
    }.getOrDefault(true)

    private fun parsePayload(songId: Long, response: String): LyricHit? = runCatching {
        val root = JSONObject(response)
        if (root.optInt("code", 200) != 200) return null
        val timeline = LyricTimeline.parse(
            original = root.field("lrc"),
            translated = root.field("tlyric"),
            wordByWord = root.field("yrc"),
        )
        if (!timeline.isUsable()) null else LyricHit(timeline, id, label, "netease:$songId")
    }.getOrNull()

    private fun JSONObject.field(name: String): String = optJSONObject(name)?.optString("lyric").orEmpty()

    companion object {
        const val SOURCE_ID = "netease"
        private const val SEARCH_URL = "https://music.163.com/api/search/get/web"
        private const val LYRIC_URL = "https://interface3.music.163.com/eapi/song/lyric/v1"

        /** Packages whose session id is a NetEase song id. */
        private val NETEASE_PACKAGES = setOf(
            "com.netease.cloudmusic", "com.netease.cloudmusic.iot", "com.netease.cloudmusic.tv",
        )

        /** A standalone number of at least four digits; hex ids and track numbers are not song ids. */
        fun numericId(value: String?): Long {
            if (value.isNullOrBlank()) return -1L
            val match = Regex("""(?<![A-Za-z0-9])\d{4,}(?![A-Za-z0-9])""").find(value.trim()) ?: return -1L
            return match.value.toLongOrNull()?.takeIf { it > 0 } ?: -1L
        }
    }
}

/**
 * QQ Music: its search and its plain lyric endpoint.
 *
 * QQ is the catalog that carries most of the licensed Mandarin catalogue BYD head units play, and it
 * is the one NetEase has lost to copyright — a studio original frequently appears only here, whose
 * NetEase results are covers. The word-timed QRC channel is not used yet: its payload needs QQ's
 * legacy triple-DES variant, and a line-level timeline is enough to sing along.
 */
internal class QQMusicLyricSource(http: OkHttpClient, private val cache: LyricDiskCache) : LyricSource {

    private val net = LyricHttp(http)

    override val id: String get() = SOURCE_ID
    override val label: String get() = "QQ 音乐"

    // Overridable for MockWebServer-backed tests.
    internal var searchUrl = SEARCH_URL
    internal var lyricUrl = LYRIC_URL

    override suspend fun lookup(query: TrackQuery, refresh: Boolean): LyricHit? {
        for (term in query.terms) {
            val keyword = "${term.title} ${term.artist}".trim()
            if (keyword.isEmpty()) continue
            val response = net.request("$searchUrl?${QUERY_PARAMS}&w=${urlEncode(keyword)}", referer = REFERER)
                ?: throw CatalogRefused("qqmusic search")
            val list = runCatching {
                JSONObject(response).optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            }.getOrNull() ?: throw CatalogRefused("qqmusic search shape")
            var bestScore = Int.MIN_VALUE
            var bestMid = ""
            for (index in 0 until list.length()) {
                val song = list.optJSONObject(index) ?: continue
                val artist = song.optJSONArray("singer")?.optJSONObject(0)?.optString("name").orEmpty()
                val duration = song.optLong("interval", -1L) * 1_000L
                if (!TrackMatch.isPlausible(term.title, term.artist, query.durationMs, song.optString("songname"), artist, duration)) {
                    continue
                }
                val score = TrackMatch.score(term.title, term.artist, query.durationMs, song.optString("songname"), artist, duration)
                if (score > bestScore) {
                    bestScore = score
                    bestMid = song.optString("songmid")
                }
            }
            if (bestScore < TrackMatch.ACCEPT_SCORE || bestMid.isBlank()) continue
            lyricByMid(bestMid, refresh)?.let { return it }
        }
        return null
    }

    private suspend fun lyricByMid(songMid: String, refresh: Boolean): LyricHit? {
        val key = "qq:$songMid"
        if (!refresh) {
            cache.read(key)?.let { stored -> toHit(songMid, stored)?.let { return it } }
            if (cache.readNegative(key)) return null
        }
        val response = net.request(
            "$lyricUrl?songmid=${urlEncode(songMid)}&format=json&nobase64=1",
            referer = REFERER,
        ) ?: throw CatalogRefused("qqmusic lyric")
        val hit = toHit(songMid, response)
        if (hit != null) cache.write(key, response) else cache.writeNegative(key)
        return hit
    }

    private fun toHit(songMid: String, response: String): LyricHit? = runCatching {
        val root = JSONObject(response)
        // QQ answers HTML-entity-escaped text in this endpoint, and an empty payload for a VIP track.
        val original = unescape(root.optString("lyric"))
        val translated = unescape(root.optString("trans"))
        if (original.isEmpty()) return null
        val timeline = LyricTimeline.parse(original, translated)
        if (!timeline.isUsable()) null else LyricHit(timeline, id, label, "qq:$songMid")
    }.getOrNull()

    private fun unescape(value: String): String = value
        .replace("&#58;", ":").replace("&#46;", ".")
        .replace("&apos;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    companion object {
        const val SOURCE_ID = "qqmusic"
        private const val SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        private const val LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
        private const val QUERY_PARAMS = "p=1&n=10&format=json&aggr_require=1"
        private const val REFERER = "https://y.qq.com/"
    }
}

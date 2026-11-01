package com.bydmate.app.lyrics

import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * The four catalogs beyond NetEase and QQ Music.
 *
 * Each one is the catalog some player on the head unit belongs to, and each is reached the same way:
 * a public search that returns candidates, a shared scorer that decides whether any of them is the
 * song being played, then that catalog's own lyric endpoint. What differs is only how the words are
 * stored — see [WordLyricCodecs].
 *
 * They disagree about how much care the search needs, and that difference is copied deliberately:
 * Kugou and Soda trust the score alone, Kuwo additionally refuses a candidate whose duration is more
 * than fifteen seconds off or whose version markers do not agree with the track's, and Migu refuses
 * anything that fails the same plausibility test NetEase gets. A catalog that needs a gate is one
 * whose first page is full of covers.
 */

/** 酷狗音乐: hash search, then its KRC word payload and the plain LRC beside it. */
internal class KugouLyricSource(http: OkHttpClient, private val cache: LyricDiskCache) : LyricSource {

    private val net = LyricHttp(http)

    override val id: String get() = SOURCE_ID
    override val label: String get() = "酷狗音乐"

    internal var searchUrl = SEARCH_URL
    internal var lyricSearchUrl = LYRIC_SEARCH_URL
    internal var downloadUrl = DOWNLOAD_URL

    override suspend fun lookup(query: TrackQuery, refresh: Boolean): LyricHit? {
        for (term in query.terms) {
            val hash = search(term, query.durationMs) ?: throw CatalogRefused("kugou search")
            if (hash.isEmpty()) continue
            lyricByHash(hash, refresh)?.let { return it }
        }
        return null
    }

    /** The `FileHash` of the best candidate; empty for an answer that offered none, null for no answer. */
    private suspend fun search(term: SearchTerm, durationMs: Long): String? {
        if (term.title.isBlank()) return ""
        val keyword = "${term.title} ${term.artist}".trim()
        val response = net.request(
            "$searchUrl?keyword=${urlEncode(keyword)}&page=1&pagesize=10&userid=-1&clientver=&platform=WebFilter",
            referer = REFERER,
        ) ?: return null
        val root = runCatching { JSONObject(response) }.getOrNull() ?: return null
        val list = root.optJSONObject("data")?.optJSONArray("lists") ?: return ""
        var bestScore = Int.MIN_VALUE
        var bestHash = ""
        for (index in 0 until list.length()) {
            val song = list.optJSONObject(index) ?: continue
            val score = TrackMatch.score(
                term.title, term.artist, durationMs,
                song.optString("SongName"), song.optString("SingerName"),
                song.optLong("Duration", -1L) * 1_000L,
            )
            if (score > bestScore) {
                bestScore = score
                bestHash = song.optString("FileHash")
            }
        }
        return if (bestScore >= TrackMatch.ACCEPT_SCORE && bestHash.isNotBlank()) bestHash else ""
    }

    /**
     * The words of one hash. Kugou splits them across two downloads — KRC for the timings, LRC for the
     * line text — and hides the translation inside the KRC header, so the three are fetched together
     * and stored apart. `_translation_checked` records that the header was read and held nothing, so a
     * song without a translation does not get its KRC downloaded on every play.
     */
    private suspend fun lyricByHash(hash: String, refresh: Boolean): LyricHit? {
        val original = if (refresh) null else cache.read("kugou:$hash:original")
        val enhanced = if (refresh) null else cache.read("kugou:$hash:krc")
        val translated = if (refresh) null else cache.read("kugou:$hash:translated")
        val checked = !refresh && (translated != null || cache.read("kugou:$hash:translation-checked") != null)
        if (original != null && enhanced != null && checked) {
            return toHit(hash, original, translated ?: "", enhanced)
        }
        val request = lyricRequest(hash) ?: return null
        var plain = original
        var timed = enhanced
        var extra = translated ?: ""
        if (timed == null || !checked) {
            val content = download("$request&fmt=krc&charset=utf8")
            if (content != null) {
                val decrypted = WordLyricCodecs.kugouPayload(content)
                if (decrypted != null) {
                    if (timed == null) {
                        timed = WordLyricCodecs.kugouEnhanced(decrypted)
                        if (timed.isNotEmpty()) cache.write("kugou:$hash:krc", timed)
                    }
                    if (!checked) {
                        extra = WordLyricCodecs.kugouTranslation(decrypted)
                        if (extra.isNotEmpty()) cache.write("kugou:$hash:translated", extra)
                        cache.write("kugou:$hash:translation-checked", "1")
                    }
                }
            }
        }
        if (plain == null) {
            val content = download("$request&fmt=lrc&charset=utf8")
            plain = content?.let { WordLyricCodecs.kugouPlainLrc(it) } ?: ""
            if (plain.isNotEmpty()) cache.write("kugou:$hash:original", plain)
        }
        return toHit(hash, plain, extra, timed ?: "")
    }

    private suspend fun lyricRequest(hash: String): String? {
        val response = net.request("$lyricSearchUrl?ver=1&man=yes&client=pc&hash=${urlEncode(hash)}", referer = REFERER)
            ?: throw CatalogRefused("kugou lyric search")
        val root = runCatching { JSONObject(response) }.getOrNull()
            ?: throw CatalogRefused("kugou lyric search shape")
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val lyricId = candidate.optString("id")
        val accessKey = candidate.optString("accesskey")
        if (lyricId.isEmpty() || accessKey.isEmpty()) return null
        return "$downloadUrl?ver=1&client=pc&id=${urlEncode(lyricId)}&accesskey=${urlEncode(accessKey)}"
    }

    private suspend fun download(address: String): String? {
        val response = net.request(address, referer = REFERER) ?: return null
        return runCatching { JSONObject(response).optString("content") }.getOrNull()
    }

    private fun toHit(hash: String, original: String, translated: String, enhanced: String): LyricHit? {
        val timeline = LyricTimeline.parse(original, translated, enhanced)
        return if (timeline.isUsable()) LyricHit(timeline, id, label, "kugou:$hash") else null
    }

    companion object {
        const val SOURCE_ID = "kugou"
        private const val SEARCH_URL = "https://songsearch.kugou.com/song_search_v2"
        private const val LYRIC_SEARCH_URL = "https://lyrics.kugou.com/search"
        private const val DOWNLOAD_URL = "https://lyrics.kugou.com/download"
        private const val REFERER = "https://www.kugou.com/"
    }
}

/** 酷我音乐: its word channel first, the web lyric as the fallback, with a backoff in between. */
internal class KuwoLyricSource(http: OkHttpClient, private val cache: LyricDiskCache) : LyricSource {

    private val net = LyricHttp(http)

    override val id: String get() = SOURCE_ID
    override val label: String get() = "酷我音乐"

    internal var searchUrl = SEARCH_URL
    internal var wordUrl = WORD_URL
    internal var webUrl = WEB_URL

    /**
     * The word channel is the first thing asked for and the least reliable, so a network that cannot
     * reach it makes every song pay a connection timeout before the working endpoint gets a turn. Two
     * transport failures in a row close it for a quarter of an hour; a success opens it again.
     */
    private var wordChannelFailures = 0
    private var wordChannelBlockedUntil = 0L

    override suspend fun lookup(query: TrackQuery, refresh: Boolean): LyricHit? {
        val attempted = HashSet<String>()
        val direct = trackId(query.mediaId)
        if (direct.isNotEmpty()) {
            attempted.add(direct)
            lyricById(direct, refresh)?.let { return it }
        }
        var requests = 0
        for (term in query.terms) {
            for (keyword in keywords(term)) {
                val candidates = search(keyword, term, query.durationMs) ?: throw CatalogRefused("kuwo search")
                for (candidate in candidates) {
                    if (!attempted.add(candidate.first)) continue
                    // Three songs that look alike is plenty; a top page of covers should not turn into
                    // a dozen lyric downloads for one track.
                    if (requests >= MAX_CANDIDATE_REQUESTS) return null
                    requests++
                    lyricById(candidate.first, refresh)?.let { return it }
                }
            }
        }
        return null
    }

    /** The combined string first and the bare title behind it, which is what a mistyped artist needs. */
    private fun keywords(term: SearchTerm): List<String> {
        val combined = "${term.title} ${term.artist}".trim()
        return if (combined == term.title) listOf(term.title) else listOf(combined, term.title)
    }

    private suspend fun search(
        keyword: String,
        term: SearchTerm,
        durationMs: Long,
    ): List<Pair<String, Int>>? {
        if (keyword.isBlank()) return emptyList()
        val response = net.request(
            "$searchUrl?all=${urlEncode(keyword)}&ft=music&itemset=web_2013&client=kt&pn=0&rn=30" +
                "&rformat=json&encoding=utf8",
            referer = REFERER,
        ) ?: return null
        val root = runCatching { JSONObject(response) }.getOrNull() ?: return null
        val items = root.optJSONArray("abslist") ?: return emptyList()
        val result = ArrayList<Pair<String, Int>>()
        for (index in 0 until minOf(items.length(), 100)) {
            val item = items.optJSONObject(index) ?: continue
            val id = trackId(item.optString("MUSICRID"))
            val name = decode(item.optString("NAME"))
            if (id.isEmpty() || name.isEmpty()) continue
            val singer = decode(item.optString("ARTIST"))
            val seconds = item.optLong("DURATION", 0L)
            val duration = if (seconds in 1..86_400) seconds * 1_000L else -1L
            // The same guard as the other catalogs, but applied to the search list rather than to the
            // candidate: Kuwo's top page leads with live and backing-track uploads of the exact title.
            if (!TrackMatch.isPlausible(term.title, term.artist, durationMs, name, singer, duration)) continue
            val score = TrackMatch.score(term.title, term.artist, durationMs, name, singer, duration)
            if (score >= TrackMatch.ACCEPT_SCORE) result += id to score
        }
        result.sortByDescending { it.second }
        return result
    }

    /**
     * Word-timed if the channel will have it, line-timed otherwise. The two are different endpoints
     * with different shapes, not one endpoint with an optional field: `mlyric` answers a compressed
     * envelope and `m.kuwo.cn` answers json with one entry per line.
     */
    private suspend fun lyricById(id: String, refresh: Boolean): LyricHit? {
        if (!refresh) {
            cache.read("kuwo:$id:krc")?.let { stored ->
                toHit(id, "", "", stored)?.let { return it }
            }
        }
        if (wordChannelOpen()) {
            val hit = wordLyric(id)
            if (hit != null) return hit
        }
        if (!refresh) {
            cache.read("kuwo:$id:original")?.let { stored ->
                toHit(id, stored, "", "")?.let { return it }
            }
        }
        val response = net.request("$webUrl?musicId=${urlEncode(id)}", referer = REFERER)
            ?: throw CatalogRefused("kuwo web lyric")
        val root = runCatching { JSONObject(response) }.getOrNull() ?: throw CatalogRefused("kuwo web shape")
        // A status other than 200 is Kuwo saying it could not look this song up, not that the song has
        // no words; treating it as a miss would park the track behind an hour of negative cache.
        if (root.optInt("status", -1) != 200) throw CatalogRefused("kuwo status ${root.optInt("status")}")
        val plain = lines(root.optJSONObject("data")?.optJSONArray("lrclist"))
        if (plain.isNotEmpty()) cache.write("kuwo:$id:original", plain)
        return toHit(id, plain, "", "")
    }

    /** null when the channel answered and had nothing, refusal counted as a closed channel. */
    private suspend fun wordLyric(id: String): LyricHit? {
        val bytes = net.requestBytes(
            "$wordUrl/mobi.s?f=web&type=lyric&lrcx=1&rid=${urlEncode(id)}&encode=utf8",
            referer = REFERER,
            quick = true,
        )
        if (bytes == null) {
            closeWordChannel()
            return null
        }
        // Reaching the endpoint is the proof it works; an answer with no words in it is not a failure.
        wordChannelFailures = 0
        wordChannelBlockedUntil = 0L
        val enhanced = WordLyricCodecs.kuwoEnvelope(bytes)
            ?.let { WordLyricCodecs.kuwoEnhanced(it) }
            .orEmpty()
        if (enhanced.isEmpty()) return null
        cache.write("kuwo:$id:krc", enhanced)
        return toHit(id, "", "", enhanced)
    }

    private fun wordChannelOpen(): Boolean =
        wordChannelBlockedUntil <= 0L || SystemClock.elapsedRealtime() >= wordChannelBlockedUntil

    private fun closeWordChannel() {
        wordChannelFailures = minOf(FAILURES_BEFORE_BACKOFF, wordChannelFailures + 1)
        if (wordChannelFailures >= FAILURES_BEFORE_BACKOFF) {
            wordChannelBlockedUntil = SystemClock.elapsedRealtime() + WORD_CHANNEL_BACKOFF_MS
            Log.d(TAG, "kuwo word channel closed for ${WORD_CHANNEL_BACKOFF_MS}ms")
        }
    }

    private fun toHit(songRef: String, original: String, translated: String, enhanced: String): LyricHit? {
        val timeline = LyricTimeline.parse(original, translated, enhanced)
        return if (timeline.isUsable()) LyricHit(timeline, id, label, "kuwo:$songRef") else null
    }

    companion object {
        const val SOURCE_ID = "kuwo"
        private const val TAG = "KuwoLyricSource"
        private const val SEARCH_URL = "https://search.kuwo.cn/r.s"

        /** `mobi.s` sits directly on the host, so the base is the origin only. */
        private const val WORD_URL = "http://mlyric.kuwo.cn"
        private const val WEB_URL = "https://m.kuwo.cn/newh5/singles/songinfoandlrc"
        private const val REFERER = "https://www.kuwo.cn/"
        private const val MAX_CANDIDATE_REQUESTS = 3
        private const val FAILURES_BEFORE_BACKOFF = 2
        private const val WORD_CHANNEL_BACKOFF_MS = 15L * 60_000L

        /** Kuwo's own ids, and the session id of its app, both carry the `MUSIC_` prefix. */
        fun trackId(value: String?): String {
            var id = value?.trim().orEmpty()
            if (id.startsWith("MUSIC_")) id = id.substring(6)
            if (!id.matches(Regex("""[0-9]{1,18}"""))) return ""
            return id.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: ""
        }

        /** Kuwo escapes its search results twice over: `\u0026` for `&`, then the html entities. */
        fun decode(value: String): String = value
            .replace(Regex("""\\+u0026"""), "&")
            .replace("&nbsp;", " ").replace("&apos;", "'")
            .replace("&#39;", "'").replace("&quot;", "\"")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .trim()

        /** `data.lrclist` is one object per line with its start in fractional seconds. */
        fun lines(items: JSONArray?): String {
            if (items == null) return ""
            val builder = StringBuilder()
            for (index in 0 until minOf(items.length(), 10_000)) {
                val line = items.optJSONObject(index) ?: continue
                val seconds = line.optDouble("time", -1.0)
                if (seconds.isNaN() || seconds.isInfinite() || seconds < 0 || seconds > 86_400) continue
                val text = line.optString("lineLyric").replace('\r', ' ').replace('\n', ' ').trim()
                if (text.isEmpty()) continue
                val millis = Math.round(seconds * 1000)
                builder.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]", millis / 60_000L, millis / 1_000L % 60L, millis % 1_000L))
                    .append(text).append('\n')
            }
            return builder.toString()
        }
    }
}

/** 汽水音乐: a search, then the public share page, which carries the word timings natively. */
internal class SodaLyricSource(http: OkHttpClient, private val cache: LyricDiskCache) : LyricSource {

    private val net = LyricHttp(http)

    override val id: String get() = SOURCE_ID
    override val label: String get() = "汽水音乐"

    internal var searchUrl = SEARCH_URL
    internal var shareUrl = MOBILE_SHARE_URL
    internal var carShareUrl = CAR_SHARE_URL

    override suspend fun lookup(query: TrackQuery, refresh: Boolean): LyricHit? {
        val direct = trackId(query.mediaId ?: "")
        if (direct.isNotEmpty()) {
            loadById(query.packageName, direct, refresh)?.let { return it }
        }
        for (term in query.terms) {
            if (term.title.isBlank()) continue
            val searched = search(term, query.durationMs) ?: throw CatalogRefused("soda search")
            if (searched.isEmpty() || searched == direct) continue
            loadById(query.packageName, searched, refresh)?.let { return it }
        }
        return null
    }

    private suspend fun search(term: SearchTerm, durationMs: Long): String? {
        val keyword = "${term.title} ${term.artist}".trim()
        val response = net.request(
            "$searchUrl?q=${urlEncode(keyword)}&cursor=0&search_method=input&aid=386088" +
                "&device_platform=web&channel=pc_web",
            referer = REFERER,
        ) ?: return null
        val root = runCatching { JSONObject(response) }.getOrNull() ?: return null
        val groups = root.optJSONArray("result_groups") ?: return ""
        var bestScore = Int.MIN_VALUE
        var bestId = ""
        for (group in 0 until groups.length()) {
            val data = groups.optJSONObject(group)?.optJSONArray("data") ?: continue
            for (index in 0 until data.length()) {
                val track = data.optJSONObject(index)?.optJSONObject("entity")?.optJSONObject("track") ?: continue
                val trackId = track.optString("id")
                if (trackId.isEmpty()) continue
                val score = TrackMatch.score(
                    term.title, term.artist, durationMs,
                    track.optString("name"), artists(track.optJSONArray("artists")),
                    track.optLong("duration", -1L),
                )
                if (score > bestScore) {
                    bestScore = score
                    bestId = trackId
                }
            }
        }
        return if (bestScore >= TrackMatch.ACCEPT_SCORE) bestId else ""
    }

    /**
     * Soda has no public lyric endpoint; the share page is the lyric endpoint. Its embedded router
     * state holds the sentences with their words, so one request gives lines, words and translation —
     * and the page is a few hundred kilobytes of html, so only the three extracted payloads are kept.
     */
    private suspend fun loadById(packageName: String, id: String, refresh: Boolean): LyricHit? {
        val prefix = "soda:$id"
        val enhanced = if (refresh) null else cache.read("$prefix:krc")
        val original = if (refresh) null else cache.read("$prefix:original")
        val translated = if (refresh) null else cache.read("$prefix:translated")
        if (original != null || enhanced != null) {
            toHit(id, original ?: "", translated ?: "", enhanced ?: "")?.let { return it }
        }
        if (!refresh && cache.read("$prefix:checked") != null) return null
        val address = (if (packageName == CAR_PACKAGE) carShareUrl else shareUrl) + urlEncode(id)
        val referer = if (packageName == CAR_PACKAGE) CAR_REFERER else REFERER
        val html = net.request(address, referer = referer) ?: throw CatalogRefused("soda share page")
        val payload = parseSharePage(html) ?: throw CatalogRefused("soda share shape")
        if (payload.third.isNotEmpty()) cache.write("$prefix:krc", payload.third)
        if (payload.first.isNotEmpty()) cache.write("$prefix:original", payload.first)
        if (payload.second.isNotEmpty()) cache.write("$prefix:translated", payload.second)
        cache.write("$prefix:checked", "1")
        return toHit(id, payload.first, payload.second, payload.third)
    }

    private fun toHit(id: String, original: String, translated: String, enhanced: String): LyricHit? {
        val timeline = LyricTimeline.parse(original, translated, enhanced)
        return if (timeline.isUsable()) LyricHit(timeline, this.id, label, "soda:$id") else null
    }

    /** lines, translation, word timings — in that order, all empty when the page carries no lyrics. */
    private fun parseSharePage(html: String): Triple<String, String, String>? {
        val router = routerData(html) ?: return null
        val sentences = router.optJSONObject("loaderData")
            ?.optJSONObject("track_page")?.optJSONObject("audioWithLyricsOption")
            ?.optJSONObject("lyrics")?.optJSONArray("sentences") ?: return null
        val original = StringBuilder()
        val translated = StringBuilder()
        val enhanced = StringBuilder()
        for (index in 0 until sentences.length()) {
            val sentence = sentences.optJSONObject(index) ?: continue
            val start = sentence.optLong("startMs", -1L)
            if (start < 0) continue
            val end = Math.max(start, sentence.optLong("endMs", start))
            val words = sentence.optJSONArray("words")
            var text = sentence.optString("text").trim()
            if (text.isEmpty()) text = wordsText(words).trim()
            if (text.isEmpty()) continue
            original.append(lrcStamp(start)).append(text).append('\n')
            val extra = sentence.optString("translation").ifBlank { sentence.optString("translatedText") }
            if (extra.isNotBlank()) translated.append(lrcStamp(start)).append(extra.trim()).append('\n')
            if (words != null && words.length() > 0) {
                val timing = StringBuilder()
                for (at in 0 until words.length()) {
                    val word = words.optJSONObject(at) ?: continue
                    val wordText = word.optString("text")
                    if (wordText.isEmpty()) continue
                    val wordStart = Math.max(start, word.optLong("startMs", start))
                    val wordEnd = Math.max(wordStart, word.optLong("endMs", end))
                    timing.append('(').append(wordStart).append(',').append(wordEnd - wordStart).append(",0)").append(wordText)
                }
                if (timing.isNotEmpty()) {
                    enhanced.append('[').append(start).append(',').append(end - start).append(']').append(timing).append('\n')
                }
            }
        }
        return Triple(original.toString(), translated.toString(), enhanced.toString())
    }

    /** `window._ROUTER_DATA = {…}` inside the page: the assignment is found, then its braces are paired. */
    private fun routerData(html: String): JSONObject? {
        val marker = html.indexOf(ROUTER_DATA)
        if (marker < 0) return null
        val equals = html.indexOf('=', marker + ROUTER_DATA.length)
        if (equals < 0) return null
        val start = html.indexOf('{', equals + 1)
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until html.length) {
            val character = html[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
                continue
            }
            when (character) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching { JSONObject(html.substring(start, index + 1)) }.getOrNull()
            }
        }
        return null
    }

    private fun wordsText(words: JSONArray?): String {
        if (words == null) return ""
        val builder = StringBuilder()
        for (index in 0 until words.length()) builder.append(words.optJSONObject(index)?.optString("text").orEmpty())
        return builder.toString()
    }

    private fun artists(artists: JSONArray?): String {
        if (artists == null) return ""
        val names = ArrayList<String>()
        for (index in 0 until artists.length()) {
            artists.optJSONObject(index)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
        }
        return names.joinToString(", ")
    }

    companion object {
        const val SOURCE_ID = "soda"
        private const val SEARCH_URL = "https://api.qishui.com/luna/pc/search/track"
        private const val MOBILE_SHARE_URL = "https://www.qishui.com/share/track?track_id="
        private const val CAR_SHARE_URL = "https://music.douyin.com/qishui/share/track?track_id="
        private const val CAR_PACKAGE = "com.luna.music.car"
        private const val REFERER = "https://www.qishui.com/"
        private const val CAR_REFERER = "https://music.douyin.com/"
        private const val ROUTER_DATA = "_ROUTER_DATA"

        /** Soda's ids are long numbers, sometimes wrapped in a media uri. */
        fun trackId(value: String): String =
            Regex("""(?:^|\D)(\d{6,})(?:$|\D)""").find(value)?.groupValues?.get(1).orEmpty()

        private fun lrcStamp(timeMs: Long): String = String.format(
            Locale.ROOT, "[%02d:%02d.%03d]", timeMs / 60_000L, timeMs / 1_000L % 60L, timeMs % 1_000L,
        )
    }
}

/** 咪咕音乐: a signed search, then an encrypted MRC line-up behind the resource record. */
internal class MiguLyricSource(http: OkHttpClient, private val cache: LyricDiskCache) : LyricSource {

    private val net = LyricHttp(http)

    override val id: String get() = SOURCE_ID
    override val label: String get() = "咪咕音乐"

    internal var searchUrl = SEARCH_URL
    internal var resourceUrl = RESOURCE_URL

    override suspend fun lookup(query: TrackQuery, refresh: Boolean): LyricHit? {
        val direct = validId(query.mediaId ?: "")
        if (direct.isNotEmpty()) {
            // A resource record that will not answer is not the catalog refusing the song: the search
            // below asks the same question of the same service and gets a fairer chance.
            val hit = try {
                byResourceId(direct, refresh)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (hit != null) return hit
        }
        for ((index, term) in query.terms.withIndex()) {
            if (term.title.isBlank()) continue
            val best = search(term, query.durationMs) ?: throw CatalogRefused("migu search")
            if (best.isEmpty() || (index == 0 && best == direct)) continue
            byResourceId(best, refresh)?.let { return it }
        }
        return null
    }

    private suspend fun search(term: SearchTerm, durationMs: Long): String? {
        val keyword = "${term.title} ${term.artist}".trim()
        val now = System.currentTimeMillis()
        val sign = runCatching {
            hex(MessageDigest.getInstance("MD5").digest(
                (keyword + SEARCH_SECRET + APP_KEY + DEVICE + now).toByteArray(StandardCharsets.UTF_8),
            ))
        }.getOrNull() ?: return null
        val response = net.request(
            "$searchUrl?isCorrect=0&isCopyright=1&searchSwitch=" +
                urlEncode("""{"song":1,"album":0,"singer":0,"tagSong":1,"mv":0,"bestShow":1,"songlist":0,"lyricSong":0}""") +
                "&pageSize=20&text=${urlEncode(keyword)}&pageNo=1&sort=0&sid=USS",
            referer = REFERER,
            headers = mapOf(
                "uiVersion" to "A_music_3.6.1",
                "deviceId" to DEVICE,
                "timestamp" to now.toString(),
                "sign" to sign,
                "channel" to "0146921",
            ),
        ) ?: return null
        val root = runCatching { JSONObject(response) }.getOrNull() ?: return null
        if (root.optString("code") != "000000") return ""
        val groups = root.optJSONObject("songResultData")?.optJSONArray("resultList") ?: return ""
        var bestScore = Int.MIN_VALUE
        var bestId = ""
        for (group in 0 until groups.length()) {
            val songs = groups.optJSONArray(group) ?: continue
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val candidateId = validId(song.optString("copyrightId"))
                if (candidateId.isEmpty()) continue
                val candidateTitle = song.optString("name")
                val candidateArtist = song.optJSONArray("singerList")?.optJSONObject(0)
                    ?.optString("name").orEmpty()
                var candidateDuration = song.optLong("duration", -1L)
                // Migu reports seconds for some records and milliseconds for others; anything under a
                // minute of milliseconds is a track that has not been released yet, so seconds it is.
                if (candidateDuration in 1..19_999) candidateDuration *= 1_000L
                if (!TrackMatch.isPlausible(term.title, term.artist, durationMs, candidateTitle, candidateArtist, candidateDuration)) {
                    continue
                }
                val score = TrackMatch.score(term.title, term.artist, durationMs, candidateTitle, candidateArtist, candidateDuration)
                if (score > bestScore) {
                    bestScore = score
                    bestId = candidateId
                }
            }
        }
        return if (bestScore >= TrackMatch.ACCEPT_SCORE) bestId else ""
    }

    /**
     * The resource record names three lyric files: the encrypted word-timed MRC, a plain LRC and a
     * translation. Only Migu's own hosts are fetched — the record comes from an API, and following a
     * url it invented is not something a car app should do.
     */
    private suspend fun byResourceId(id: String, refresh: Boolean): LyricHit? {
        if (!refresh) {
            cache.read("migu:$id:mrc")?.let { stored ->
                toHit(id, "", "", stored)?.let { return it }
            }
        }
        val response = net.request(
            "$resourceUrl?resourceType=2",
            form = mapOf("resourceId" to id),
            referer = REFERER,
        ) ?: throw CatalogRefused("migu resource")
        val root = runCatching { JSONObject(response) }.getOrNull()
            ?: throw CatalogRefused("migu resource shape")
        val song = root.optJSONArray("resource")?.optJSONObject(0) ?: return null
        val mrcUrl = song.optString("mrcUrl")
        val lrcUrl = song.optString("lrcUrl")
        val translationUrl = song.optString("trcUrl")
        if (mrcUrl.isEmpty() && lrcUrl.isEmpty()) return null
        var translation = ""
        if (isMiguHost(translationUrl)) {
            translation = net.request(translationUrl, referer = REFERER) ?: ""
        }
        if (isMiguHost(mrcUrl)) {
            val encrypted = net.request(mrcUrl, referer = REFERER)
            if (encrypted != null) {
                val enhanced = WordLyricCodecs.miguEnhanced(WordLyricCodecs.miguDecrypt(encrypted))
                val hit = toHit(id, "", translation, enhanced)
                if (hit != null) {
                    cache.write("migu:$id:mrc", enhanced)
                    return hit
                }
            }
        }
        if (!isMiguHost(lrcUrl)) return null
        val plain = net.request(lrcUrl, referer = REFERER) ?: return null
        return toHit(id, plain, translation, "")
    }

    private fun toHit(id: String, original: String, translated: String, enhanced: String): LyricHit? {
        val timeline = LyricTimeline.parse(original, translated, enhanced)
        return if (timeline.isUsable()) LyricHit(timeline, this.id, label, "migu:$id") else null
    }

    companion object {
        const val SOURCE_ID = "migu"
        private const val SEARCH_URL = "https://jadeite.migu.cn/music_search/v3/search/searchAll"
        private const val RESOURCE_URL = "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do"
        private const val REFERER = "https://app.c.nf.migu.cn/"
        private const val DEVICE = "963B7AA0D21511ED807EE5846EC87D20"
        private const val APP_KEY = "yyapp2d16148780a1dcc7408e06336b98cfd50"

        /** The salt the Migu app puts between the query and its own key; not a secret, its client ships it. */
        private const val SEARCH_SECRET = "6cdc72a439cef99a3418d2a78aa28c73"

        fun validId(value: String?): String =
            value?.trim()?.takeIf { it.matches(Regex("""[A-Za-z0-9]{4,48}""")) }.orEmpty()

        /** A search result is a hex string of 16-character words; anything else is not a MRC. */
        private fun isMiguHost(address: String): Boolean = runCatching {
            val uri = URI(address)
            val host = uri.host ?: return false
            val scheme = uri.scheme.lowercase(Locale.ROOT)
            (scheme == "https" || scheme == "http") && (host == "migu.cn" || host.endsWith(".migu.cn"))
        }.getOrDefault(false)

        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            for (byte in bytes) append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xFF))
        }
    }
}

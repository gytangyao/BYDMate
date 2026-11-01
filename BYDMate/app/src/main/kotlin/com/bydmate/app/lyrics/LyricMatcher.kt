package com.bydmate.app.lyrics

import android.content.Context
import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One track, all catalogs.
 *
 * A single Chinese catalog is not enough: the licensed studio original of a song is on QQ while
 * NetEase's first page is covers, and the other way round for independents. Six catalogs are asked, in
 * an order that puts the playing app's own catalog first — its session id is the shortest path and its
 * words are the ones that app will sing. That one is asked alone and serially; the other five are
 * woken together and their answers are still read in precedence order, so a slower, worse catalog can
 * never beat a faster, better one, and the whole track costs at most one lookup's latency.
 *
 * A song is searched under several strings ([CatalogQueries]) — the published metadata, then a
 * filename-cleaned form, then Simplified variants of both — and every catalog works through the list
 * in order, stopping at the first answer.
 *
 * Two caches sit in front of the network. [MatchedLyricCache] remembers the timeline a search
 * *produced*, keyed by the song rather than by the catalog's id, so replaying a track needs no request
 * at all; behind it each catalog keeps its raw payload, which is what a re-parse reads. A remembered
 * timeline that has no word timings is upgraded on one background thread — the driver gets the line-
 * timed words now and the karaoke ones a second later, and switching songs stops the upgrade.
 *
 * Nothing here decides what the screen shows: [LyricHit] carries who answered, so the engine can say
 * it out loud. A catalog that will not talk — a rate limit, a dead endpoint — is caught here rather
 * than thrown, and reported as `answered = false` so the caller can tell it from a song without words.
 */
@Singleton
class LyricMatcher @Inject constructor(
    @ApplicationContext context: Context,
    httpClient: OkHttpClient,
) {

    private val disk = LyricDiskCache(context.cacheDir)

    // Internal so a test can point every catalog at MockWebServer; nothing else rebinds them.
    internal val netEase = NetEaseLyricSource(httpClient, disk)
    internal val qqMusic = QQMusicLyricSource(httpClient, disk)
    internal val kugou = KugouLyricSource(httpClient, disk)
    internal val kuwo = KuwoLyricSource(httpClient, disk)
    internal val soda = SodaLyricSource(httpClient, disk)
    internal val migu = MiguLyricSource(httpClient, disk)

    private val byId: Map<String, LyricSource> =
        listOf(netEase, qqMusic, kugou, kuwo, soda, migu).associateBy { it.id }

    private val matched = MatchedLyricCache(disk)

    /** One coroutine context for the whole app: an upgrade is worth doing, not worth competing with a lookup. */
    private val upgradeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var upgradeJob: Job? = null

    /**
     * The timeline of [query], and whether any catalog was willing to talk about it at all.
     *
     * `hit == null, answered = true` is the answer the driver wants remembered: the song has no words.
     * `answered = false` means every catalog refused — a rate limit, a dead endpoint, a plane landing —
     * and the track is still owed a lookup, so [LyricsDisplayEngine] asks again after a wait instead of
     * blanking the song for the rest of the drive.
     *
     * [preferredSource] is the catalog the playing app itself belongs to, which goes to the front of the
     * queue and is the only one handed the session's media id.
     */
    suspend fun match(query: TrackQuery, preferredSource: String? = null): MatchOutcome {
        val terms = CatalogQueries.terms(query)
        if (terms.isEmpty()) return MatchOutcome(null, true)
        val requested = query.copy(terms = terms)
        cancelPendingUpgrade()
        val startedAt = System.currentTimeMillis()
        val plan = plan(preferredSource)
        fromMatchedCache(requested, preferredSource, plan)?.let {
            return MatchOutcome(it, true)
        }
        val outcome = try {
            searchAll(requested, preferredSource, plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "lookup failed: ${e.javaClass.simpleName}")
            MatchOutcome(null, false)
        }
        if (outcome.hit == null && outcome.answered) {
            disk.writeNegative(
                TrackMatch.trackKey(requested.packageName, requested.title, requested.artist, requested.durationMs),
            )
        }
        outcome.hit?.let {
            Trace.event(
                TraceArea.NET, "lyric_match",
                "source" to it.sourceId,
                "lines" to it.timeline.size,
                "words" to it.timeline.hasWordTiming,
                "ms" to (System.currentTimeMillis() - startedAt),
            )
        }
        return outcome
    }

    private suspend fun searchAll(
        query: TrackQuery,
        preferred: String?,
        plan: CatalogPlan,
    ): MatchOutcome = coroutineScope {
        var answered = false
        for (term in query.terms) {
            val scoped = query.copy(terms = listOf(term))
            for (id in plan.priority) {
                val answer = await(ask(id, scoped, preferred))
                answered = answered || answer.answered
                val hit = answer.hit
                if (hit != null) {
                    remember(hit, query, preferred)
                    return@coroutineScope MatchOutcome(hit, true)
                }
            }
            // The rest run at once, but only an earlier catalog can beat a later one.
            val rest = plan.ordered.filterNot { it in plan.priority }
            val inFlight = rest.map { id -> id to ask(id, scoped, preferred) }
            for ((id, job) in inFlight) {
                val answer = await(job)
                answered = answered || answer.answered
                val hit = answer.hit
                if (hit != null) {
                    inFlight.forEach { (_, pending) -> pending.cancel() }
                    remember(hit, query, preferred)
                    return@coroutineScope MatchOutcome(hit, true)
                }
            }
            inFlight.forEach { (_, pending) -> pending.cancel() }
        }
        MatchOutcome(null, answered)
    }

    /** One catalog, asked so that a refusal is a fact about the catalog and not an exception. */
    private fun CoroutineScope.ask(id: String, query: TrackQuery, preferred: String?): Deferred<CatalogAnswer> =
        async {
            try {
                Replied(byId.getValue(id).lookup(query.forCatalog(id, preferred)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "$id refused ${query.title}")
                Refused
            }
        }

    /**
     * Only the catalog the player belongs to may read the session id. An id from another app's session
     * means nothing here, and the hex ids automotive sessions publish carry leading digits that read as
     * a plausible song id and land the driver on an unrelated track.
     */
    private fun TrackQuery.forCatalog(id: String, preferred: String?): TrackQuery =
        if (id == preferred) this else copy(mediaId = null)

    /** Out of time is the same fact as a refusal: nothing was said about this track. */
    private suspend fun await(job: Deferred<CatalogAnswer>): CatalogAnswer =
        withTimeoutOrNull(LOOKUP_BUDGET_MS) { job.await() } ?: Refused

    /**
     * A remembered timeline, or null. Reading all six before issuing any request is deliberate: a cache
     * written by the catalog that is not first in line still answers, and the driver gets its words on
     * the first frame instead of after a round trip.
     */
    private suspend fun fromMatchedCache(query: TrackQuery, preferred: String?, plan: CatalogPlan): LyricHit? =
        withContext(Dispatchers.IO) {
            var answer: LyricHit? = null
            for (id in plan.ordered) {
                val source = byId[id] ?: continue
                val directId = if (id == preferred) query.mediaId.orEmpty() else ""
                val key = MatchedLyricCache.key(
                    id, query.title, query.artist, query.durationMs, directId, query.packageName,
                )
                val cached = matched.read(key)
                if (cached.empty) continue
                Log.d(TAG, "matched cache hit $id")
                if (matched.needsUpgrade(key, id, cached)) upgrade(key, id, query, preferred, cached)
                answer = LyricHit(cached, id, source.label, "cached:$id")
                break
            }
            answer
        }

    /** Ask [id] again behind the answer already on screen, and keep whichever is the better lyric. */
    private fun upgrade(key: String, id: String, query: TrackQuery, preferred: String?, cached: LyricTimeline) {
        matched.markUpgradeChecked(key)
        val source = byId[id] ?: return
        // The upgrade has to reach the catalog itself: the raw payload this source cached last time is
        // the very line-timed lyric on screen, and reading it back could never find the words that were
        // published since.
        upgradeJob = upgradeScope.launch {
            try {
                val scoped = query.copy(terms = listOf(SearchTerm(query.title.trim(), query.artist.trim())))
                val upgraded = source.lookup(scoped.forCatalog(id, preferred), refresh = true) ?: return@launch
                if (upgraded.timeline.qualityScore() > cached.qualityScore()) {
                    matched.write(key, upgraded.timeline)
                    Log.d(TAG, "$id upgraded to ${upgraded.timeline.size} lines")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "$id upgrade failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** A new song means the old song's upgrade is no longer worth its bandwidth. */
    private fun cancelPendingUpgrade() {
        upgradeJob?.cancel()
        upgradeJob = null
    }

    private fun remember(hit: LyricHit, query: TrackQuery, preferred: String?) {
        val key = MatchedLyricCache.key(
            hit.sourceId, query.title, query.artist, query.durationMs,
            if (hit.sourceId == preferred) query.mediaId.orEmpty() else "", query.packageName,
        )
        matched.write(key, hit.timeline)
    }

    /**
     * The order to ask: what the player owns goes first and is asked alone before the others are woken,
     * and the rest keep the default precedence.
     */
    private fun plan(preferredSource: String?): CatalogPlan {
        val priority = listOfNotNull(preferredSource?.takeIf { it in byId })
        return CatalogPlan(priority + DEFAULT_ORDER.filterNot { it in priority }, priority)
    }

    fun labelOf(sourceId: String): String = byId[sourceId]?.label ?: sourceId

    /** The catalog of a playing app, when it publishes to one we can ask. */
    fun sourceForPackage(packageName: String): String? = when {
        packageName in NETEASE_PACKAGES -> NetEaseLyricSource.SOURCE_ID
        packageName in QQ_PACKAGES -> QQMusicLyricSource.SOURCE_ID
        packageName in KUGOU_PACKAGES -> KugouLyricSource.SOURCE_ID
        packageName in KUWO_PACKAGES -> KuwoLyricSource.SOURCE_ID
        packageName in SODA_PACKAGES -> SodaLyricSource.SOURCE_ID
        packageName in MIGU_PACKAGES -> MiguLyricSource.SOURCE_ID
        else -> null
    }

    internal companion object {
        const val TAG = "LyricMatcher"

        /** One search plus one lyric call per catalog, with the app's own client timing out at 8 s. */
        const val LOOKUP_BUDGET_MS = 20_000L

        /** The order the reference asks them in: the two biggest catalogues, then the word-timed ones. */
        val DEFAULT_ORDER = listOf("netease", "qqmusic", "kugou", "kuwo", "soda", "migu")

        val NETEASE_PACKAGES = setOf(
            "com.netease.cloudmusic", "com.netease.cloudmusic.iot", "com.netease.cloudmusic.tv",
        )
        val QQ_PACKAGES = setOf(
            "com.tencent.qqmusic", "com.tencent.qqmusiccar", "com.tencent.qqmusictv",
        )
        val KUGOU_PACKAGES = setOf("com.kugou.android", "com.kugou.android.auto", "com.kugou.android.tv")
        val KUWO_PACKAGES = setOf("cn.kuwo.player", "cn.kuwo.kwmusiccar", "cn.kuwo.player.car")
        val SODA_PACKAGES = setOf("com.luna.music", "com.luna.music.car")

        /** Migu's phone app; the car builds ship under other ids nobody has confirmed on a head unit. */
        val MIGU_PACKAGES = setOf("cmccwm.mobilemusic")
    }
}

/**
 * What all the catalogs together said about one track: the words if they had them, and whether any of
 * them was reached at all. The difference matters downstream — a miss is remembered, a refusal is
 * retried.
 */
data class MatchOutcome(val hit: LyricHit?, val answered: Boolean)

/** Which catalogs to ask, and which of them are asked before the rest are woken. */
private class CatalogPlan(val ordered: List<String>, val priority: List<String>)

/** One catalog's side of a [MatchOutcome]. */
private sealed interface CatalogAnswer {
    /** False is the refusal: nothing was said about this track, so it is still owed a lookup. */
    val answered: Boolean get() = false

    val hit: LyricHit? get() = null
}

/** The catalog answered; [hit] is null when it has no words for this track. */
private data class Replied(override val hit: LyricHit?) : CatalogAnswer {
    override val answered: Boolean get() = true
}

/** The catalog refused, threw, or took longer than the lookup budget. */
private object Refused : CatalogAnswer

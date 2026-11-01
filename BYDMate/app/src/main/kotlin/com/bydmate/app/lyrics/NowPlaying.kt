package com.bydmate.app.lyrics

/**
 * What the head unit is playing right now, read from the media sessions of every app — which is the
 * only way to know: BYDMate plays nothing itself, and the stock players, a Bluetooth stream from a
 * phone and a radio app all publish through the same API.
 *
 * [NowPlayingPick] decides the one session that owns the lyric screen. Unlike the instrument
 * cluster's music card, this must not skip the whitelisted players or Bluetooth: here they are the
 * normal case, and `com.android.bluetooth`'s AVRCP metadata is the only trace of a song playing from
 * a phone.
 */
data class SessionInfo(
    val packageName: String,
    /** A `PlaybackState.STATE_*` value; null when the session reports no state. */
    val playbackState: Int?,
    val title: String?,
    val artist: String?,
    val album: String? = null,
    val mediaId: String? = null,
    /** Position already advanced to "now" by the reader; null when the session gives none. */
    val positionMs: Long? = null,
    /** `METADATA_KEY_DURATION`; null or <= 0 when unknown. */
    val durationMs: Long? = null,
    val speed: Float = 1f,
    /** The session's artwork (`METADATA_KEY_ART`, its album-art twin, or the description's icon),
     *  already scaled and JPEG-encoded for the wire; null when the player publishes none. A [String]
     *  rather than a bitmap so the whole pipeline stays JVM-pure. */
    val coverBase64: String? = null,
)

/** The chosen session, in the shape the lyric pipeline needs. */
data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String?,
    val mediaId: String?,
    /** Null when the player does not publish a duration (live radio, some Bluetooth sources). */
    val durationMs: Long?,
    /** Position within the track at [atUptimeMs], already extrapolated by the reader. */
    val positionMs: Long,
    /** False for a source that publishes no position (some Bluetooth streams, live radio): the
     *  display then shows the title and waits instead of parking on the first line. */
    val positionKnown: Boolean,
    val playing: Boolean,
    val speed: Float,
    /** `SystemClock.elapsedRealtime()` the position belongs to. */
    val atUptimeMs: Long,
    /** The playing app's artwork for this track, JPEG-encoded and scaled, ready for the wire. */
    val coverBase64: String? = null,
) {
    /** Stable across polls of the same song, so a lookup happens once per track. */
    val key: String get() = TrackMatch.trackKey(packageName, title, artist, durationMs ?: 0L)

    val searchQuery: TrackQuery
        get() = TrackQuery(
            title = title,
            artist = artist,
            durationMs = durationMs ?: -1L,
            mediaId = mediaId,
            packageName = packageName,
        )
}

/** Picks which session the lyric screen follows. Pure, so the ownership rules are JVM-tested. */
object NowPlayingPick {

    // PlaybackState.STATE_* as literals, the way the media package does it: android.jar members are
    // stubs on the JVM.
    private const val STATE_PLAYING = 3
    private const val STATE_PAUSED = 2
    private const val STATE_BUFFERING = 6

    /**
     * Sessions that are not music: a call holds a titled session while it rings, and the assistant's
     * own session outlives the prompt it played. They must never steal the lyric screen.
     */
    private val NOISE_PREFIXES = listOf(
        "com.android.server.telecom", "com.android.incallui", "com.android.dialer",
        "com.byd.autovoice", "com.byd.vrassistant", "com.baidu.duershow",
    )

    fun pick(sessions: List<SessionInfo>, lastOwner: String? = null, nowUptimeMs: Long = 0L): NowPlaying? {
        val titled = sessions.filter { !it.title.isNullOrBlank() && !isNoise(it.packageName) }
        if (titled.isEmpty()) return null
        val chosen = titled.firstOrNull { it.playbackState == STATE_PLAYING }
            ?: (titled.firstOrNull { it.packageName == lastOwner && !isNoise(it.packageName) }
                ?: titled.firstOrNull { it.playbackState == STATE_BUFFERING }
                ?: titled.firstOrNull { it.playbackState == STATE_PAUSED })
            ?: return null
        return chosen.toNowPlaying(nowUptimeMs)
    }

    private fun SessionInfo.toNowPlaying(nowUptimeMs: Long) = NowPlaying(
        packageName = packageName,
        title = title?.trim().orEmpty(),
        artist = artist?.trim().orEmpty(),
        album = album,
        mediaId = mediaId,
        durationMs = durationMs?.takeIf { it > 0 },
        positionMs = (positionMs ?: 0L).coerceAtLeast(0L),
        positionKnown = positionMs != null,
        playing = playbackState == STATE_PLAYING,
        speed = if (speed == 0f) 1f else speed,
        atUptimeMs = nowUptimeMs,
        coverBase64 = coverBase64,
    )

    private fun isNoise(packageName: String): Boolean =
        NOISE_PREFIXES.any { packageName.startsWith(it) }
}

package com.bydmate.app.lyrics

/**
 * Which frames the display needs next — the whole decision of [LyricsDisplayEngine] except the
 * network and the catalog call, so the cadence rules are JVM-tested.
 *
 * Three rules carry the latency budget:
 *
 * **A track change is one frame, the words another.** [observe] emits `track` the moment the key
 * changes and asks for a lookup; [onLyrics] delivers the timeline when it lands. Nothing else is
 * repeated per song.
 *
 * **Position is re-anchored on a timer, not per line.** The display extrapolates between anchors, so
 * a poll that finds the same song gives [anchorEveryMs]-spaced position at most — and an anchor the
 * moment playback starts or stops, because that is when the display's own extrapolation goes wrong.
 *
 * **Nothing is answered twice.** A key whose lookup already returned — hit or miss — is not re-queried,
 * so a radio station that repeats a title costs one search per hour, not one per poll. Rewinding
 * through a queue finds its words in [answered] instead of on the network.
 */
class DisplayScheduler(
    private val anchorEveryMs: Long = ANCHOR_EVERY_MS,
    private val idleAfterMs: Long = IDLE_AFTER_MS,
) {

    private var current: NowPlaying? = null
    private var lastAnchorAt = 0L
    private var lastSeenAt = 0L
    private var seenGap = false

    /**
     * The keys a lookup has already returned for, hit or miss, oldest dropped first.
     * [MAX_ANSWERED_KEYS] covers a queue the driver skips through and returns to; a whole drive
     * through a radio station's titles must not grow state in an object that lives as long as the
     * service.
     */
    private val answered = LinkedHashMap<String, LyricHit?>()

    /** The song the display should be showing, and the state of its lyric lookup. */
    val track: NowPlaying? get() = current

    /** Ready / pending / none, for the status line and the `track` frame. */
    val lyricState: String get() = lyricStateFor(current?.key)

    /** The catalog that answered the song on screen, or null while it has not answered. */
    val lyricSource: String? get() = currentHit()?.sourceId

    /** Lines the display is being given; 0 until a timeline lands for the song on screen. */
    val lines: Int get() = currentHit()?.timeline?.size ?: 0

    /**
     * One observation of the player at [nowUptimeMs] (`SystemClock.elapsedRealtime()`), returning the
     * frames to broadcast. A null [play] starts the [idleAfterMs] grace before the `idle` frame, so a
     * session that blinks out on a seek does not blank the screen.
     */
    fun observe(play: NowPlaying?, nowUptimeMs: Long): List<String> {
        if (play == null) {
            // A session that blinks out on a seek must not blank the screen; only a full
            // [idleAfterMs] without one clears it.
            if (current == null || nowUptimeMs - lastSeenAt < idleAfterMs) {
                seenGap = true
                return emptyList()
            }
            // Cleared so a display that connects after this is shown nothing rather than the song
            // that just ended; the answers stay remembered in [answered].
            current = null
            seenGap = true
            return listOf(DisplayProtocol.idle("no session"))
        }
        lastSeenAt = nowUptimeMs
        val previous = current
        // The session was briefly gone, so nothing the display counted during that gap is trusted.
        val afterGap = seenGap
        seenGap = false
        val frames = ArrayList<String>(2)
        if (previous?.key != play.key) {
            current = play
            frames += DisplayProtocol.track(play, lyricStateFor(play.key))
            // A song the lookup already answered (the same track chosen again) is sent whole right
            // away, so rewinding in a queue costs no network at all.
            answered[play.key]?.let { frames += DisplayProtocol.lyrics(play.key, it) }
            lastAnchorAt = 0L
        } else {
            current = play
            // A player that publishes the title before its picture would otherwise lose the picture,
            // and one that publishes the *previous* song's bitmap first and the right one later would
            // otherwise keep it: either way a cover that changed is worth a second song frame. Same
            // key, so the display keeps its words.
            if (previous?.coverBase64 != play.coverBase64) {
                frames += DisplayProtocol.track(play, lyricStateFor(play.key))
            }
        }
        if (wantsAnchor(play, previous, nowUptimeMs, afterGap)) {
            lastAnchorAt = nowUptimeMs
            frames += DisplayProtocol.anchor(play)
        }
        return frames
    }

    /**
     * The lookup answer for [key]: the timeline, or null when every catalog failed it. Frames are
     * only produced for the song still on screen — a late answer for the previous track is remembered
     * (it must not be re-queried) but dropped.
     */
    fun onLyrics(key: String, answer: LyricHit?): List<String> {
        val known = answered.containsKey(key)
        if (!known) remember(key, answer)
        if (current?.key != key || known) return emptyList()
        // No extra anchor: observe has been sending position the whole time the catalogs were
        // answering, so the display's clock is already where the new lines start.
        return if (answer == null) listOf(DisplayProtocol.lyricsMissing(key))
        else listOf(DisplayProtocol.lyrics(key, answer))
    }

    /** The song a lookup is still owed for, or null when the current one is answered. */
    fun lookupFor(): LookupRequest? {
        val play = current ?: return null
        if (answered.containsKey(play.key)) return null
        return LookupRequest(play.key, play.searchQuery, play.packageName)
    }

    /**
     * What a display that has just connected needs to see in order: the song, its words, then where
     * the song is. Without the last one it would show line one of a track that is halfway through.
     */
    fun snapshot(): List<String> {
        val play = current ?: return listOf(DisplayProtocol.idle("no session"))
        val frames = ArrayList<String>(3)
        frames += DisplayProtocol.track(play, lyricState)
        if (lyricState == DisplayProtocol.LYRICS_READY) answered[play.key]?.let {
            frames += DisplayProtocol.lyrics(play.key, it)
        }
        if (play.positionKnown) frames += DisplayProtocol.anchor(play)
        return frames
    }

    private fun lyricStateFor(key: String?): String = when {
        key == null || !answered.containsKey(key) -> DisplayProtocol.LYRICS_PENDING
        answered[key] != null -> DisplayProtocol.LYRICS_READY
        else -> DisplayProtocol.LYRICS_NONE
    }

    private fun currentHit(): LyricHit? = current?.let { answered[it.key] }

    private fun remember(key: String, answer: LyricHit?) {
        if (answered.size >= MAX_ANSWERED_KEYS && !answered.containsKey(key)) {
            answered.remove(answered.keys.first())
        }
        answered[key] = answer
    }

    private fun wantsAnchor(
        play: NowPlaying,
        previous: NowPlaying?,
        nowUptimeMs: Long,
        afterGap: Boolean,
    ): Boolean {
        if (!play.positionKnown) return false
        if (lastAnchorAt == 0L || afterGap) return true
        // Start and stop are not a timer tick: the display must stop counting the instant playback does.
        if (previous != null && previous.playing != play.playing) return true
        return nowUptimeMs - lastAnchorAt >= anchorEveryMs
    }

    /** What [observe] decided must be looked up because the song on screen changed. */
    data class LookupRequest(val key: String, val query: TrackQuery, val packageName: String)

    companion object {
        /** Re-anchor spacing: the drift of a phone or head-unit clock over this long is well under
         *  the ~100 ms a sung line can absorb, and 2 s of traffic-free display is what keeps the
         *  link cheap on a hotspot. */
        const val ANCHOR_EVERY_MS = 2_000L

        /** How long a session may be missing before the screen is told to clear. */
        const val IDLE_AFTER_MS = 5_000L

        /** A queue skipped forward and back stays answered; a long radio list evicts its oldest. */
        private const val MAX_ANSWERED_KEYS = 8
    }
}

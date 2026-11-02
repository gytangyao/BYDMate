package com.bydmate.app.lyrics

import java.util.Locale

/**
 * Deciding whether a catalog hit is the track the head unit is playing. Pure, so every threshold is
 * JVM-tested — a wrong match is worse than no lyrics, because it shows text that never gets sung.
 *
 * The identity that reaches a search is whatever the player published: a QQ Music session, an AVRCP
 * stream from a phone over Bluetooth, a radio app. Titles carry version suffixes, artists carry
 * "、" lists, and durations disagree by a second or two between catalogs. So [score] weights title,
 * artist and duration apart, [isPlausible] rejects the combinations that are clearly a different
 * recording, and [threshold] says what is good enough to display.
 */
object TrackMatch {

    /** Duration agreement that alone can carry a near-title match. */
    private const val DURATION_TIGHT_MS = 2_000L
    private const val DURATION_CLOSE_MS = 5_000L
    private const val DURATION_ACCEPT_MS = 15_000L
    private const val TITLE_EXACT = 100
    private const val TITLE_CONTAINS = 55
    private const val TITLE_UNRELATED = -80
    private const val ARTIST_EXACT = 70
    private const val ARTIST_CONTAINS = 45
    private const val ARTIST_MISMATCH = -45
    private const val VERSION_PENALTY = -60
    private const val DURATION_EXACT = 35
    private const val DURATION_CLOSE = 25
    private const val DURATION_FAR = 5
    private const val DURATION_MISMATCH = -20

    /** A candidate needs this much score to be the track; a title match alone is not enough. */
    const val ACCEPT_SCORE = 100

    /** The version markers a catalog appends and the player sometimes does not: live, cover, key
     *  changes, remixes, sped-up edits. */
    private val VERSION_NOISE = Regex(
        """(伴奏|翻唱|现场|升调|降调|live|remix|dj|sped|slowed|ktv)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Case- and punctuation-insensitive form used for every comparison: `晴天 (Live) - 周杰伦` and
     * `晴天-周杰伦` normalize alike. Whitespace and every Unicode punctuation mark go, including the
     * full-width ones Chinese players use.
     */
    fun normalize(value: String?): String =
        value?.lowercase(Locale.ROOT)?.replace(Regex("""[\p{P}\s]+"""), "") ?: ""

    /** The same text with the version markers kept, so a cover is not mistaken for the original. */
    fun hasVersionNoise(value: String?): Boolean =
        value != null && VERSION_NOISE.containsMatchIn(value.lowercase(Locale.ROOT))

    /** Stripped of a trailing `- 歌手` the players append to the title itself. */
    fun titleFromSession(title: String?, artist: String?): String {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty() || artist.isNullOrBlank()) return trimmed
        val separator = trimmed.lastIndexOf('-')
            .takeIf { it > 0 } ?: trimmed.lastIndexOf('－')
        if (separator == null) return trimmed
        val tail = normalize(trimmed.substring(separator + 1))
        return if (tail.isNotEmpty() && tail == normalize(artist)) trimmed.substring(0, separator).trim()
        else trimmed
    }

    /**
     * How well [candidate] fits the wanted track. All three signals count: a title that only contains
     * the other is worth less, an artist disagreement is punished harder than a missing artist, and
     * duration decides ties. A candidate whose title carries a version marker the wanted track does
     * not loses [VERSION_PENALTY], which is what keeps a live or backing-track upload out of the
     * cache of a studio recording.
     */
    fun score(
        title: String?,
        artist: String?,
        durationMs: Long,
        candidateTitle: String?,
        candidateArtist: String?,
        candidateDurationMs: Long,
    ): Int {
        val wantedTitle = normalize(title)
        val foundTitle = normalize(candidateTitle)
        if (wantedTitle.isEmpty() || foundTitle.isEmpty()) return Int.MIN_VALUE
        var points = when {
            wantedTitle == foundTitle -> TITLE_EXACT
            wantedTitle.contains(foundTitle) || foundTitle.contains(wantedTitle) -> TITLE_CONTAINS
            else -> TITLE_UNRELATED
        }
        val wantedArtist = normalize(artist)
        val foundArtist = normalize(candidateArtist)
        if (wantedArtist.isNotEmpty() && foundArtist.isNotEmpty()) {
            points += when {
                wantedArtist == foundArtist -> ARTIST_EXACT
                wantedArtist.contains(foundArtist) || foundArtist.contains(wantedArtist) -> ARTIST_CONTAINS
                else -> ARTIST_MISMATCH
            }
        }
        if (durationMs > 0 && candidateDurationMs > 0) {
            val difference = Math.abs(durationMs - candidateDurationMs)
            points += when {
                difference <= DURATION_TIGHT_MS -> DURATION_EXACT
                difference <= DURATION_CLOSE_MS -> DURATION_CLOSE
                difference <= DURATION_ACCEPT_MS -> DURATION_FAR
                else -> DURATION_MISMATCH
            }
        }
        if (!hasVersionNoise(title) && hasVersionNoise(candidateTitle)) points += VERSION_PENALTY
        return points
    }

    /**
     * Whether to look at this candidate at all. An artist both sides name must agree, and the
     * durations must be within [DURATION_ACCEPT_MS]; that alone drops the covers and speed-ups that
     * fill a Chinese catalog's first result page. A title that is not even contained in the other is
     * out. When the titles only overlap, the artist must agree and the duration must be within two
     * seconds, and neither side may add a version marker the other lacks — otherwise the risk of
     * displaying another recording's words is too high.
     */
    fun isPlausible(
        title: String?,
        artist: String?,
        durationMs: Long,
        candidateTitle: String?,
        candidateArtist: String?,
        candidateDurationMs: Long,
    ): Boolean {
        val wantedTitle = normalize(title)
        val foundTitle = normalize(candidateTitle)
        if (wantedTitle.isEmpty() || foundTitle.isEmpty()) return false
        val wantedArtist = normalize(artist)
        val foundArtist = normalize(candidateArtist)
        if (wantedArtist.isNotEmpty() && foundArtist.isNotEmpty() &&
            !wantedArtist.contains(foundArtist) && !foundArtist.contains(wantedArtist)
        ) return false
        val difference = if (durationMs > 0 && candidateDurationMs > 0)
            Math.abs(durationMs - candidateDurationMs) else -1L
        if (difference > DURATION_ACCEPT_MS) return false
        if (wantedTitle == foundTitle) return true
        return wantedArtist.isNotEmpty() && foundArtist.isNotEmpty() &&
            difference in 0..DURATION_TIGHT_MS &&
            (wantedTitle.contains(foundTitle) || foundTitle.contains(wantedTitle)) &&
            hasVersionNoise(title) == hasVersionNoise(candidateTitle)
    }

    /** A stable id for one track identity, so a repeated poll of the same song re-uses one lookup. */
    fun trackKey(packageName: String, title: String?, artist: String?, durationMs: Long): String =
        "${packageName}|${normalize(title)}|${normalize(artist)}|${durationMs / 1000}"
}

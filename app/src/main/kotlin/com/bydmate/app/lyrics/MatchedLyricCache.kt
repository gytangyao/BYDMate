package com.bydmate.app.lyrics

import java.security.MessageDigest
import java.util.Locale

/**
 * The timeline a search produced, keyed by the song rather than by any catalog's id.
 *
 * The key is the whole point: it names the catalog, the title and artist as the player published them
 * (case and repeated whitespace folded, **punctuation kept**, because `晴天 (Live)` and `晴天` are not
 * the same recording), the duration to the millisecond, and the session id when the player is the
 * catalog being asked. A key that dropped the version markers would answer a live album with studio
 * words, and one that rounded the duration would let a 254 s song borrow a 255 s one's timeline.
 *
 * An empty artist falls back to the player's package: with no credit to go on, the only thing that
 * distinguishes this track from that one is who was singing it.
 */
internal class MatchedLyricCache(private val disk: LyricDiskCache) {

    fun read(key: String): LyricTimeline {
        val stored = disk.read(key) ?: return LyricTimeline.EMPTY
        val timeline = LyricTimeline.fromJson(stored)
        return if (timeline.isUsable()) timeline else LyricTimeline.EMPTY
    }

    /** Only a lyric worth showing is worth remembering; a placeholder would outlive its own bug. */
    fun write(key: String, timeline: LyricTimeline) {
        if (!timeline.isUsable()) return
        disk.write(key, timeline.toJson())
    }

    /**
     * Whether this catalog could still do better. Word timings are the thing worth waiting for — the
     * karaoke highlight is what the driver watches — and a translation is next, so either one missing
     * earns a background lookup. Once tried, never tried again: a song the catalog only ever has plain
     * lines for must not re-download on every play.
     */
    fun needsUpgrade(key: String, provider: String, timeline: LyricTimeline): Boolean {
        if (provider !in UPGRADEABLE) return false
        if (timeline.hasWordTiming && timeline.hasTranslation) return false
        return disk.read("$key:upgrade") == null
    }

    fun markUpgradeChecked(key: String) {
        disk.write("$key:upgrade", "checked")
    }

    companion object {
        /** Soda's words arrive with its lines and QQ's QRC codec is not written; the rest can improve. */
        private val UPGRADEABLE = setOf("netease", "kuwo", "kugou", "migu")

        fun key(
            provider: String,
            title: String?,
            artist: String?,
            durationMs: Long,
            directId: String?,
            packageName: String,
        ): String {
            val normalizedArtist = normalize(artist)
            val identity = buildString {
                append(provider).append('\n')
                append(normalize(title)).append('\n')
                append(normalizedArtist).append('\n')
                append(durationMs).append('\n')
                append(directId.orEmpty()).append('\n')
                if (normalizedArtist.isEmpty()) append(packageName)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            return buildString(digest.size * 2) {
                for (byte in digest) append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xFF))
            }
        }

        /** Folded case and whitespace, punctuation kept — see the class documentation. */
        private fun normalize(value: String?): String =
            value?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("""\s+"""), " ").orEmpty()
    }
}

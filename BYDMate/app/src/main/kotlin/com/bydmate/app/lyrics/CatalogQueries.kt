package com.bydmate.app.lyrics

import java.util.Locale

/** One way of asking a catalog for a song: what to put in the search box, and what to score against. */
data class SearchTerm(val title: String, val artist: String) {
    val isEmpty: Boolean get() = title.isBlank()
}

/**
 * The searches one track is worth, in order.
 *
 * A catalog answers a *string*, and the string a player publishes is not always a song title: a local
 * library hands over `03. 周杰伦 - 晴天.flac`, a podcast-style app puts the artist inside the title and
 * an album name in the artist field, and a phone mirroring Spotify reports Traditional characters
 * while mainland catalogs are indexed in Simplified. Asking each of those the same way loses the song.
 *
 * So the first term is always the metadata exactly as published — it is the only one allowed to use
 * the player's own media id, because that id was read together with those exact strings — and the
 * rest are conservative rewrites of it. Every catalog works through the list in order and stops at the
 * first answer, so a bad rewrite can only cost a request, never a wrong match.
 */
object CatalogQueries {

    /** A file name, a track number or a quality tag in the title: worth cleaning up before searching. */
    private val FILE_LIKE = listOf(
        Regex("""(?i)\.(?:mp3|flac|wav|aac|m4a|ogg|opus|wma|ape|alac|dsf|dff)$"""),
        Regex("""(?i)^\s*(?:(?:cd|disc)\s*\d+\s*[-_. ]+)?(?:track\s*)?\d{1,3}\s*[-_.、． ]+\s*"""),
        Regex(
            """(?i)\s*[\[\(（【][^\]\)）】]*(?:\b(?:hi[ -]?res|lossless|sq|hq|flac|mp3|wav|""" +
                """\d{2,3}\s*k(?:bps)?|\d{2}\s*bit|remaster(?:ed)?|live)\b|无损|伴奏|纯音乐|dj版)""" +
                """[^\]\)）】]*[\]\)）】]\s*""",
        ),
        Regex("""\s+[-–—－]\s+"""),
        Regex("""^\s*\d{1,4}\s*[.、_－-]"""),
        Regex("""(?i)(?:wav|flac|ape|mp3|dsf|dff|hi-?res|lossless|真无损|无损|CD音轨)"""),
        Regex("""[/\\]"""),
    )

    private val AUDIO_EXTENSION = FILE_LIKE[0]
    private val TRACK_PREFIX = FILE_LIKE[1]
    private val QUALITY_TAG = FILE_LIKE[2]
    private val SPACED_SEPARATOR = FILE_LIKE[3]

    /** Every search worth issuing for [query], the published metadata first. */
    fun terms(query: TrackQuery): List<SearchTerm> {
        val title = query.title.trim()
        val artist = query.artist.trim()
        if (title.isEmpty()) return emptyList()
        val result = ArrayList<SearchTerm>()
        add(result, SearchTerm(title, artist))
        // Local files carry no artist, which is precisely when the embedded "artist - title" is the
        // only usable credit; a clean streaming session never needs this pass.
        if (artist.isEmpty() || looksLikeFile(title)) {
            addFallbacks(result, title, artist)
        }
        val published = result.toList()
        for (term in published) {
            val simplifiedTitle = ChineseScript.toSimplified(term.title)
            val simplifiedArtist = ChineseScript.toSimplified(term.artist)
            if (simplifiedTitle != term.title && simplifiedArtist != term.artist) {
                add(result, SearchTerm(simplifiedTitle, simplifiedArtist))
            }
            if (simplifiedTitle != term.title) add(result, SearchTerm(simplifiedTitle, term.artist))
            if (simplifiedArtist != term.artist) add(result, SearchTerm(term.title, simplifiedArtist))
        }
        return result
    }

    /**
     * A file-style title usually reads `artist - title`, but podcast and vehicle metadata invert it.
     * Both orders are offered — the catalog's own match score decides, and an unmatched rewrite costs
     * nothing but the request.
     */
    private fun addFallbacks(result: ArrayList<SearchTerm>, title: String, artist: String) {
        val cleaned = cleanFileTitle(title)
        if (cleaned.isEmpty()) return
        val parts = splitArtistAndTitle(cleaned, artist.isEmpty())
        if (parts == null) {
            add(result, SearchTerm(cleaned, artist))
            return
        }
        val left = parts[0]
        val right = parts[1]
        when {
            artist.isEmpty() -> {
                add(result, SearchTerm(right, left))
                add(result, SearchTerm(left, right))
            }
            sameText(left, artist) -> add(result, SearchTerm(right, artist))
            sameText(right, artist) -> add(result, SearchTerm(left, artist))
            else -> {
                add(result, SearchTerm(right, artist))
                add(result, SearchTerm(left, artist))
            }
        }
    }

    /** Path, extension, track number and bracketed quality markers, all gone. */
    fun cleanFileTitle(value: String): String {
        var cleaned = value.trim()
        val slash = Math.max(cleaned.lastIndexOf('/'), cleaned.lastIndexOf('\\'))
        if (slash >= 0 && slash + 1 < cleaned.length) cleaned = cleaned.substring(slash + 1)
        cleaned = AUDIO_EXTENSION.replaceFirst(cleaned, "")
        cleaned = TRACK_PREFIX.replaceFirst(cleaned, "")
        cleaned = QUALITY_TAG.replace(cleaned, " ")
        cleaned = cleaned.replace(Regex("""_{2,}"""), " - ").replace(Regex("""\s+"""), " ").trim()
        return trimSeparators(cleaned)
    }

    /** ` - ` splits artist from title; a bare `-` or `_` only when nothing else looks like a file. */
    private fun splitArtistAndTitle(value: String, allowCompactSeparator: Boolean): Array<String>? {
        SPACED_SEPARATOR.find(value)?.let {
            return validParts(value.substring(0, it.range.first), value.substring(it.range.last + 1))
        }
        for (separator in charArrayOf('–', '—', '－')) {
            val index = value.indexOf(separator)
            if (index > 0 && index < value.length - 1) {
                return validParts(value.substring(0, index), value.substring(index + 1))
            }
        }
        if (!allowCompactSeparator) return null
        for (separator in charArrayOf('-', '_')) {
            val index = value.indexOf(separator)
            if (index > 0 && index == value.lastIndexOf(separator) && index < value.length - 1) {
                return validParts(value.substring(0, index), value.substring(index + 1))
            }
        }
        return null
    }

    private fun validParts(left: String, right: String): Array<String>? {
        val first = trimSeparators(left)
        val second = trimSeparators(right)
        return if (first.isEmpty() || second.isEmpty()) null else arrayOf(first, second)
    }

    private fun trimSeparators(value: String): String =
        value.trim().replace(Regex("""^[\s._–—－-]+|[\s._–—－-]+$"""), "").trim()

    private fun sameText(left: String, right: String): Boolean {
        val identity = identity(left)
        return identity.isNotEmpty() && identity == identity(right)
    }

    /** Punctuation- and case-insensitive equality, so two rewrites that only differ in separators count once. */
    private fun identity(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("""[\p{P}\s]+"""), "")

    private fun add(result: ArrayList<SearchTerm>, term: SearchTerm) {
        if (term.title.isBlank()) return
        val key = identity(term.title) + "|" + identity(term.artist)
        if (result.none { identity(it.title) + "|" + identity(it.artist) == key }) result.add(term)
    }

    /** Whether the title is a file name rather than a title — the only case cleanup can improve. */
    private fun looksLikeFile(title: String): Boolean = FILE_LIKE.any { it.containsMatchIn(title) }
}

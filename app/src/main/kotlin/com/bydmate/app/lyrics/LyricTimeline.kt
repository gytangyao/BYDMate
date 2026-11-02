package com.bydmate.app.lyrics

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A lyric timeline: the sung lines of one track, each with its start, how long it stays up, an
 * optional translation and, when the catalog timed it, its words. Pure Kotlin, no Android, so the
 * parsing and every position lookup is JVM-tested.
 *
 * Two catalog shapes reach it. Plain LRC — `[mm:ss(.x|xx|xxx)]text`, repeated tags sharing one text,
 * an optional `[offset:±ms]` shifting the whole file. And the word-timed payload NetEase calls YRC:
 * a `[lineStartMs,lineDurationMs]` line whose words are `(wordStartMs,wordDurationMs,id)` markers
 * written **before** the text they belong to, each marker owning the text up to the next one.
 *
 * [cue] answers "what does the screen show at this position". It never runs ahead of the song, keeps
 * the last line up through a short gap, and turns a long gap into an intermission.
 */
class LyricTimeline(val lines: List<LyricLine>) {

    val empty: Boolean get() = lines.isEmpty()
    val size: Int get() = lines.size
    val hasWordTiming: Boolean get() = lines.any { it.words.isNotEmpty() }
    val hasTranslation: Boolean get() = lines.any { !it.translation.isNullOrEmpty() }

    /** Index of the last line starting at or before [positionMs]; -1 during the intro. */
    fun lineIndexAt(positionMs: Long): Int {
        var low = 0
        var high = lines.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= positionMs) low = mid + 1 else high = mid
        }
        return low - 1
    }

    /**
     * What to draw at [positionMs]. A line's visible span is the provider's own duration when it
     * gave one, else the distance to the next line, else [PLAIN_LINE_HOLD_MS] so the last line of a
     * truncated lyric does not blink out.
     */
    fun cue(positionMs: Long): LyricCue {
        if (lines.isEmpty()) return LyricCue.EMPTY
        val index = lineIndexAt(positionMs)
        val current = if (index >= 0) lines[index] else null
        val previous = if (index > 0) lines[index - 1] else null
        val next = if (index + 1 < lines.size) lines[index + 1] else null
        if (current == null) {
            val first = lines.first()
            return LyricCue(
                current = null, next = first, previous = null,
                lineProgress = 0f, wordIndex = -1, wordProgress = 0f,
                upcomingInMs = first.startMs - positionMs,
            )
        }
        val lineDuration = current.visibleDurationMs(next)
        val lineEnd = current.startMs + lineDuration
        val gap = next?.let { it.startMs - lineEnd }
        val intermission = gap?.takeIf { it >= MIN_INTERMISSION_MS && positionMs >= lineEnd }
        val progress = ((positionMs - current.startMs).coerceIn(0L, lineDuration) * 1_000L /
            lineDuration.coerceAtLeast(1L)).toFloat() / 1_000f
        var wordIndex = -1
        var wordProgress = 0f
        var wordText: String? = null
        for (i in current.words.indices) {
            val word = current.words[i]
            if (positionMs < word.startMs) break
            wordIndex = i
            wordText = word.text
            val span = word.durationMs.coerceAtLeast(1L)
            wordProgress = if (positionMs >= word.startMs + span) 1f
            else ((positionMs - word.startMs) * 1_000L / span).toFloat() / 1_000f
        }
        return LyricCue(
            current = current, next = next, previous = previous,
            lineProgress = progress, wordIndex = wordIndex, wordProgress = wordProgress,
            wordText = wordText, intermissionMs = intermission,
            upcomingInMs = next?.let { it.startMs - positionMs },
        )
    }

    /**
     * Whether a catalog is worth showing: more than one line and at least one line of real text.
     * Credits, an "instrumental" notice or a lone placeholder do not count, so the next catalog gets
     * its turn and the matched cache is not poisoned by a provider's boilerplate.
     */
    fun isUsable(): Boolean =
        lines.size > 1 && lines.any { line -> line.text.isNotBlank() && !isPlaceholder(line.text) }

    fun qualityScore(): Int {
        var score = lines.size.coerceAtMost(MAX_QUALITY_LINES)
        if (hasWordTiming) score += WORD_TIMING_BONUS
        if (hasTranslation) score += TRANSLATION_BONUS
        return score
    }

    /**
     * Wire form for the display, one array per line: `[startMs, durationMs, text, translation?,
     * words?]`, words as `[[offsetFromLineStartMs, durationMs, text], …]`. Word offsets are relative
     * to their line, which keeps a whole song small enough to hand over once at a track change —
     * after that the display runs on its own clock.
     */
    fun encodeLines(): List<List<Any?>> = lines.mapIndexed { index, line ->
        // The line's own visibility, resolved the same way [cue] resolves it: a plain LRC line that
        // the next one replaces after 30 s must not be sent as a 5 s line.
        val row = mutableListOf<Any?>(line.startMs, line.visibleDurationMs(lines.getOrNull(index + 1)), line.text)
        if (line.words.isEmpty()) {
            if (line.translation != null) row.add(line.translation)
        } else {
            // A word list needs the translation slot even when empty, so the display can read
            // positions 3 and 4 by name.
            row.add(line.translation)
            row.add(line.words.map { listOf(it.startMs - line.startMs, it.durationMs, it.text) })
        }
        row
    }

    /**
     * Storage form: the lines themselves, word starts absolute. [encodeLines] is for the display and
     * throws away what the catalog said in favour of what the screen will do; this keeps both, so a
     * timeline read back answers [cue] exactly as the one that was matched did.
     */
    fun toJson(): String = JSONArray().apply {
        lines.forEach { line ->
            val wordArray = JSONArray()
            line.words.forEach { word ->
                wordArray.put(JSONArray().put(word.startMs).put(word.durationMs).put(word.text))
            }
            put(
                JSONArray()
                    .put(line.startMs).put(line.durationMs).put(line.text)
                    .put(line.translation ?: JSONObject.NULL)
                    .put(wordArray),
            )
        }
    }.toString()

    companion object {
        /** The inverse of [toJson]; damaged or truncated storage reads back as no timeline. */
        fun fromJson(value: String): LyricTimeline {
            val rows = runCatching { JSONArray(value) }.getOrNull() ?: return EMPTY
            val lines = ArrayList<LyricLine>(rows.length())
            for (index in 0 until rows.length()) {
                val row = rows.optJSONArray(index) ?: continue
                val start = row.optLong(0, -1L)
                if (start < 0) continue
                val words = ArrayList<LyricWord>()
                row.optJSONArray(4)?.let { stored ->
                    for (at in 0 until stored.length()) {
                        val word = stored.optJSONArray(at) ?: continue
                        val text = word.optString(2)
                        if (text.isEmpty()) continue
                        words += LyricWord(word.optLong(0, start), word.optLong(1, 0L), text)
                    }
                }
                lines += LyricLine(
                    startMs = start,
                    durationMs = row.optLong(1, 0L),
                    text = row.optString(2),
                    translation = if (row.isNull(3)) null else row.optString(3).takeIf { it.isNotEmpty() },
                    words = words,
                )
            }
            return LyricTimeline(lines)
        }

        const val PLAIN_LINE_HOLD_MS = 5_000L
        const val MIN_INTERMISSION_MS = 5_000L

        private const val MAX_QUALITY_LINES = 120
        private const val WORD_TIMING_BONUS = 40
        private const val TRANSLATION_BONUS = 10
        private const val TRANSLATION_MATCH_TOLERANCE_MS = 700L

        private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
        private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
        private val TIMED_LINE = Regex("""^\[(\d+)\s*,\s*(\d+)](.*)$""")
        private val TIMED_WORD = Regex("""\((-?\d+)\s*,\s*(\d+)(?:\s*,\s*\d+)?\)""")
        private val WORD_NOISE = Regex("""\([^()\r\n]*\)""")
        private val PLACEHOLDER = listOf("纯音乐", "暂无歌词", "暂时没有歌词", "instrumental", "no lyrics")

        val EMPTY = LyricTimeline(emptyList())

        /**
         * Parse one track's payload. [wordByWord] (YRC) wins whenever it has lines, because its
         * boundaries are the singing; [original] then only lends the translation it omits. Without a
         * timed payload the LRC lines stand alone, their translations matched by timestamp.
         */
        fun parse(original: String? = null, translated: String? = null, wordByWord: String? = null): LyricTimeline {
            val offset = offsetMs(original, translated, wordByWord)
            val timed = parseTimedLines(wordByWord, offset)
            if (timed.isNotEmpty()) {
                val translations = parseLrc(translated, offset)
                val merged = timed.map { line ->
                    if (line.translation == null) {
                        line.copy(translation = nearest(translations, line.startMs))
                    } else line
                }.sortedBy { it.startMs }
                return LyricTimeline(merged)
            }
            val plain = parseLrc(original, offset)
            if (plain.isEmpty()) return EMPTY
            val extra = parseLrc(translated, offset)
            return LyricTimeline(plain.map { (start, text) ->
                LyricLine(start, 0L, text, nearest(extra, start))
            })
        }

        private fun offsetMs(vararg inputs: String?): Long {
            for (input in inputs) {
                if (input == null) continue
                input.lineSequence().firstNotNullOfOrNull { line ->
                    OFFSET_TAG.find(line)?.groupValues?.get(1)?.toLongOrNull()
                }?.let { return it }
            }
            return 0L
        }

        /** LRC lines, deduplicated by timestamp and in song order. */
        private fun parseLrc(value: String?, offsetMs: Long): List<Pair<Long, String>> {
            if (value.isNullOrEmpty()) return emptyList()
            val result = LinkedHashMap<Long, String>()
            for (raw in value.split("\r\n", "\n")) {
                // NetEase ships its credits as one json object per line, with no time tag.
                if (raw.trimStart().startsWith("{")) continue
                val stamps = ArrayList<Long>()
                var textStart = -1
                for (match in TIME_TAG.findAll(raw)) {
                    stamps.add(match.toMillis(offsetMs))
                    textStart = match.range.last + 1
                }
                if (stamps.isEmpty() || textStart < 0) continue
                val text = raw.substring(textStart).trim()
                if (text.isEmpty()) continue
                for (stamp in stamps) result.putIfAbsent(stamp, text)
            }
            return result.entries.sortedBy { it.key }.map { it.key to it.value }
        }

        /** `[ms,dur]` lines with their `(ms,dur,id)` words; a marker owns the text up to the next one. */
        private fun parseTimedLines(value: String?, offsetMs: Long): List<LyricLine> {
            if (value.isNullOrEmpty()) return emptyList()
            val result = ArrayList<LyricLine>()
            for (raw in value.split("\r\n", "\n")) {
                val line = TIMED_LINE.matchEntire(raw.trim()) ?: continue
                val start = line.groupValues[1].toLongOrNull()?.plus(offsetMs)?.coerceAtLeast(0L) ?: continue
                val duration = line.groupValues[2].toLongOrNull() ?: 0L
                val content = line.groupValues[3]
                val markers = TIMED_WORD.findAll(content).toList()
                if (markers.isEmpty()) {
                    val text = content.replace(WORD_NOISE, "").trim()
                    if (text.isNotEmpty()) result += LyricLine(start, duration, text)
                    continue
                }
                val words = ArrayList<LyricWord>()
                val text = StringBuilder()
                for (i in markers.indices) {
                    val marker = markers[i]
                    // A marker owns the text that follows it, up to the next marker: verified against
                    // the live endpoint, `(start,dur,id)字(start,dur,id)字`. Whatever sits between the
                    // line tag and the first marker is untimed padding and is dropped, which is what
                    // keeps the word list and the displayed line on one coordinate system.
                    val wordText = content.substring(
                        marker.range.last + 1,
                        if (i + 1 < markers.size) markers[i + 1].range.first else content.length,
                    )
                    val wordStart = marker.groupValues[1].toLongOrNull()?.plus(offsetMs)?.coerceAtLeast(start)
                        ?: continue
                    val wordDuration = marker.groupValues[2].toLongOrNull() ?: 0L
                    if (wordText.isNotEmpty()) {
                        words += LyricWord(wordStart, wordDuration, wordText)
                        text.append(wordText)
                    }
                }
                if (words.isEmpty()) continue
                result += LyricLine(start, duration, text.toString(), null, words)
            }
            return result
        }

        /** Translations are stamped to the second, so match the closest line start. */
        private fun nearest(translations: List<Pair<Long, String>>, startMs: Long): String? {
            if (translations.isEmpty()) return null
            var best: String? = null
            var bestDistance = Long.MAX_VALUE
            for ((stamp, text) in translations) {
                val distance = Math.abs(stamp - startMs)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = text
                }
            }
            return best.takeIf { bestDistance <= TRANSLATION_MATCH_TOLERANCE_MS }
        }

        private fun MatchResult.toMillis(offsetMs: Long): Long {
            val minutes = groupValues[1].toLongOrNull() ?: 0L
            val seconds = groupValues[2].toLongOrNull() ?: 0L
            val fraction = groupValues[3]
            val millis = when (fraction?.length) {
                null, 0 -> 0L
                1 -> (fraction.toLongOrNull() ?: 0L) * 100L
                2 -> (fraction.toLongOrNull() ?: 0L) * 10L
                else -> (fraction.take(3).toLongOrNull() ?: 0L)
            }
            return (minutes * 60_000L + seconds * 1_000L + millis + offsetMs).coerceAtLeast(0L)
        }

        private fun isPlaceholder(text: String): Boolean {
            val tight = text.replace(Regex("""[\s\p{Punct}，。！：、]+"""), "").lowercase(Locale.ROOT)
            return tight.isEmpty() || PLACEHOLDER.any { tight.contains(it) }
        }
    }
}

/** One sung line. [durationMs] is the provider's own visible duration, 0 when it gave none. */
data class LyricLine(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<LyricWord> = emptyList(),
) {
    /** How long this line stands alone before the next one takes the screen. */
    fun visibleDurationMs(next: LyricLine?): Long = when {
        durationMs > 0L -> durationMs
        next != null -> (next.startMs - startMs).coerceAtLeast(1_000L)
        else -> LyricTimeline.PLAIN_LINE_HOLD_MS
    }
}

/** One timed word, [startMs] absolute in the song. */
data class LyricWord(val startMs: Long, val durationMs: Long, val text: String)

/** What to draw at one position: the line being sung, how far it is, the word inside it, the next line. */
data class LyricCue(
    val current: LyricLine?,
    val next: LyricLine?,
    val previous: LyricLine?,
    val lineProgress: Float,
    val wordIndex: Int,
    val wordProgress: Float,
    val wordText: String? = null,
    val intermissionMs: Long? = null,
    val upcomingInMs: Long? = null,
) {
    companion object {
        val EMPTY = LyricCue(null, null, null, 0f, -1, 0f)
    }
}

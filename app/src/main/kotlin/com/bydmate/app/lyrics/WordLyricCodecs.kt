package com.bydmate.app.lyrics

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.zip.InflaterInputStream

/**
 * The word-timed payloads the extra catalogs ship, turned into the one format [LyricTimeline] reads:
 * `[lineStartMs,lineDurationMs]` followed by `(wordStartMs,wordDurationMs,0)word` — absolute
 * milliseconds, marker before the text it owns.
 *
 * Each catalog keeps its words to itself: Kugou XORs and zlib-compresses them, Kuwo wraps them in an
 * http-ish envelope and encodes the timings as a sum/difference pair, Migu encrypts the whole file
 * with TEA. The algorithms are the published ones, reimplemented here; the output is the same shape
 * NetEase's YRC already produces, so no parser outside these objects changes.
 */
internal object WordLyricCodecs {

    /** One inflate is allowed to grow to this much, so a corrupt payload cannot exhaust the heap. */
    private const val MAX_DECOMPRESSED_BYTES = 10L * 1024 * 1024

    private val KRC_KEY = byteArrayOf(
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, -50, -46, 110, 105,
    )

    private val KUWO_KEY = "yeelion".toByteArray(StandardCharsets.US_ASCII)

    private val KRC_LINE = Regex("""^\[(\d+)\s*,\s*(\d+)](.*)$""")
    private val KRC_WORD = Regex("""<(\d+)\s*,\s*(\d+)\s*,\s*\d+>""")
    private val KRC_LANGUAGE = Regex("""^\[language:([^]]+)]\s*$""", RegexOption.IGNORE_CASE)

    private val KUWO_LINE = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?](.*)$""")
    private val KUWO_WORD = Regex("""<(-?\d+),(-?\d+)(?:,-?\d+)?>""")
    private val KUWO_TAG = Regex("""^\[kuwo:([^]]+)]""")

    private val MIGU_LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val MIGU_WORD = Regex("""\((\d+),(\d+)\)""")

    // ---- Kugou -------------------------------------------------------------------------------

    /** Kugou's `content` field: base64 of four junk bytes, then XOR-keyed, zlib-compressed KRC. */
    fun kugouPayload(encoded: String): String? {
        if (encoded.isEmpty()) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrNull() ?: return null
        if (bytes.size <= 4) return null
        val deflated = ByteArray(bytes.size - 4)
        for (index in deflated.indices) {
            deflated[index] = (bytes[index + 4].toInt() xor KRC_KEY[index % KRC_KEY.size].toInt()).toByte()
        }
        val text = inflate(deflated) ?: return null
        return String(text, StandardCharsets.UTF_8).removePrefix("\uFEFF")
    }

    /** `[start,dur]<offset,dur,id>word…` with offsets from the line; the markers become absolute. */
    fun kugouEnhanced(krc: String): String {
        val builder = StringBuilder()
        for (raw in krc.split("\r\n", "\n")) {
            val line = KRC_LINE.matchEntire(raw.trim()) ?: continue
            val lineStart = line.groupValues[1].toLongOrNull() ?: continue
            val lineDuration = line.groupValues[2].toLongOrNull() ?: continue
            val body = line.groupValues[3]
            var offset = -1L
            var duration = 0L
            var textStart = -1
            val converted = StringBuilder()
            for (marker in KRC_WORD.findAll(body)) {
                if (offset >= 0 && textStart >= 0) {
                    converted.append('(').append(lineStart + offset).append(',')
                        .append(duration).append(",0)").append(body, textStart, marker.range.first)
                }
                offset = marker.groupValues[1].toLongOrNull() ?: -1L
                duration = marker.groupValues[2].toLongOrNull() ?: 0L
                textStart = marker.range.last + 1
            }
            if (offset >= 0 && textStart >= 0) {
                converted.append('(').append(lineStart + offset).append(',')
                    .append(duration).append(",0)").append(body.substring(textStart))
            }
            if (converted.isNotEmpty()) {
                builder.append('[').append(lineStart).append(',').append(lineDuration).append(']')
                    .append(converted).append('\n')
            }
        }
        return builder.toString()
    }

    /**
     * Kugou's translation is not on the lyric line — it hides in the `[language:…]` header, base64 of
     * a json object whose `type == 1` section holds one row per KRC line. The rows have to line up
     * with the lines one-for-one, so a mismatch yields nothing rather than a shifted translation.
     */
    fun kugouTranslation(krc: String): String {
        val encoded = krc.lineSequence().firstNotNullOfOrNull { raw ->
            KRC_LANGUAGE.matchEntire(raw.trim())?.groupValues?.get(1)?.trim()
        }.orEmpty()
        if (encoded.isEmpty()) return ""
        val json = runCatching {
            String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8)
        }.getOrNull() ?: return ""
        val lineStarts = krc.split("\r\n", "\n").mapNotNull { raw ->
            KRC_LINE.matchEntire(raw.trim())?.groupValues?.get(1)?.toLongOrNull()
        }
        if (lineStarts.isEmpty()) return ""
        val sections = runCatching { JSONObject(json).optJSONArray("content") }.getOrNull() ?: return ""
        var rows: org.json.JSONArray? = null
        for (index in 0 until sections.length()) {
            val section = sections.optJSONObject(index) ?: continue
            if (section.optInt("type", -1) == 1) {
                rows = section.optJSONArray("lyricContent")
                break
            }
        }
        if (rows == null || rows.length() != lineStarts.size) return ""
        val builder = StringBuilder()
        for (index in 0 until rows.length()) {
            val fragments = rows.optJSONArray(index) ?: continue
            val text = StringBuilder()
            for (part in 0 until fragments.length()) text.append(fragments.optString(part, ""))
            val value = text.toString().trim()
            if (value.isEmpty() || value == "//") continue
            builder.append(lrcStamp(lineStarts[index])).append(value).append('\n')
        }
        return builder.toString()
    }

    fun kugouPlainLrc(encoded: String): String =
        runCatching { String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8) }
            .getOrNull()?.replace("\uFEFF", "").orEmpty()

    // ---- Kuwo --------------------------------------------------------------------------------

    /**
     * Kuwo's mobile word endpoint answers with a small http-ish envelope: an `tp=content` header, a
     * blank line, then zlib, then base64, then a repeating-key XOR. Anything else is an error page and
     * has to be reported as no answer rather than as an empty lyric.
     */
    fun kuwoEnvelope(response: ByteArray): String? {
        if (response.size < 15) return null
        val prefix = String(response, 0, 10, StandardCharsets.US_ASCII)
        if (!prefix.equals("tp=content", ignoreCase = true)) return null
        var body = -1
        var index = 0
        while (index + 3 < response.size) {
            if (response[index] == '\r'.code.toByte() && response[index + 1] == '\n'.code.toByte() &&
                response[index + 2] == '\r'.code.toByte() && response[index + 3] == '\n'.code.toByte()
            ) {
                body = index + 4
                break
            }
            index++
        }
        if (body < 0) return null
        val inflated = inflate(response.copyOfRange(body, response.size)) ?: return null
        val xored = runCatching { Base64.getMimeDecoder().decode(inflated) }.getOrNull() ?: return null
        for (at in xored.indices) {
            xored[at] = (xored[at].toInt() xor KUWO_KEY[at % KUWO_KEY.size].toInt()).toByte()
        }
        return String(xored, StandardCharsets.UTF_8).replace("\uFEFF", "")
    }

    /**
     * Kuwo's word tags are not times but the sum and difference of a start and a duration, scaled by a
     * divisor pair the `[kuwo:XX]` header spells out in octal — so octal `21` is 17, and 17 divides the
     * first number by 1 and the second by 7. A header whose pair comes out zero cannot describe any
     * timing, so the payload is dropped; no header at all means the pair is 1 and 1.
     */
    fun kuwoEnhanced(lyric: String): String {
        if (lyric.isEmpty()) return ""
        var firstDivisor = 1L
        var secondDivisor = 1L
        val tag = KUWO_TAG.find(lyric)
        if (tag != null) {
            val value = runCatching {
                BigInteger(tag.groupValues[1].trim(), 8).toLong()
            }.getOrDefault(-1L)
            firstDivisor = value / 10L
            secondDivisor = value % 10L
            if (firstDivisor <= 0L || secondDivisor <= 0L) return ""
        }
        val builder = StringBuilder()
        for (raw in lyric.split("\r\n", "\n")) {
            val line = KUWO_LINE.matchEntire(raw.trim()) ?: continue
            val lineStart = stampToMillis(line.groupValues[1], line.groupValues[2], line.groupValues[3])
            val body = line.groupValues[4]
            val marks = KUWO_WORD.findAll(body).toList()
            if (marks.isEmpty()) continue
            // A marker owns the text up to the next marker, exactly as in the YRC payload.
            val starts = LongArray(marks.size)
            val durations = LongArray(marks.size)
            val texts = arrayOfNulls<String>(marks.size)
            for (at in marks.indices) {
                val sum = marks[at].groupValues[1].toLongOrNull() ?: continue
                val difference = marks[at].groupValues[2].toLongOrNull() ?: continue
                starts[at] = Math.abs((sum + difference) / (firstDivisor * 2L))
                durations[at] = Math.abs((sum - difference) / (secondDivisor * 2L))
                texts[at] = body.substring(
                    marks[at].range.last + 1,
                    if (at + 1 < marks.size) marks[at + 1].range.first else body.length,
                )
            }
            // A word never outlives the next one's start, whatever the encoded numbers said.
            for (at in 0 until marks.size - 1) {
                if (starts[at] + durations[at] > starts[at + 1]) {
                    durations[at] = (starts[at + 1] - starts[at]).coerceAtLeast(0L)
                }
            }
            // The decoded starts and durations are offsets from the line, which is why the word tags
            // add [lineStart] below; the last word's end is therefore already the line's duration.
            builder.append('[').append(lineStart).append(',')
                .append((starts.last() + durations.last()).coerceAtLeast(0L)).append(']')
            for (at in marks.indices) {
                val text = texts[at]
                if (text.isNullOrEmpty()) continue
                builder.append('(').append(lineStart + starts[at]).append(',')
                    .append(durations[at]).append(",0)").append(text)
            }
            builder.append('\n')
        }
        return builder.toString()
    }

    // ---- Migu --------------------------------------------------------------------------------

    private const val TEA_DELTA = 0x9E3779B9L

    /** The rounds address `KEY[(index & 3) xor roundBits]`, so only these four are ever read. */
    private val TEA_KEY = longArrayOf(
        27303562373562475L, 18014862372307051L, 22799692160172081L, 34058940340699235L,
    )

    /**
     * Migu's MRC is plain TEA over 64-bit words, sixteen hex characters apiece, and the plaintext is
     * UTF-16LE. An already-decrypted file starts with a `[` and is handed back untouched.
     */
    fun miguDecrypt(encrypted: String): String {
        if (encrypted.length < 32) return encrypted
        if (encrypted.trimStart().startsWith("[")) return encrypted
        val blocks = encrypted.length / 16
        if (blocks < 2 || blocks > 65_536) return ""
        val data = LongArray(blocks)
        for (index in 0 until blocks) {
            data[index] = runCatching {
                BigInteger(encrypted.substring(index * 16, index * 16 + 16), 16).toLong()
            }.getOrNull() ?: return ""
        }
        var sum = (6 + 52 / blocks) * TEA_DELTA
        var last = data[0]
        while (sum != 0L) {
            val round = ((sum ushr 2) and 3L).toInt()
            for (index in blocks - 1 downTo 1) {
                last = data[index] - teaMix(last, data[index - 1], sum, TEA_KEY[(index and 3) xor round])
                data[index] = last
            }
            last = data[0] - teaMix(last, data[blocks - 1], sum, TEA_KEY[round])
            data[0] = last
            sum -= TEA_DELTA
        }
        val bytes = ByteBuffer.allocate(blocks * 8).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { bytes.putLong(it) }
        return String(bytes.array(), StandardCharsets.UTF_16LE)
            .replace("\u0000", "").replace("\uFEFF", "")
    }

    /** Migu's MRC already times its words in absolute milliseconds; only the tag shape changes. */
    fun miguEnhanced(mrc: String): String {
        val builder = StringBuilder()
        for (raw in mrc.split("\r\n", "\n")) {
            val line = MIGU_LINE.matchEntire(raw.trim()) ?: continue
            val body = line.groupValues[3]
            var textEnd = -1
            var start = 0L
            var duration = 0L
            val converted = StringBuilder()
            for (marker in MIGU_WORD.findAll(body)) {
                if (textEnd >= 0) {
                    converted.append('(').append(start).append(',').append(duration).append(",0)")
                        .append(body, textEnd, marker.range.first)
                }
                start = marker.groupValues[1].toLongOrNull() ?: continue
                duration = marker.groupValues[2].toLongOrNull() ?: 0L
                textEnd = marker.range.last + 1
            }
            if (textEnd < 0) continue
            converted.append('(').append(start).append(',').append(duration).append(",0)")
                .append(body.substring(textEnd))
            builder.append('[').append(line.groupValues[1]).append(',')
                .append(line.groupValues[2]).append(']').append(converted).append('\n')
        }
        return builder.toString()
    }

    // ---- shared ------------------------------------------------------------------------------

    private fun teaMix(current: Long, previous: Long, sum: Long, key: Long): Long =
        ((current xor sum) + (previous xor key)) xor
            (((previous shr 5) xor (current shl 2)) + ((current shr 3) xor (previous shl 4)))

    /** `[mm:ss.xxx]` to milliseconds; a one-digit fraction is tenths, a two-digit one is hundredths. */
    private fun stampToMillis(minutes: String, seconds: String, fraction: String): Long {
        var millis = (minutes.toLongOrNull() ?: 0L) * 60_000L + (seconds.toLongOrNull() ?: 0L) * 1_000L
        val value = fraction.toLongOrNull() ?: return millis
        millis += when (fraction.length) {
            1 -> value * 100L
            2 -> value * 10L
            else -> value
        }
        return millis
    }

    private fun lrcStamp(timeMs: Long): String = String.format(
        Locale.ROOT, "[%02d:%02d.%03d]", timeMs / 60_000L, timeMs / 1_000L % 60L, timeMs % 1_000L,
    )

    /** zlib, with a ceiling: a lyric is a few dozen kilobytes, so anything past this is a bomb. */
    private fun inflate(input: ByteArray): ByteArray? = runCatching {
        InflaterInputStream(ByteArrayInputStream(input)).use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var count = source.read(buffer)
            while (count >= 0) {
                if (count > 0) {
                    if (output.size() + count > MAX_DECOMPRESSED_BYTES) return null
                    output.write(buffer, 0, count)
                }
                count = source.read(buffer)
            }
            output.toByteArray()
        }
    }.getOrNull()
}

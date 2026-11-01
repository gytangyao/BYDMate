package com.bydmate.app.lyrics

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.zip.Deflater

/**
 * The three binary payloads, each checked against the bytes that produce it.
 *
 * [encodeKrc] / [encodeKuwo] / [encryptMigu] write the catalog's own encoder out again here, so a
 * decoder that drifted from its format fails instead of agreeing with itself. The Migu helper is the
 * exact inverse of [WordLyricCodecs.miguDecrypt] derived from it, which pins the round count, the
 * chain order and the UTF-16LE reading; it is not a capture of a live response.
 */
class WordLyricCodecsTest {

    // ---- Kugou -------------------------------------------------------------------------------

    @Test fun `a krc payload is four junk bytes then xor then zlib`() {
        val krc = "[0,1000]<0,1000,0>故事<1000,1000,0>的小黄花\n"
        assertEquals(krc, WordLyricCodecs.kugouPayload(encodeKrc(krc)))
    }

    @Test fun `a byte order mark is not part of the lyric`() {
        val krc = "﻿[0,1000]<0,1000,0>故事\n"
        assertEquals("[0,1000]<0,1000,0>故事\n", WordLyricCodecs.kugouPayload(encodeKrc(krc)))
    }

    @Test fun `junk that is not a payload is no answer`() {
        assertNull(WordLyricCodecs.kugouPayload(""))
        assertNull(WordLyricCodecs.kugouPayload("!!!!"))
        assertNull(WordLyricCodecs.kugouPayload(Base64.getEncoder().encodeToString(ByteArray(3))))
        // base64 of nothing but zeros: the bytes after the junk header are not a zlib stream.
        assertNull(WordLyricCodecs.kugouPayload(Base64.getEncoder().encodeToString(ByteArray(40))))
    }

    @Test fun `krc word offsets become absolute and an untimed line is dropped`() {
        val krc = """
            [0,1000]<0,1000,0>故事<1000,1000,0>的小黄花
            [10000,3000]<0,1500,0>从出生<1500,1500,0>那年就飘着
            [20000,2000]没有标记的一行
        """.trimIndent()
        assertEquals(
            "[0,1000](0,1000,0)故事(1000,1000,0)的小黄花\n" +
                "[10000,3000](10000,1500,0)从出生(11500,1500,0)那年就飘着\n",
            WordLyricCodecs.kugouEnhanced(krc),
        )
        val timeline = LyricTimeline.parse("", "", WordLyricCodecs.kugouEnhanced(krc))
        assertEquals(2, timeline.size)
        assertTrue(timeline.hasWordTiming)
        assertEquals("故事的小黄花", timeline.lines[0].text)
        assertEquals(11_500L, timeline.lines[1].words[1].startMs)
    }

    @Test fun `the translation rides in the language header and must line up with the lines`() {
        val rows = JSONArray()
            .put(JSONArray().put("the petals").put(" fall"))
            .put(JSONArray().put("from the year i was born"))
        val json = JSONObject().put(
            "content",
            JSONArray()
                .put(JSONObject().put("type", 0).put("lyricContent", JSONArray()))
                .put(JSONObject().put("type", 1).put("lyricContent", rows)),
        ).toString()
        val header = "[language:${Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))}]"
        val krc = "$header\n[0,1000]<0,1000,0>故事\n[10000,3000]<0,1500,0>从出生\n"
        assertEquals(
            "[00:00.000]the petals fall\n[00:10.000]from the year i was born\n",
            WordLyricCodecs.kugouTranslation(krc),
        )
    }

    @Test fun `one row per line or none at all`() {
        // Three rows against two lines would shift every translation by a line, which is worse than none.
        val rows = JSONArray().put(JSONArray().put("a")).put(JSONArray().put("b")).put(JSONArray().put("c"))
        val json = JSONObject().put(
            "content", JSONArray().put(JSONObject().put("type", 1).put("lyricContent", rows)),
        ).toString()
        val header = "[language:${Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))}]"
        val krc = "$header\n[0,1000]<0,1000,0>故事\n[10000,3000]<0,1500,0>从出生\n"
        assertEquals("", WordLyricCodecs.kugouTranslation(krc))
        assertEquals("", WordLyricCodecs.kugouTranslation("[0,1000]<0,1000,0>故事\n"))
        assertEquals("", WordLyricCodecs.kugouTranslation("[language:bm90IGpzb24=]\n[0,1]x\n"))
    }

    @Test fun `the plain lrc download is only base64`() {
        val lrc = "[00:10.00]故事的小黄花\n"
        assertEquals(lrc, WordLyricCodecs.kugouPlainLrc(Base64.getEncoder().encodeToString(lrc.toByteArray())))
        assertEquals("", WordLyricCodecs.kugouPlainLrc("!!!!"))
    }

    // ---- Kuwo --------------------------------------------------------------------------------

    @Test fun `the word channel answers an envelope, not a lyric`() {
        val lyric = "[kuwo:13]\n[00:10.00]<1000,-1000>故事\n"
        val decoded = WordLyricCodecs.kuwoEnvelope(encodeKuwo(lyric))
        assertEquals(lyric, decoded)
        assertNotNull(decoded)
    }

    @Test fun `an error page in place of the envelope is no answer`() {
        assertNull(WordLyricCodecs.kuwoEnvelope("sorry, no lyric here".toByteArray(StandardCharsets.US_ASCII)))
        assertNull(WordLyricCodecs.kuwoEnvelope("tp=content".toByteArray(StandardCharsets.US_ASCII)))
        assertNull(WordLyricCodecs.kuwoEnvelope("tp=content=1\r\nno blank line here".toByteArray()))
    }

    @Test fun `the divisor pair is octal and splits the marker into a start and a duration`() {
        // octal 13 = 11, so 11/10 = 1 divides the sum and 11%10 = 1 divides the difference.
        val enhanced = WordLyricCodecs.kuwoEnhanced(
            "[kuwo:13]\n[00:10.00]<1000,-1000>故事<3000,1000>的小黄花\n",
        )
        assertEquals("[10000,3000](10000,1000,0)故事(12000,1000,0)的小黄花\n", enhanced)
        val timeline = LyricTimeline.parse("", "", enhanced)
        assertTrue(timeline.hasWordTiming)
        assertEquals(3_000L, timeline.lines[0].durationMs)
        assertEquals("故事的小黄花", timeline.lines[0].text)
    }

    @Test fun `a divisor pair of zero cannot describe any timing`() {
        // octal 10 = 8, and 8/10 is 0: every start would be divided by zero.
        assertEquals("", WordLyricCodecs.kuwoEnhanced("[kuwo:10]\n[00:10.00]<1000,-1000>故事\n"))
    }

    @Test fun `a word is cut short when the next one starts inside it`() {
        val enhanced = WordLyricCodecs.kuwoEnhanced(
            "[kuwo:13]\n[00:10.00]<1000,-1000>故事<180,20>的小黄花\n",
        )
        assertEquals("[10000,180](10000,100,0)故事(10100,80,0)的小黄花\n", enhanced)
    }

    @Test fun `lines without a word marker are not a word timeline`() {
        assertEquals("", WordLyricCodecs.kuwoEnhanced("[kuwo:13]\n[00:10.00]只有这一行\n"))
    }

    // ---- Migu --------------------------------------------------------------------------------

    @Test fun `an mrc is tea over hex and the plaintext is utf-16le`() {
        val mrc = "[10000,3000](10000,1500)(11500,1500)从出生那年就飘着\n"
        val encrypted = encryptMigu(mrc)
        assertEquals(0, encrypted.length % 16)
        assertTrue(encrypted.length >= 32)
        assertEquals(mrc, WordLyricCodecs.miguDecrypt(encrypted))
    }

    @Test fun `plain text is handed back and anything too short is left alone`() {
        val plain = "[00:10.00]故事的小黄花\n"
        assertEquals(plain, WordLyricCodecs.miguDecrypt(plain))
        assertEquals("[0,0]x", WordLyricCodecs.miguDecrypt("[0,0]x"))
    }

    @Test fun `hex that is not hex is no lyric`() {
        assertEquals("", WordLyricCodecs.miguDecrypt("zz".repeat(16)))
    }

    @Test fun `migu already times its words absolutely`() {
        val enhanced = WordLyricCodecs.miguEnhanced(
            "[10000,3000](10000,1500)从出生(11500,1500)那年就飘着\n[not a timed line]\n",
        )
        assertEquals("[10000,3000](10000,1500,0)从出生(11500,1500,0)那年就飘着\n", enhanced)
        val timeline = LyricTimeline.parse("", "", enhanced)
        assertEquals(2, timeline.lines[0].words.size)
        assertEquals("从出生", timeline.lines[0].words[0].text)
    }

    // ---- helpers -----------------------------------------------------------------------------

    private companion object {
        val KRC_KEY = byteArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, -50, -46, 110, 105)
        val KUWO_KEY = "yeelion".toByteArray(StandardCharsets.US_ASCII)
        const val TEA_DELTA = 0x9E3779B9L
        val TEA_KEY = longArrayOf(27303562373562475L, 18014862372307051L, 22799692160172081L, 34058940340699235L)

        fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater()
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
            return out.toByteArray()
        }

        fun encodeKrc(krc: String): String {
            val compressed = deflate(krc.toByteArray(StandardCharsets.UTF_8))
            val body = ByteArray(compressed.size) { i ->
                (compressed[i].toInt() xor KRC_KEY[i % KRC_KEY.size].toInt()).toByte()
            }
            return Base64.getEncoder().encodeToString(ByteArray(4) { 0x7f } + body)
        }

        fun encodeKuwo(lyric: String): ByteArray {
            val plain = lyric.toByteArray(StandardCharsets.UTF_8)
            val xored = ByteArray(plain.size) { i ->
                (plain[i].toInt() xor KUWO_KEY[i % KUWO_KEY.size].toInt()).toByte()
            }
            return "tp=content=16646\r\n\r\n".toByteArray(StandardCharsets.US_ASCII) +
                deflate(Base64.getEncoder().encode(xored))
        }

        /** The inverse of [WordLyricCodecs.miguDecrypt]: the sums climb, the indices ascend, the mix adds. */
        fun encryptMigu(plain: String): String {
            val utf16 = plain.toByteArray(StandardCharsets.UTF_16LE)
            val blocks = Math.max(2, (utf16.size + 7) / 8)
            val bytes = ByteArray(blocks * 8)
            utf16.copyInto(bytes)
            val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val data = LongArray(blocks) { buffer.getLong(it * 8) }
            var sum = 0L
            repeat(6 + 52 / blocks) {
                sum += TEA_DELTA
                val round = ((sum ushr 2) and 3L).toInt()
                for (index in 0 until blocks) {
                    val carry = data[(index + 1) % blocks]
                    val previous = data[(index - 1 + blocks) % blocks]
                    data[index] += mix(carry, previous, sum, TEA_KEY[(index and 3) xor round])
                }
            }
            return data.joinToString("") { String.format(Locale.ROOT, "%016x", it) }
        }

        fun mix(current: Long, previous: Long, sum: Long, key: Long): Long =
            ((current xor sum) + (previous xor key)) xor
                (((previous shr 5) xor (current shl 2)) + ((current shr 3) xor (previous shl 4)))
    }
}

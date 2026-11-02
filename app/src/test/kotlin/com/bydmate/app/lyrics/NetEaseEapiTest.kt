package com.bydmate.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The signed form NetEase's lyric endpoint answers. The test rebuilds the envelope from the published
 * protocol shape instead of calling the production helpers, so a separator, field order or digest
 * written differently than the server expects fails here rather than as an empty lyric on the road.
 */
class NetEaseEapiTest {

    private val songId = 181_775_989L

    @Test fun `the form body is the encrypted envelope the endpoint reads`() {
        val body = NetEaseEapi.formBody(songId)
        assertNotNull(body)
        assertTrue(body!!.startsWith("params="))
        val hex = body.removePrefix("params=")
        // Hex of whole AES blocks; anything else is a padding or encoding mistake.
        assertTrue(hex.length % 32 == 0)
        val parts = decrypt(hex).split(SEPARATOR)
        assertEquals(3, parts.size)
        assertEquals(NetEaseEapi.LYRIC_PATH, parts[0])
        assertEquals(payload(songId), parts[1])
        assertEquals(md5("nobody${NetEaseEapi.LYRIC_PATH}use${parts[1]}md5forencrypt"), parts[2])
    }

    @Test fun `the same song signs to the same bytes`() {
        assertEquals(NetEaseEapi.formBody(songId), NetEaseEapi.formBody(songId))
    }

    @Test fun `a different song is a different envelope`() {
        assertTrue(NetEaseEapi.formBody(songId) != NetEaseEapi.formBody(songId + 1))
    }

    @Test fun `only the lyric path and a real song id are signed`() {
        assertNull(NetEaseEapi.formBody(songId, "/api/song/enhance/player/url"))
        assertNull(NetEaseEapi.formBody(0L))
        assertNull(NetEaseEapi.formBody(-1L))
    }

    private fun payload(id: Long) =
        """{"id":$id,"cp":false,"tv":0,"lv":0,"rv":0,"kv":0,"yv":1,"ytv":0,"yrv":1}"""

    private fun decrypt(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.US_ASCII), "AES"))
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SEPARATOR = "-36cd479b6b5-"
        const val KEY = "e82ckenh8dichen8"
    }
}

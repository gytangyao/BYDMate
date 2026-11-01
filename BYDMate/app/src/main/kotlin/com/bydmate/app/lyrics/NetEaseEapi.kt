package com.bydmate.app.lyrics

import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The request envelope NetEase's own desktop client puts in front of `/eapi/…` endpoints, for the
 * one call BYDMate needs: a song's lyric by id. It is a fixed-key form, not a login: the payload is
 * MD5-digested, glued together with the path and the separator, AES/ECB/PKCS5-encrypted with the
 * public web key and hex-encoded into a `params=` form field.
 *
 * Written from the published shape of the protocol rather than from any third-party implementation,
 * and kept free of Android so the exact bytes are unit-tested.
 */
object NetEaseEapi {

    /** The lyric endpoint's path, as the signing string wants it (without the `/eapi` mount). */
    const val LYRIC_PATH = "/api/song/lyric/v1"

    private const val SEPARATOR = "-36cd479b6b5-"
    private const val KEY = "e82ckenh8dichen8"
    private val ALLOWED_PATHS = setOf(LYRIC_PATH)

    /** `params=<hex>` body for [songId]'s lyric, or null when [path] is not one we signed up for. */
    fun formBody(songId: Long, path: String = LYRIC_PATH): String? {
        if (path !in ALLOWED_PATHS || songId <= 0L) return null
        // `yv`/`yrv` are the word-timed channels: with them at 0 the endpoint answers `lrc` and
        // `tlyric` only, and a display that fills one character at a time has nothing to fill from.
        val payload = """{"id":$songId,"cp":false,"tv":0,"lv":0,"rv":0,"kv":0,"yv":1,"ytv":0,"yrv":1}"""
        val digest = md5Hex("nobody$path" + "use$payload" + "md5forencrypt")
        val plaintext = path + SEPARATOR + payload + SEPARATOR + digest
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.US_ASCII), "AES"))
        return "params=" + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun md5Hex(value: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) append(String.format(Locale.ROOT, "%02x", byte.toInt() and 0xFF))
    }
}

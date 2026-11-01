package com.bydmate.app.data.mysql

/**
 * Strips what a log line must not carry out of the car before it goes into the shared
 * `app_logs` table: coordinates, phone numbers, credentials, VINs, tokens.
 *
 * Deliberately NOT [com.bydmate.app.diagnostics.TraceSanitizer], which is the gate for structured
 * trace *values*: it caps at 80 chars, maps any non-ASCII text to `<text>` and rewrites every
 * number with 4+ decimals to `<num>`. That is right for a field holding user text and wrong for a
 * diagnostic line — cell voltages (4.0125 V), SoC percentages and millisecond durations are exactly
 * what a remote diagnosis needs, and Cyrillic log text is ours, not the driver's. So this redactor
 * removes only whole patterns that identify a person, a place or a secret, and keeps the rest.
 *
 * Every rule is a replacement, never a drop: a line that matches still shows its shape
 * (`soc=71 lat=<coords>`), so the absence of a field is never confused with a redaction.
 */
internal object LogRedactor {

    private const val COORDS = "<coords>"
    private const val SECRET = "<secret>"
    private const val VIN = "<vin>"
    private const val PHONE = "<phone>"

    /** `lat,lon` / `lon lat` decimal pairs. 4+ decimals is ~11 m — real positioning, not a voltage. */
    private val COORD_PAIR = Regex("""-?\d{1,3}\.\d{4,}\s*[,;]?\s*-?\d{1,3}\.\d{4,}""")
    private val GEO_URI = Regex("""(?i)\bgeo:[^\s"]*""")
    /** Anything after `?` in a URL: query strings carry api keys and session tokens. */
    private val URL_QUERY = Regex("""(?i)(https?://[^\s"']*?)\?\S*""")
    /**
     * `token=abc`, `"password": "abc"`, `Authorization: Bearer abc` and friends. The auth scheme
     * word is consumed as part of the match: `Authorization: Bearer <token>` otherwise redacts the
     * word "Bearer" and leaves the credential standing.
     */
    private val SECRET_KV = Regex(
        """(?i)\b(password|passwd|secret|token|api[_-]?key|apikey|access[_-]?key|auth[_-]?token|authorization)\b\s*[=:]\s*"?(?:bearer|basic)?\s*"?[^\s",}]*"""
    )
    /** Telegram bot tokens look like 123456789:AAxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx. */
    private val TG_BOT = Regex("""\b\d{8,12}:AA[A-Za-z0-9_-]{20,}""")
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b""")
    /** VIN is 17 characters and never contains I, O or Q. Uppercase-only keeps it off code words. */
    private val VIN_17 = Regex("""\b(?![0-9]{17}\b)[A-HJ-NPR-Z0-9]{17}\b""")
    /** Mobile numbers in the two regions this app is used in: +7 / 8 + 10, +380 and 9 + 9. */
    private val PHONE_INTL = Regex("""\+\d[\d\s()-]{8,}\d""")
    private val PHONE_LOCAL = Regex("""\b[789]\d{9,10}\b""")
    /** Long hex/base64 runs: device ids, keys, signatures. */
    private val LONG_BLOB = Regex("""\b[0-9a-fA-F]{24,}\b""")

    fun redact(line: String): String {
        var s = line
        s = URL_QUERY.replace(s, "$1?<query>")
        s = JWT.replace(s, "<jwt>")
        s = TG_BOT.replace(s, "<tg-bot>")
        s = SECRET_KV.replace(s, "$1=$SECRET")
        s = GEO_URI.replace(s, "geo:$COORDS")
        s = COORD_PAIR.replace(s, COORDS)
        s = PHONE_INTL.replace(s, PHONE)
        s = PHONE_LOCAL.replace(s, PHONE)
        s = VIN_17.replace(s, VIN)
        s = LONG_BLOB.replace(s, "<blob>")
        return s
    }
}

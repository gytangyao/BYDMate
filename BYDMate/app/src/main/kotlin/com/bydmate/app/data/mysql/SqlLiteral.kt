package com.bydmate.app.data.mysql

/**
 * Renders a Kotlin value as a MySQL literal so every statement can travel through a plain
 * [java.sql.Statement].
 *
 * Prepared statements are not available on this platform: Connector/J's `ClientPreparedStatement`
 * declares `setObject(int, Object, java.sql.SQLType)`, and Android has never shipped
 * `java.sql.SQLType` - it is absent from android.jar at every API level up to 36 - so loading that
 * class throws `NoClassDefFoundError: Failed resolution of: Ljava/sql/SQLType;` on both the head
 * unit and the phone. A desktop JVM does have the type, which is exactly why a PC-side JDBC
 * round-trip test cannot catch it.
 *
 * Quoting avoids backslash escapes for the two characters that matter: a single quote is doubled
 * and a backslash is doubled, which MySQL reads the same way whether or not
 * `NO_BACKSLASH_ESCAPES` is set. Only NUL and SUB, which cannot appear verbatim in a query, get a
 * backslash escape.
 */
internal object SqlLiteral {

    private const val QUOTE = '\''
    private const val BACKSLASH = '\\'
    private const val NUL = '\u0000'
    private const val SUB = '\u001A'

    /** `null` and the non-finite doubles become SQL NULL: MySQL has no NaN or Infinity literal. */
    fun of(value: Any?): String = when (value) {
        null -> "NULL"
        is String -> quote(value)
        is Boolean -> if (value) "1" else "0"
        is Long, is Int, is Short, is Byte -> value.toString()
        is Double -> finite(value)
        is Float -> finite(value.toDouble())
        else -> quote(value.toString())
    }

    private fun finite(d: Double): String =
        if (d.isNaN() || d.isInfinite()) "NULL" else d.toString()

    private fun quote(s: String): String {
        val out = StringBuilder(s.length + 8).append(QUOTE)
        for (c in s) {
            when (c) {
                QUOTE -> out.append(QUOTE).append(QUOTE)
                BACKSLASH -> out.append(BACKSLASH).append(BACKSLASH)
                NUL -> out.append(BACKSLASH).append('0')
                SUB -> out.append(BACKSLASH).append('Z')
                else -> out.append(c)
            }
        }
        return out.append(QUOTE).toString()
    }
}

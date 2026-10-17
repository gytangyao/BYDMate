package com.bydmate.app.data.mysql

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The renderer that replaces JDBC parameter binding, which Android cannot load at all. Its output is
 * what actually runs on the hub, so the quoting rules are the security boundary: one unescaped quote
 * in a log line would otherwise turn appended text into SQL.
 */
class SqlLiteralTest {

    private val quote = '\''
    private val backslash = '\\'
    private val nul = 0.toChar()
    private val sub = 26.toChar()
    private val newline = 10.toChar()

    @Test fun `null becomes SQL NULL`() {
        assertEquals("NULL", SqlLiteral.of(null))
    }

    @Test fun `numbers are plain and need no quotes`() {
        assertEquals("42", SqlLiteral.of(42L))
        assertEquals("7", SqlLiteral.of(7))
        assertEquals("1.5", SqlLiteral.of(1.5))
    }

    /** MySQL has no NaN or Infinity literal, and Room holds both when a sensor drops out. */
    @Test fun `non-finite doubles degrade to NULL instead of a syntax error`() {
        assertEquals("NULL", SqlLiteral.of(Double.NaN))
        assertEquals("NULL", SqlLiteral.of(Double.POSITIVE_INFINITY))
    }

    @Test fun `a boolean is the 1 and 0 the schema expects`() {
        assertEquals("1", SqlLiteral.of(true))
        assertEquals("0", SqlLiteral.of(false))
    }

    @Test fun `an empty string is still a string`() {
        assertEquals("''", SqlLiteral.of(""))
    }

    /**
     * Doubling, not backslash-escaping, is what survives both NO_BACKSLASH_ESCAPES settings, so the
     * same statement stays correct on a server we do not configure.
     */
    @Test fun `quotes and backslashes are doubled`() {
        assertEquals("'od''bread'", SqlLiteral.of("od" + quote + "bread"))
        assertEquals("'C:\\\\temp'", SqlLiteral.of("C:" + backslash + "temp"))
    }

    /** Only the bytes that cannot sit verbatim in a query get a backslash escape. */
    @Test fun `nul and sub are escaped where a newline is not`() {
        assertEquals("'a\\0b'", SqlLiteral.of("a" + nul + "b"))
        assertEquals("'a\\Zb'", SqlLiteral.of("a" + sub + "b"))
        assertEquals("'a\nb'", SqlLiteral.of("a" + newline + "b"))
    }

    /** The whole point of the rewrite: text that looks like SQL stays data. */
    @Test fun `a log line that tries to end the statement is quoted as one string`() {
        assertEquals("'''); DROP TABLE app_logs; -- '", SqlLiteral.of("'); DROP TABLE app_logs; -- "))
    }
}

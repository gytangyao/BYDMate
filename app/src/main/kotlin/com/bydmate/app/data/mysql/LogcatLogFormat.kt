package com.bydmate.app.data.mysql

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A parsed logcat entry. */
internal data class ParsedLog(val ts: Long, val level: String, val tag: String, val msg: String)

/**
 * The logcat text formats DiLink's `logcat -v threadtime` can produce, turned into
 * [ParsedLog]s — kept separate from [CarLogCollector] because the device ring cannot be
 * produced in a test, while every rule below can.
 *
 * `uid` and `year` are optional prefixes/suffixes rather than expected columns: not every vendor
 * build accepts those modifiers, and the collector falls back to a variant that doesn't print
 * them. Absent columns simply disable the corresponding filter, they never lose the line.
 */
internal object LogcatLogFormat {

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val DATE_YEAR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /**
     * `MM-dd` carries no year, and [LocalDateTime.parse] refuses to resolve a date without one, so
     * a stand-in leap year lets February 29 parse too; the real year is substituted afterwards.
     */
    private val DATE_PLAIN = java.time.format.DateTimeFormatterBuilder()
        .appendPattern("MM-dd HH:mm:ss.SSS")
        .parseDefaulting(java.time.temporal.ChronoField.YEAR, 2000L)
        .toFormatter()

    /**
     * threadtime is `MM-dd HH:mm:ss.SSS  PID TID L TAG: msg`; `uid` prefixes it and `year`
     * lengthens the date. Both groups optional, so one regex covers every variant.
     */
    private val HEADER = Regex(
        """^(?:(\d{1,9})\s+)?""" +
            """(\d{4}-\d{2}-\d{2}|\d{2}-\d{2})\s+""" +
            """(\d{2}:\d{2}:\d{2}\.\d{3})\s+""" +
            """(\d{1,7})\s+(\d{1,7})\s+""" +
            """([VDIWEAFS])\s+""" +
            """([^:]{1,64}):\s?(.*)$"""
    )

    /**
     * Parses headers and folds unheaded lines into the entry above them: logd stores a multi-line
     * message (an exception trace) as one entry and prints its tail without a header, so a trace
     * split across rows would lose the frame that threw. [maxMsgChars] is the `app_logs.msg`
     * column width — a long trace is cut at the tail, keeping the frames closest to the cause.
     */
    fun collapse(raw: List<String>, myUid: Int, myPid: Int, maxMsgChars: Int): List<ParsedLog> {
        val out = ArrayList<ParsedLog>(raw.size)
        // Whether the previous raw line was one of ours: a trace tail after a foreign process's
        // line belongs to that process, and folding it into our last record would invent a stack.
        var lastLineWasOurs = false
        for (line in raw) {
            val header = parseHeader(line, myUid, myPid)
            if (header != null) {
                out.add(header)
                lastLineWasOurs = true
                continue
            }
            val continuation = lastLineWasOurs && (line.startsWith("\t") || line.startsWith("    at "))
            if (continuation) {
                val last = out.removeAt(out.size - 1)
                out.add(last.copy(msg = (last.msg + "\n" + line.trimEnd()).take(maxMsgChars)))
                lastLineWasOurs = true
            } else {
                lastLineWasOurs = false
            }
        }
        return out
    }

    /**
     * Keeps every line at or above [minRank] plus the [contextLines] that preceded it: "retry 1/3,
     * retry 2/3, retry 3/3, <exception>" is the diagnosis, the exception alone is not. Lines are
     * redacted on the way out — nothing leaves the car unmasked.
     */
    fun selectWithContext(lines: List<ParsedLog>, minRank: Int, contextLines: Int): List<MysqlLogRecord> {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, l -> if (rank(l.level) >= minRank) keep[i] = true }
        lines.forEachIndexed { i, _ ->
            if (keep[i]) for (j in maxOf(0, i - contextLines) until i) keep[j] = true
        }
        return lines.mapIndexedNotNull { i, l ->
            if (!keep[i]) return@mapIndexedNotNull null
            MysqlLogRecord(ts = l.ts, level = l.level, tag = l.tag, msg = LogRedactor.redact(l.msg))
        }
    }

    /** Rank of a level name or letter: `WARN` and `W` are the same threshold. */
    fun rank(level: String): Int = when (level.trim().uppercase().firstOrNull()) {
        'V' -> 1
        'D' -> 2
        'I' -> 3
        'W' -> 4
        'E', 'A', 'F' -> 5
        else -> 3
    }

    /**
     * A line whose uid column says another app is never ours, even though READ_LOGS lets us see it.
     * Without a uid column the pid has to do: after a restart the previous process' lines are gone
     * from this dump, which is the one thing the uid column buys.
     */
    private fun parseHeader(line: String, myUid: Int, myPid: Int): ParsedLog? {
        val m = HEADER.matchEntire(line) ?: return null
        val g = m.groupValues
        val uid = g[1].toIntOrNull()
        val pid = g[4].toIntOrNull()
        if (uid != null) {
            if (uid != myUid) return null
        } else if (pid != myPid) {
            return null
        }
        val ts = toEpochMs(g[2], g[3]) ?: return null
        return ParsedLog(ts = ts, level = g[6], tag = g[7].trim(), msg = g[8])
    }

    private fun toEpochMs(date: String, time: String): Long? = runCatching {
        val zone = ZoneId.systemDefault()
        if (date.length == 10) {
            LocalDateTime.parse("$date $time", DATE_YEAR).atZone(zone).toInstant().toEpochMilli()
        } else {
            // No year in the format: assume this one, then correct a December line read in January.
            val stamp = LocalDateTime.parse("$date $time", DATE_PLAIN)
            val current = stamp.withYear(LocalDateTime.now().year).atZone(zone).toInstant().toEpochMilli()
            if (current > System.currentTimeMillis() + DAY_MS) {
                stamp.withYear(LocalDateTime.now().year - 1).atZone(zone).toInstant().toEpochMilli()
            } else {
                current
            }
        }
    }.getOrNull()
}

package com.bydmate.app.data.mysql

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log shipping path is the only part of the MySQL hub that can be proven without a device: the
 * logcat ring cannot be manufactured, but every rule that reads it can be handed as text.
 */
class LogShippingTest {

    private val uid = 10123
    private val pid = 4321

    private fun ours(level: String, tag: String, msg: String, time: String = "10-17 12:00:00.000", pid: Int = this.pid) =
        "$time $pid 9999 $level $tag: $msg"

    @Test
    fun `parses a plain threadtime line from our own pid`() {
        val lines = listOf(ours("W", "TrackingService", "webhook cooldown 60 s"))
        val parsed = LogcatLogFormat.collapse(lines, uid, pid, 2000)
        assertEquals(1, parsed.size)
        assertEquals("W", parsed[0].level)
        assertEquals("TrackingService", parsed[0].tag)
        assertEquals("webhook cooldown 60 s", parsed[0].msg)
        assertTrue(parsed[0].ts > 0L)
    }

    /**
     * The column order `logcat -v uid -v year` actually prints on the head unit: uid after the
     * timestamp, not before it. Assuming the other order made every line of every first-variant
     * dump unmatchable, so the collector silently fell back to the second variant.
     */
    @Test
    fun `the uid column sits between the time and the pid`() {
        val ours = "2026-10-17 12:00:00.000 10123 4321 9999 E MysqlSync: sync failed"
        val theirs = "2026-10-17 12:00:00.100 10089 8181 8181 E SystemUI: boom"
        val parsed = LogcatLogFormat.collapse(listOf(ours, theirs), uid, pid, 2000)
        assertEquals(1, parsed.size)
        assertEquals("MysqlSync", parsed[0].tag)
    }

    @Test
    fun `folds a stack trace into the line that threw it`() {
        val lines = listOf(
            ours("E", "TripTracker", "java.lang.IllegalStateException: no session"),
            "\tat com.bydmate.app.data.trips.TripTracker.start(TripTracker.kt:88)",
            "\tat com.bydmate.app.service.TrackingService.loop(TrackingService.kt:1810)",
        )
        val parsed = LogcatLogFormat.collapse(lines, uid, pid, 2000)
        assertEquals(1, parsed.size)
        assertEquals(3, parsed[0].msg.lines().size)
        assertTrue(parsed[0].msg.contains("TripTracker.kt:88"))
    }

    @Test
    fun `does not steal a foreign trace tail`() {
        val lines = listOf(
            ours("E", "TripTracker", "boom"),
            "10-17 12:00:00.500 10089 8181 8181 E SystemUI: theirs",
            "\tat android.view.View.draw(View.java:1)",
        )
        val parsed = LogcatLogFormat.collapse(lines, uid, pid, 2000)
        assertEquals(1, parsed.size)
        assertFalse(parsed[0].msg.contains("View.draw"))
    }

    @Test
    fun `an error drags its context and quiet chatter stays home`() {
        val chatter = (1..30).map { ours("V", "Sampling", "tick $it", time = "10-17 12:00:%02d.000".format(it)) }
        val parsed = LogcatLogFormat.collapse(chatter + ours("E", "MysqlSync", "connection refused"), uid, pid, 2000)
        val kept = LogcatLogFormat.selectWithContext(parsed, minRank = 4, contextLines = 15)
        // 1 error + the 15 lines before it; the 14 older verbose lines are not worth a row each.
        assertEquals(16, kept.size)
        assertEquals("E", kept.last().level)
        assertTrue(kept.take(15).all { it.level == "V" })
    }

    @Test
    fun `threshold accepts both the letter and the level name`() {
        assertEquals(LogcatLogFormat.rank("W"), LogcatLogFormat.rank("WARN"))
        assertTrue(LogcatLogFormat.rank("E") > LogcatLogFormat.rank("I"))
        assertTrue(LogcatLogFormat.rank("D") < LogcatLogFormat.rank("I"))
    }

    @Test
    fun `coordinates, ids and credentials are masked before they leave the car`() {
        val raw = "charging at 55.7512447,37.6184230 tel +79161234567 vin LC0C76CB2N0073730 " +
            "Authorization: Bearer abcdefghijklmnopqrstuvwxyz01 token=sekret123 https://x.io/cb?key=42"
        val out = LogRedactor.redact(raw)
        assertTrue(out, out.contains("<coords>"))
        assertTrue(out, out.contains("<phone>"))
        assertTrue(out, out.contains("<vin>"))
        assertTrue(out, out.contains("<secret>"))
        assertTrue(out, out.contains("?<query>"))
        assertFalse(out, out.contains("55.7512447"))
        assertFalse(out, out.contains("LC0C76CB2N0073730"))
        assertFalse(out, out.contains("abcdefghijklmnopqrstuvwxyz01"))
    }

    @Test
    fun `diagnostic numbers survive redaction`() {
        val line = "cell 4.0125V avg 27.4C soc 71% kwh 12.5 age 1500ms"
        assertEquals(line, LogRedactor.redact(line))
    }

    @Test
    fun `a telegram bot token never rides along`() {
        val out = LogRedactor.redact("send to 123456789:AAHexampleexampleexampleexample0")
        assertTrue(out, out.contains("<tg-bot>"))
        assertFalse(out, out.contains("AAHexample"))
    }

    @Test
    fun `log rows render as a plain insert of seven columns`() {
        val upsert = MysqlUpsertSql.log(
            MysqlLogRecord(ts = 1L, level = "W", tag = "T", msg = "m", runId = "car-aaaa1111"),
            vehicleId = "byd-1",
            source = "car",
        )
        val sql = upsert.render()
        assertTrue(sql, sql.startsWith("INSERT INTO `app_logs`"))
        assertFalse("append-only rows must not upsert", sql.contains("ON DUPLICATE"))
        assertEquals(7, upsert.columns.size)
        assertEquals(7, upsert.values.size)
        // Values go out as literals, not `?` placeholders — see SqlLiteral.
        assertEquals(0, Regex("""\?""").findAll(sql).count())
        assertEquals(7, sql.substringAfter("VALUES (").substringBeforeLast(")").split(",").size)
        assertEquals(listOf("vehicle_id", "source", "ts", "level", "tag", "msg", "run_id"), upsert.columns)
        assertEquals("car-aaaa1111", upsert.values.last())
    }

    @Test
    fun `an empty run id lands as SQL NULL, the same as the phone writes it`() {
        val upsert = MysqlUpsertSql.log(
            MysqlLogRecord(ts = 1L, level = "W", tag = "T", msg = "m"),
            vehicleId = "byd-1",
            source = "car",
        )
        org.junit.Assert.assertNull(upsert.values.last())
    }

    @Test
    fun `an over-long message is cut to the column width`() {
        val upsert = MysqlUpsertSql.log(
            MysqlLogRecord(ts = 1L, level = "E", tag = "T", msg = "x".repeat(5000)),
            vehicleId = "byd-1",
            source = "car",
        )
        assertEquals(2000, (upsert.values[5] as String).length)
    }
}

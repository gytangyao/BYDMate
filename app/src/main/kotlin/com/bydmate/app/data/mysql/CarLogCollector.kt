package com.bydmate.app.data.mysql

import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One line destined for `app_logs`. [runId] is stamped by [CarLogCollector], not parsed: a line has
 * no idea which process wrote it. [marker] rows are the collector talking about itself (shipping
 * capped, logcat unreadable) and never move the cursor in [CarLogCollector.markShipped].
 */
data class MysqlLogRecord(
    val ts: Long,
    val level: String,
    val tag: String,
    val msg: String,
    val runId: String = "",
    val marker: Boolean = false,
)

/**
 * Reads this app's own logcat lines and turns them into [MysqlLogRecord]s for the hub, because the
 * head unit cannot be attached to a debugger — `app_logs` is the remote diagnosis channel.
 *
 * logcat rather than a logging wrapper: BYDMate has ~1300 plain `Log.x()` call sites and no facade,
 * so instrumenting them would be a sweep through half the codebase and would still miss the
 * framework lines (ANRs, lowmemorykiller, ServiceStartIntent timeouts) that explain a head-unit
 * freeze. The device ring IS the log, so the ring is what gets read. Parsing lives in
 * [LogcatLogFormat]; this class is the process, the cursor and the budgets.
 *
 * The read is a `logcat -d` dump that exits immediately — no long-lived process, no file left
 * behind. It runs only when the hub already has a session to attach to (the ignition sync, the
 * throttled live push), because shipping logs means opening a MySQL connection and this car is
 * allowed exactly one of those at a time.
 *
 * Volume is the risk: the 1 Hz sampling loop alone can print thousands of lines an hour. Three
 * limits hold it down — the level threshold (default WARN), a context window around every kept
 * line, and per-flush ([MAX_FLUSH_ROWS]) / per-day ([MAX_DAY_ROWS]) row caps.
 */
@Singleton
class CarLogCollector @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    /** Seam for tests, mirroring [com.bydmate.app.diagnostics.LogRecorder]: a canned dump, no device. */
    var exec: (Array<String>) -> Process = { Runtime.getRuntime().exec(it) }

    private val lock = Any()
    private var runId = ""
    private var previousRunId = ""
    private var runStartTs = 0L

    @Volatile private var lastMarkerMs = 0L

    /**
     * New lines ready to ship, oldest first, already redacted. Empty when shipping is off or nothing
     * notable was written since the last flush. Call [markShipped] only once the rows are in MySQL:
     * the cursor is what makes a failed retry re-send the same lines instead of skipping them.
     */
    suspend fun pendingBatch(): List<MysqlLogRecord> = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext emptyList()
        val now = System.currentTimeMillis()
        val cursor = longPref(SettingsRepository.KEY_MYSQL_LOG_CURSOR_TS)
        val budget = dailyBudget(now)
        if (budget <= 0) return@withContext capMarker(now)

        val minRank = LogcatLogFormat.rank(settingsRepository.getString(SettingsRepository.KEY_MYSQL_LOG_LEVEL, DEFAULT_LEVEL))
        val parsed = dump().filter { it.ts > cursor && it.ts <= now + CLOCK_SLACK_MS }
        if (parsed.isEmpty()) return@withContext emptyList()

        val kept = LogcatLogFormat.selectWithContext(parsed, minRank, CONTEXT_LINES)
        // Nothing crossed the threshold, but the window has been read: park the cursor there so the
        // next dump starts after it instead of re-parsing the same 800 lines every 30 s.
        if (kept.isEmpty()) {
            settingsRepository.setString(SettingsRepository.KEY_MYSQL_LOG_CURSOR_TS, parsed.last().ts.toString())
            return@withContext emptyList()
        }
        val limited = kept.take(minOf(MAX_FLUSH_ROWS, budget))
        val rows = stampRuns(limited)
        if (kept.size > limited.size) rows + listOfNotNull(overflowMarker(now, kept.size, rows.size)) else rows
    }

    /** Advance the cursor and today's row counter after the rows are safely in MySQL. */
    suspend fun markShipped(batch: List<MysqlLogRecord>) {
        if (batch.isEmpty()) return
        val real = batch.filterNot { it.marker }
        val now = System.currentTimeMillis()
        val values = mutableMapOf<String, String>()
        if (real.isNotEmpty()) {
            values[SettingsRepository.KEY_MYSQL_LOG_CURSOR_TS] = real.maxOf { it.ts }.toString()
        }
        val dayStart = longPref(SettingsRepository.KEY_MYSQL_LOG_DAY_START_TS)
        if (now - dayStart > DAY_MS) {
            values[SettingsRepository.KEY_MYSQL_LOG_DAY_START_TS] = now.toString()
            values[SettingsRepository.KEY_MYSQL_LOG_DAY_ROWS] = real.size.toString()
        } else {
            values[SettingsRepository.KEY_MYSQL_LOG_DAY_ROWS] = (intPref(SettingsRepository.KEY_MYSQL_LOG_DAY_ROWS) + real.size).toString()
        }
        settingsRepository.setStrings(values)
    }

    private suspend fun dump(): List<ParsedLog> {
        val myUid = android.os.Process.myUid()
        val myPid = android.os.Process.myPid()
        for (args in LOGCAT_ARG_VARIANTS) {
            val lines = runCatching {
                val process = exec(args)
                val out = process.inputStream.bufferedReader().use { it.readText() }
                val err = process.errorStream.bufferedReader().use { it.readText() }
                if (!process.waitFor(LOGCAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                if (out.isBlank()) Log.w(TAG, "logcat `${args.joinToString(" ")}` empty: ${err.take(200)}")
                out.lineSequence().map { it.trimEnd() }.toList()
            }.getOrDefault(emptyList())
            val parsed = LogcatLogFormat.collapse(lines, myUid, myPid, MysqlUpsertSql.MAX_LOG_MSG_CHARS)
            if (parsed.isNotEmpty()) return parsed
            if (lines.isNotEmpty()) Log.w(TAG, "logcat gave ${lines.size} lines, none matched the header format")
        }
        return emptyList()
    }

    /**
     * One process = one `run_id`, so a query can pull "everything the run that crashed wrote"
     * instead of an arbitrary time window. The id that was in prefs before this process claimed one
     * belongs to the previous run — whose lines are still in the logcat ring and are exactly what we
     * want after a crash, so they are labelled with it rather than passed off as this boot.
     */
    private suspend fun stampRuns(rows: List<MysqlLogRecord>): List<MysqlLogRecord> {
        ensureRunIds()
        return rows.map {
            it.copy(runId = if (it.ts < runStartTs && previousRunId.isNotEmpty()) previousRunId else runId)
        }
    }

    private suspend fun ensureRunIds() {
        if (runId.isNotEmpty()) return
        val now = System.currentTimeMillis()
        val stored = settingsRepository.getString(SettingsRepository.KEY_MYSQL_LOG_RUN_ID, "")
        val fresh = "car-" + UUID.randomUUID().toString().take(8)
        // Reads happen before the lock (a suspend call cannot sit inside a critical section); the
        // lock only decides which of two racing flushes this process remembers as its own id.
        synchronized(lock) {
            if (runId.isEmpty()) {
                previousRunId = stored
                runId = fresh
                runStartTs = now
            }
        }
        settingsRepository.setStrings(
            mapOf(
                SettingsRepository.KEY_MYSQL_LOG_RUN_ID to runId,
                SettingsRepository.KEY_MYSQL_LOG_RUN_START_TS to runStartTs.toString(),
            )
        )
    }

    /** Rows this flush may still write, after today's counter. */
    private suspend fun dailyBudget(now: Long): Int {
        val dayStart = longPref(SettingsRepository.KEY_MYSQL_LOG_DAY_START_TS)
        if (now - dayStart > DAY_MS) return MAX_DAY_ROWS
        return MAX_DAY_ROWS - intPref(SettingsRepository.KEY_MYSQL_LOG_DAY_ROWS)
    }

    /** Once a day, not once a flush: a cap notice every 30 s would itself blow the cap it reports. */
    private fun capMarker(now: Long): List<MysqlLogRecord> {
        if (now - lastMarkerMs < MARKER_COOLDOWN_MS) return emptyList()
        lastMarkerMs = now
        return listOf(
            MysqlLogRecord(
                ts = now, level = "W", tag = TAG, marker = true,
                msg = "app_logs daily cap of $MAX_DAY_ROWS rows reached; lines are skipped until the day rolls over",
            )
        )
    }

    private fun overflowMarker(now: Long, pending: Int, shipped: Int): MysqlLogRecord? {
        // Same cooldown rule: this fires while the queue is saturated, i.e. on every flush.
        if (now - lastMarkerMs < MARKER_COOLDOWN_MS) return null
        lastMarkerMs = now
        return MysqlLogRecord(
            ts = now, level = "W", tag = TAG, marker = true,
            msg = "log shipping capped at $shipped rows this flush, $pending pending (logcat ring can rotate before they go)",
        )
    }

    private suspend fun enabled(): Boolean =
        settingsRepository.getString(SettingsRepository.KEY_MYSQL_LOGS_ENABLED, "true") == "true"

    private suspend fun longPref(key: String): Long =
        settingsRepository.getString(key, "0").toLongOrNull() ?: 0L

    private suspend fun intPref(key: String): Int =
        settingsRepository.getString(key, "0").toIntOrNull() ?: 0

    companion object {
        private const val TAG = "MysqlLogs"
        private const val DEFAULT_LEVEL = "W"
        internal const val MAX_FLUSH_ROWS = 300
        internal const val MAX_DAY_ROWS = 20_000
        private const val CONTEXT_LINES = 15
        private const val MARKER_COOLDOWN_MS = 10 * 60 * 1000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val LOGCAT_TIMEOUT_MS = 10_000L

        /** The head unit's clock can lead the log buffer by seconds just after boot. */
        private const val CLOCK_SLACK_MS = 60_000L

        /** `-t` bounds the dump: newest lines are the interesting ones and the ring is ~256 KB. */
        internal val LOGCAT_ARG_VARIANTS = arrayOf(
            arrayOf("logcat", "-d", "-v", "uid", "-v", "threadtime", "-v", "year", "-t", "800"),
            arrayOf("logcat", "-d", "-v", "threadtime", "-t", "800"),
        )
    }
}

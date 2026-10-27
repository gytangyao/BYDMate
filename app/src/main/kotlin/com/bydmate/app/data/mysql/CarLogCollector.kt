package com.bydmate.app.data.mysql

import android.util.Log
import com.bydmate.app.data.charging.CatchUpJournal
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.diagnostics.LogRecorder
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
 *
 * The threshold is also the blind spot: a verdict the driver needs is usually INFO, and INFO only
 * survives as context under a WARN. So one thing bypasses logcat — [CatchUpJournal], the ring of
 * charging decisions the detector keeps in prefs, ships whole whenever it changes
 * ([chargeJournalBatch]).
 */
@Singleton
class CarLogCollector @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val journal: CatchUpJournal,
) {
    /** Seam for tests, mirroring [com.bydmate.app.diagnostics.LogRecorder]: a canned dump, no device. */
    var exec: (Array<String>) -> Process = { Runtime.getRuntime().exec(it) }

    private val lock = Any()
    private var runId = ""
    private var previousRunId = ""
    private var runStartTs = 0L

    @Volatile private var lastMarkerMs = 0L
    @Volatile private var lastEmptyWarnMs = 0L

    /** Verdicts the hub has this ring for, and the set this flush offered: only a confirmed write moves the former. */
    @Volatile private var journalSentVerdicts: List<String>? = null
    @Volatile private var journalPendingVerdicts: List<String>? = null

    /**
     * New lines ready to ship, oldest first, already redacted. Empty when shipping is off or nothing
     * notable was written since the last flush. Call [markShipped] only once the rows are in MySQL:
     * the cursor is what makes a failed retry re-send the same lines instead of skipping them.
     */
    suspend fun pendingBatch(): List<MysqlLogRecord> = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext emptyList()
        val rows = logcatBatch() + chargeJournalBatch()
        // Stamping claims a run id in prefs, so an empty flush must not reach it.
        if (rows.isEmpty()) emptyList() else stampRuns(rows)
    }

    private suspend fun logcatBatch(): List<MysqlLogRecord> {
        val now = System.currentTimeMillis()
        val cursor = longPref(SettingsRepository.KEY_MYSQL_LOG_CURSOR_TS)
        val budget = dailyBudget(now)
        if (budget <= 0) return capMarker(now)

        val minRank = LogcatLogFormat.rank(settingsRepository.getString(SettingsRepository.KEY_MYSQL_LOG_LEVEL, DEFAULT_LEVEL))
        val parsed = dump().filter { it.ts > cursor && it.ts <= now + CLOCK_SLACK_MS }
        if (parsed.isEmpty()) return emptyList()

        val kept = LogcatLogFormat.selectWithContext(parsed, minRank, CONTEXT_LINES)
        // Nothing crossed the threshold, but the window has been read: park the cursor there so the
        // next dump starts after it instead of re-parsing the same tail at every flush.
        if (kept.isEmpty()) {
            settingsRepository.setString(SettingsRepository.KEY_MYSQL_LOG_CURSOR_TS, parsed.last().ts.toString())
            return emptyList()
        }
        val limited = kept.take(minOf(MAX_FLUSH_ROWS, budget))
        return if (kept.size > limited.size) limited + listOfNotNull(overflowMarker(now, kept.size, limited.size))
        else limited
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
        if (batch.any { it.tag == JOURNAL_TAG }) journalSentVerdicts = journalPendingVerdicts
    }

    /**
     * The catch-up verdicts, as one line whenever they change.
     *
     * `runCatchUp` says exactly why a charge produced no row — `NO_DELTA soc=98 prev=98`,
     * `STILL_CHARGING gun=1`, `autoservice client not available` — but it says it at INFO, and the
     * threshold is WARN: an INFO line only reaches the hub when it happens to sit within
     * [CONTEXT_LINES] of something that warns, and this car writes ~30 log lines a second. Three days
     * of charging that produced no rows produced not one detector line in `app_logs` either (2026-10-20),
     * which is why the cause could not be read from the cloud. [CatchUpJournal] holds the same
     * verdicts in prefs, does not rotate, and collapses repeats — so the ring ships, and one row
     * carries the whole explanation.
     */
    private suspend fun chargeJournalBatch(): List<MysqlLogRecord> {
        val entries = journal.entries()
        if (entries.isEmpty()) return emptyList()
        val verdicts = entries.map { CatchUpJournal.verdictOf(it) }
        if (verdicts == journalSentVerdicts) return emptyList()
        journalPendingVerdicts = verdicts
        return listOf(
            MysqlLogRecord(
                ts = System.currentTimeMillis(),
                level = "I",
                tag = JOURNAL_TAG,
                msg = LogRedactor.redact(journalMsg(entries)),
                marker = true,
            )
        )
    }

    /** Newest verdicts that fit `app_logs.msg`, oldest first: the tail of the ring is the story. */
    private fun journalMsg(entries: List<String>): String {
        val kept = ArrayDeque<String>()
        var used = JOURNAL_PREFIX.length
        for (entry in entries.asReversed()) {
            val cost = entry.length + if (kept.isEmpty()) 0 else JOURNAL_SEPARATOR.length
            if (used + cost > MysqlUpsertSql.MAX_LOG_MSG_CHARS) break
            kept.addFirst(entry)
            used += cost
        }
        return JOURNAL_PREFIX + kept.joinToString(JOURNAL_SEPARATOR)
    }

    private suspend fun dump(): List<ParsedLog> {
        val myUid = android.os.Process.myUid()
        val myPid = android.os.Process.myPid()
        val tried = ArrayList<String>()
        for ((name, args) in logcatArgVariants(myPid)) {
            val lines = runCatching {
                val process = exec(args)
                val out = process.inputStream.bufferedReader().use { it.readText() }
                val err = process.errorStream.bufferedReader().use { it.readText() }
                if (!process.waitFor(LOGCAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                // A rejected modifier is a property of this firmware, not news: DEBUG keeps it out
                // of the WARN-level hub rows that made `MysqlLogs: logcat 'pid' empty` 121 warnings
                // in 17 h — all of them describing a fallback that then worked.
                if (out.isBlank()) Log.d(TAG, "logcat `$name` empty: ${err.take(200)}")
                out.lineSequence().map { it.trimEnd() }.toList()
            }.getOrDefault(emptyList())
            val parsed = LogcatLogFormat.collapse(lines, myUid, myPid, MysqlUpsertSql.MAX_LOG_MSG_CHARS)
            if (parsed.isNotEmpty()) return parsed
            // "4000 lines, none ours" (logd was read fine, the window simply had no app line) reads
            // very differently from "0 lines" (the modifier was rejected or the ring is empty), so
            // both are recorded.
            tried += "$name=${lines.size}"
        }
        warnEmptyThrottled(tried)
        return emptyList()
    }

    /** One warning per cooldown: an empty dump repeats every flush and is not a new fact each time. */
    private fun warnEmptyThrottled(tried: List<String>) {
        val now = System.currentTimeMillis()
        val msg = "logcat: no own lines in any variant, dumped: ${tried.joinToString(", ")}"
        if (now - lastEmptyWarnMs < EMPTY_WARN_COOLDOWN_MS) {
            Log.d(TAG, msg)
        } else {
            lastEmptyWarnMs = now
            Log.w(TAG, msg)
        }
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

        /** Same tag the detector logs under, so one query finds both its lines and the shipped ring. */
        internal const val JOURNAL_TAG = "AutoserviceDetector"
        private const val JOURNAL_PREFIX = "catch-up verdicts: "
        private const val JOURNAL_SEPARATOR = " | "
        private const val EMPTY_WARN_COOLDOWN_MS = 10 * 60 * 1000L

        /**
         * Bounded dumps tried in order until one contains a line of this process.
         *
         * The tag filter comes second: logd selects our ~100 known tags itself, so a ring 4000
         * lines deep in system chatter still yields the app's own sparse lines (the plain tail
         * often held none — the remote `app_logs` only ever saw the collector's own warning).
         * The uid variant stays first because the uid survives a process restart, so the
         * previous run's lines (the ones that explain a crash) are still ours; `--pid` makes
         * logd filter by process; the plain dump is the last resort for builds that reject
         * the modifiers.
         */
        internal fun logcatArgVariants(pid: Int): List<Pair<String, Array<String>>> = listOf(
            "uid" to arrayOf("logcat", "-d", "-v", "uid", "-v", "threadtime", "-v", "year", "-t", "4000"),
            "tags" to (
                arrayOf("logcat", "-d", "-v", "threadtime", "-t", "4000", "-s") +
                    LogRecorder.filterSpecs() + "MysqlLogs:*"
                ),
            "pid" to arrayOf("logcat", "-d", "--pid=$pid", "-v", "threadtime", "-t", "4000"),
            "plain" to arrayOf("logcat", "-d", "-v", "threadtime", "-t", "4000"),
        )
    }
}

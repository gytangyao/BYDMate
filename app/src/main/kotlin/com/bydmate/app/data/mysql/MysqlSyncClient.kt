package com.bydmate.app.data.mysql

import android.util.Log
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TripEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the car's Room data into the shared MySQL hub. The phone reads the same tables, so this
 * class is the only place the car touches MySQL. Every method is a blocking JDBC call wrapped in
 * [Dispatchers.IO]; WorkManager and the settings "test" button are the only callers, so there is
 * no queue or retry here — a failed sync just runs again on the next scheduled tick.
 *
 * Connection budget: this head unit holds **at most one** session to the hub, enforced by
 * [connectionGate], and each session lives only for the duration of one call (opened with
 * `autoReconnect=false`, idle-reaped by the server after 45 s, closed by [use]). Nothing here
 * pools or caches connections, so the car's worst case is 1 live + 0 idle sessions.
 */
@Singleton
class MysqlSyncClient @Inject constructor() {

    /**
     * Permits == max concurrent hub sessions from this process. One, because the two callers (the
     * ignition history sync and the 30 s live car_state push) can easily overlap — the sync runs
     * right at ignition, which is exactly when sampling starts — and letting them each open their
     * own socket would double the count for data that can wait its turn or simply be dropped.
     */
    private val connectionGate = Semaphore(1)

    /** Opens the hub, returns the server version string. Used by the settings test button. */
    suspend fun testConnection(cfg: MysqlConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            withHubConnection(cfg, GATE_WAIT_TEST_MS) { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT VERSION()").use { rs -> if (rs.next()) rs.getString(1) else "?" }
                }
            }
        }
    }

    /**
     * Upserts every row in [trips]/[charges]/[batterySnapshots], appends one [carState] sample and
     * the [logs] lines, and drops rows older than [pruneRetentionDays] (`app_logs`) and
     * [pruneCarStateDays] (`car_state`) — all inside a single transaction on a single session.
     * Returns how many rows each table touched. An empty list is a no-op for that table; a null
     * [carState] skips the sample row; a null retention leaves that table alone for this pass.
     *
     * [gateWaitMs] is how long to queue behind whoever holds the only permit: the history sync waits
     * (its data is worth serialising), a live sample waits for nothing (see [tryPushCarState]).
     */
    suspend fun syncAll(
        cfg: MysqlConfig,
        trips: List<TripEntity>,
        charges: List<ChargeEntity>,
        batterySnapshots: List<BatterySnapshotEntity>,
        carState: CarStateSnapshot?,
        logs: List<MysqlLogRecord> = emptyList(),
        pruneRetentionDays: Int? = null,
        pruneCarStateDays: Int? = null,
        gateWaitMs: Long = GATE_WAIT_SYNC_MS,
    ): Result<SyncOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            withHubConnection(cfg, gateWaitMs) { c ->
                val auto = c.autoCommit
                c.autoCommit = false
                try {
                    val nTrips = batchUpsert(c, trips.map { MysqlUpsertSql.trips(it, cfg.vehicleId, now) })
                    val nCharges = batchUpsert(c, charges.map { MysqlUpsertSql.charges(it, cfg.vehicleId, now) })
                    val nSnaps = batchUpsert(c, batterySnapshots.map { MysqlUpsertSql.batterySnapshots(it, cfg.vehicleId, now) })
                    val nState = carState?.let { execUpsert(c, MysqlUpsertSql.carState(it, cfg.vehicleId)) } ?: 0
                    val nLogs = batchUpsert(c, logs.map { MysqlUpsertSql.log(it, cfg.vehicleId, LOG_SOURCE) })
                    val nPruned = pruneRetentionDays?.let { pruneLogs(c, cfg.vehicleId, it) } ?: 0
                    val nPrunedState = pruneCarStateDays?.let { pruneCarState(c, cfg.vehicleId, it) } ?: 0
                    c.commit()
                    SyncOutcome(nTrips, nCharges, nSnaps, nState, nLogs, nPruned, nPrunedState)
                } catch (e: Exception) {
                    runCatching { c.rollback() }
                    throw e
                } finally {
                    c.autoCommit = auto
                }
            }
        }
    }

    /** Appends one live [CarStateSnapshot] (and any [logs]); fails fast when the sync holds the permit. */
    suspend fun tryPushCarState(
        cfg: MysqlConfig,
        carState: CarStateSnapshot,
        logs: List<MysqlLogRecord> = emptyList(),
    ): Result<SyncOutcome> =
        syncAll(cfg, emptyList(), emptyList(), emptyList(), carState, logs, gateWaitMs = 0L)

    private fun <T> withHubConnection(cfg: MysqlConfig, gateWaitMs: Long, block: (Connection) -> T): T {
        // tryAcquire with a bound rather than acquire(): an unbounded wait would park a worker
        // thread forever behind a half-open TCP session, which is how you end up with a pile of
        // blocked threads instead of the single connection this gate exists to guarantee.
        if (!connectionGate.tryAcquire(gateWaitMs, TimeUnit.MILLISECONDS)) {
            throw HubBusyException("Hub session in use; no second connection is allowed")
        }
        try {
            ensureDriver()
            DriverManager.getConnection(cfg.jdbcUrl(), cfg.user, cfg.password).use { c -> return block(c) }
        } finally {
            connectionGate.release()
        }
    }

    /**
     * Multi-row `INSERT … ON DUPLICATE KEY UPDATE` per table. A JDBC prepared statement is off the
     * table on Android (see [SqlLiteral]), so the rows arrive already rendered; batching them keeps
     * the round-trip count at one per table on the single permitted session.
     *
     * The chunk cap exists because the first sync after an upgrade carries every trip and charge the
     * car ever recorded. One statement sized to all of Room would either blow past
     * `max_allowed_packet` — an error, not a slow write — or hold the car's only session open far
     * past its 45 s idle limit.
     */
    private fun batchUpsert(conn: Connection, upserts: List<Upsert>): Int {
        if (upserts.isEmpty()) return 0
        var total = 0
        conn.createStatement().use { st ->
            for (chunk in upserts.chunked(MAX_ROWS_PER_STATEMENT)) {
                total += st.executeUpdate(Upsert.render(chunk))
            }
        }
        return total
    }

    private fun execUpsert(conn: Connection, upsert: Upsert): Int =
        conn.createStatement().use { st -> st.executeUpdate(upsert.render()) }

    /**
     * Retention, so the append-only tables don't become the biggest thing on the NAS. See
     * [MysqlUpsertSql.logPrune] and [MysqlUpsertSql.carStatePrune] for why the cutoff comes from
     * the server's clock and (for logs) why both writers' rows are in scope.
     *
     * `LIMIT` bounds one statement: a first pass after months of shipping would otherwise delete
     * millions of rows in one transaction and hold the car's only permitted session past its socket
     * timeout. A full batch means more is waiting, so passes repeat until one comes back short — a
     * sync that runs once per ignition must not spend its whole window on janitor work.
     */
    private fun pruneLogs(conn: Connection, vehicleId: String, retentionDays: Int): Int =
        pruneInBatches(
            conn = conn,
            sql = MysqlUpsertSql.logPrune(vehicleId, retentionDays, PRUNE_BATCH_ROWS),
            batchRows = PRUNE_BATCH_ROWS,
            maxPasses = MAX_PRUNE_PASSES,
        )

    /**
     * `car_state` rows are much larger than log lines (the raw snapshot rides along), so one pass
     * drops fewer of them: [CAR_STATE_PRUNE_BATCH_ROWS] × [MAX_CAR_STATE_PRUNE_PASSES] bounds a
     * single sync to a few thousand rows instead of tens of MB of JSON deleted per transaction.
     */
    private fun pruneCarState(conn: Connection, vehicleId: String, retentionDays: Int): Int =
        pruneInBatches(
            conn = conn,
            sql = MysqlUpsertSql.carStatePrune(vehicleId, retentionDays, CAR_STATE_PRUNE_BATCH_ROWS),
            batchRows = CAR_STATE_PRUNE_BATCH_ROWS,
            maxPasses = MAX_CAR_STATE_PRUNE_PASSES,
        )

    private fun pruneInBatches(conn: Connection, sql: String, batchRows: Int, maxPasses: Int): Int {
        var total = 0
        conn.createStatement().use { st ->
            for (pass in 1..maxPasses) {
                val deleted = st.executeUpdate(sql)
                total += deleted
                if (deleted < batchRows) break
            }
        }
        return total
    }

    private fun ensureDriver() {
        try {
            Class.forName(MysqlConfig.DRIVER_CLASS)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "MySQL JDBC driver not bundled", e)
            throw IllegalStateException("MySQL JDBC driver missing", e)
        }
    }

    companion object {
        private const val TAG = "MysqlSync"

        /** History sync queues behind a live push for up to this long — its rows can wait. */
        private const val GATE_WAIT_SYNC_MS = 45_000L

        /** The test button should say "busy" quickly instead of spinning. */
        private const val GATE_WAIT_TEST_MS = 10_000L

        /** `app_logs.source` for this writer; the phone app writes its own value to the same table. */
        const val LOG_SOURCE = "car"

        private const val PRUNE_BATCH_ROWS = 5_000

        /** Rows a single sync may drop at most: 50 000, which is a month of driving on this hub. */
        private const val MAX_PRUNE_PASSES = 10

        /** `car_state` rows carry the raw JSON snapshot, so a delete batch stays four times smaller. */
        private const val CAR_STATE_PRUNE_BATCH_ROWS = 1_000

        /** At most 5 000 live samples per sync: several days of shrink-back, never one huge delete. */
        private const val MAX_CAR_STATE_PRUNE_PASSES = 5

        /** Rows per rendered `INSERT`: 200 log lines at the 2 000-char cap is ~400 KB, well inside any server's packet limit. */
        private const val MAX_ROWS_PER_STATEMENT = 200
    }
}

data class SyncOutcome(
    val trips: Int,
    val charges: Int,
    val batterySnapshots: Int,
    val carState: Int,
    val logs: Int = 0,
    val prunedLogs: Int = 0,
    val prunedCarState: Int = 0,
)

/**
 * The single permitted hub session was already in use. Deliberately not an
 * [IllegalStateException]: the worker treats that as "not configured" and stops, whereas a busy
 * hub is worth a backoff retry.
 */
class HubBusyException(message: String) : RuntimeException(message)

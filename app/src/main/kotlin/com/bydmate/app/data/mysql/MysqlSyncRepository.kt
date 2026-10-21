package com.bydmate.app.data.mysql

import android.util.Log
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.dao.CounterSnapshotDao
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Glue between the car's Room database and the shared MySQL hub. Reads the connection settings,
 * enumerates every trip / charge / battery snapshot, pages the append-only detail tables from
 * their cursors, collects the log lines the sampling loop has printed, and hands them to
 * [MysqlSyncClient] as one idempotent pass over one connection. Trips and charges carry no
 * per-row mutation stamp, so that whole set is re-upserted each run — cheap for a single car's
 * history, and it also propagates edits to already-finished trips (cost recalculated after the
 * fact). Detail rows are append-only, so they ship once and only the cursor advances.
 */
@Singleton
class MysqlSyncRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val tripDao: TripDao,
    private val chargeDao: ChargeDao,
    private val batterySnapshotDao: BatterySnapshotDao,
    private val tripPointDao: TripPointDao,
    private val chargePointDao: ChargePointDao,
    private val idleDrainDao: IdleDrainDao,
    private val ruleLogDao: RuleLogDao,
    private val vehicleWriteLogDao: VehicleWriteLogDao,
    private val counterSnapshotDao: CounterSnapshotDao,
    private val logCollector: CarLogCollector,
    private val client: MysqlSyncClient,
) {
    /** Live car state is a separate, service-driven concern; the historical sync skips it. */
    suspend fun syncHistory(): Result<SyncOutcome> {
        val cfg = loadConfig()
        if (!cfg.isComplete) {
            Log.i(TAG, "sync skipped: MySQL not configured")
            return Result.failure(IllegalStateException("MySQL not configured"))
        }
        val trips = tripDao.getAllSnapshot()
        val charges = chargeDao.getAllSnapshot()
        val snapshots = batterySnapshotDao.getAllSnapshot()
        val logs = logCollector.pendingBatch()
        val now = System.currentTimeMillis()
        val prune = pruneDue(now)

        val tripCursor = pairCursor(SettingsRepository.KEY_MYSQL_CURSOR_TRIP_POINTS)
        val tripPoints = tripPointDao.getForSync(tripCursor.first, tripCursor.second, TRIP_POINTS_PER_SYNC)
        val chargeCursor = pairCursor(SettingsRepository.KEY_MYSQL_CURSOR_CHARGE_POINTS)
        val chargePoints = chargePointDao.getForSync(chargeCursor.first, chargeCursor.second, CHARGE_POINTS_PER_SYNC)
        val ruleCursor = longCursor(SettingsRepository.KEY_MYSQL_CURSOR_RULE_LOGS)
        val ruleLogs = ruleLogDao.getForSync(ruleCursor, RULE_LOGS_PER_SYNC)
        val writeCursor = longCursor(SettingsRepository.KEY_MYSQL_CURSOR_VEHICLE_WRITES)
        val vehicleWrites = vehicleWriteLogDao.getForSync(writeCursor, VEHICLE_WRITES_PER_SYNC)
        val counterCursor = longCursor(SettingsRepository.KEY_MYSQL_CURSOR_COUNTER_SNAPSHOTS)
        val counterSnapshots = counterSnapshotDao.getForSync(counterCursor, COUNTER_SNAPSHOTS_PER_SYNC)
        // Rows of a drain are updated until it closes, so this table is re-upserted whole.
        val idleDrains = idleDrainDao.getAll()

        Log.i(
            TAG,
            "syncing ${trips.size} trips, ${charges.size} charges, ${snapshots.size} snapshots, " +
                "${tripPoints.size} trip points, ${chargePoints.size} charge points, " +
                "${idleDrains.size} idle drains, ${ruleLogs.size} rule logs, " +
                "${vehicleWrites.size} writes, ${counterSnapshots.size} counters, ${logs.size} log lines",
        )
        val outcome = client.syncAll(
            cfg, trips, charges, snapshots, carState = null, logs = logs,
            tripPoints = tripPoints, chargePoints = chargePoints, idleDrains = idleDrains,
            ruleLogs = ruleLogs, vehicleWrites = vehicleWrites, counterSnapshots = counterSnapshots,
            pruneRetentionDays = if (prune) LOG_RETENTION_DAYS else null,
            pruneCarStateDays = if (prune) CAR_STATE_RETENTION_DAYS else null,
            pruneDetailDays = if (prune) DETAIL_RETENTION_DAYS else null,
        )
        outcome.onSuccess {
            settingsRepository.setMysqlLastSyncTs(now)
            logCollector.markShipped(logs)
            // One transactional write: a half-saved set of cursors would skip a page on the next
            // sync while its rows only exist on the car.
            val cursors = buildMap {
                tripPoints.lastOrNull()?.let { put(SettingsRepository.KEY_MYSQL_CURSOR_TRIP_POINTS, "${it.tripId}:${it.id}") }
                chargePoints.lastOrNull()?.let { put(SettingsRepository.KEY_MYSQL_CURSOR_CHARGE_POINTS, "${it.chargeId}:${it.id}") }
                ruleLogs.lastOrNull()?.let { put(SettingsRepository.KEY_MYSQL_CURSOR_RULE_LOGS, it.id.toString()) }
                vehicleWrites.lastOrNull()?.let { put(SettingsRepository.KEY_MYSQL_CURSOR_VEHICLE_WRITES, it.id.toString()) }
                counterSnapshots.lastOrNull()?.let { put(SettingsRepository.KEY_MYSQL_CURSOR_COUNTER_SNAPSHOTS, it.id.toString()) }
            }
            if (cursors.isNotEmpty()) settingsRepository.setStrings(cursors)
            if (prune) {
                settingsRepository.setString(SettingsRepository.KEY_MYSQL_LOG_PRUNED_TS, now.toString())
            }
        }.onFailure { Log.w(TAG, "sync failed: ${it.message}") }
        return outcome
    }

    /** "parentId:pointId" for a point-table cursor; malformed values restart from the beginning. */
    private suspend fun pairCursor(key: String): Pair<Long, Long> {
        val parts = settingsRepository.getString(key, "0:0").split(':')
        return (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to
            (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    private suspend fun longCursor(key: String): Long =
        settingsRepository.getString(key, "0").toLongOrNull() ?: 0L

    /**
     * Fire-and-forget live dashboard row from the sampling loop; throttled by the caller. Refuses
     * instantly when the history sync owns the car's only hub session — one stale sample on the
     * phone is cheaper than a queue of them each retrying over 1 Hz sampling. Carries the pending
     * log lines too, which is what makes a live problem debuggable while it is still happening.
     */
    suspend fun pushCarState(snapshot: CarStateSnapshot): Result<Unit> {
        val cfg = loadConfig()
        if (!cfg.isComplete) return Result.failure(IllegalStateException("MySQL not configured"))
        val logs = logCollector.pendingBatch()
        val outcome = client.tryPushCarState(cfg, snapshot, logs)
        outcome.onSuccess { logCollector.markShipped(logs) }
        return outcome.map { }
    }

    /** Newest recorded state-of-health, or null if no battery snapshot exists yet. */
    suspend fun latestSohPct(): Double? = batterySnapshotDao.getLast()?.sohPercent

    /** Test the hub with an arbitrary config (the Settings form's values, before they're saved). */
    suspend fun test(cfg: MysqlConfig): Result<String> = client.testConnection(cfg)

    /**
     * Stored settings, with the compiled-in hub answering wherever a value was never set. A stored
     * blank counts as "never set" rather than "cleared": a build from before the defaults existed
     * saved empty strings, and the car should still sync after an upgrade.
     */
    suspend fun loadConfig(): MysqlConfig = MysqlConfig(
        host = settingsRepository.getString(SettingsRepository.KEY_MYSQL_HOST, "").orFallback(MysqlDefaults.HOST),
        port = settingsRepository.getString(SettingsRepository.KEY_MYSQL_PORT, "").orFallback(MysqlDefaults.PORT)
            .toIntOrNull() ?: 0,
        user = settingsRepository.getString(SettingsRepository.KEY_MYSQL_USER, "").orFallback(MysqlDefaults.USER),
        password = settingsRepository.getString(SettingsRepository.KEY_MYSQL_PASSWORD, "")
            .orFallback(MysqlDefaults.PASSWORD),
        database = settingsRepository.getString(SettingsRepository.KEY_MYSQL_DATABASE, "").orFallback(MysqlDefaults.DATABASE),
        vehicleId = settingsRepository.getMysqlVehicleId().orFallback(MysqlDefaults.VEHICLE_ID),
    )

    private fun String.orFallback(default: String): String = trim().ifEmpty { default }

    suspend fun isEnabled(): Boolean =
        settingsRepository.getString(SettingsRepository.KEY_MYSQL_ENABLED, "true") != "false"

    /**
     * The janitor pass — old `app_logs` lines and old `car_state` samples — runs on at most one
     * sync per [PRUNE_INTERVAL_MS], from the same transaction that writes new rows: a separate
     * pruning job would be a second hub session for no benefit. False when a pass isn't due.
     *
     * The cadence is hourly rather than daily because a pass deletes at most [MysqlSyncClient]'s
     * batch per statement: a hub that has run for a month has far more to drop, and a daily pass
     * would delete slower than the apps write. The stamp is one for both tables, so the two
     * deletes always travel together.
     */
    private suspend fun pruneDue(now: Long): Boolean {
        val lastPruned = settingsRepository.getString(SettingsRepository.KEY_MYSQL_LOG_PRUNED_TS, "0")
            .toLongOrNull() ?: 0L
        return now - lastPruned >= PRUNE_INTERVAL_MS
    }

    companion object {
        private const val TAG = "MysqlSync"
        private const val PRUNE_INTERVAL_MS = 60 * 60 * 1000L

        /** How far back `app_logs` is kept on the hub: one month, counting both writers' rows. */
        private const val LOG_RETENTION_DAYS = 30

        /**
         * How far back live `car_state` samples are kept: three months, the longest the owner wants
         * retained. This is the one table that grows at a fixed rate (a sample every 30 s, each
         * carrying the full raw snapshot), so it is the one that needs a ceiling; trips, charges
         * and battery snapshots grow with usage and stay.
         */
        private const val CAR_STATE_RETENTION_DAYS = 90

        /** Same ceiling for the detail tables; trip points are the only one that really grows. */
        private const val DETAIL_RETENTION_DAYS = 90

        // Per-sync page sizes. The first run after an upgrade backfills everything the car already
        // holds, so these bound one ignition's session: 20 000 points ≈ 100 statements of 200 rows.
        private const val TRIP_POINTS_PER_SYNC = 20_000
        private const val CHARGE_POINTS_PER_SYNC = 20_000
        private const val COUNTER_SNAPSHOTS_PER_SYNC = 10_000
        private const val RULE_LOGS_PER_SYNC = 2_000
        private const val VEHICLE_WRITES_PER_SYNC = 500
    }
}

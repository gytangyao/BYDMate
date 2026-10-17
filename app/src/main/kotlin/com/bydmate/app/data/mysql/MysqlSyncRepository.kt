package com.bydmate.app.data.mysql

import android.util.Log
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Glue between the car's Room database and the shared MySQL hub. Reads the connection settings,
 * enumerates every trip / charge / battery snapshot, collects the log lines the sampling loop has
 * printed, and hands them to [MysqlSyncClient] as one idempotent pass over one connection. The Room
 * tables carry no per-row mutation stamp, so the whole set is re-upserted each run — cheap for a
 * single car's history and it also propagates edits to already-finished trips (cost recalculated
 * after the fact).
 */
@Singleton
class MysqlSyncRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val tripDao: TripDao,
    private val chargeDao: ChargeDao,
    private val batterySnapshotDao: BatterySnapshotDao,
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
        val pruneDays = pruneDue(now)
        Log.i(TAG, "syncing ${trips.size} trips, ${charges.size} charges, ${snapshots.size} snapshots, ${logs.size} log lines")
        val outcome = client.syncAll(cfg, trips, charges, snapshots, carState = null, logs = logs, pruneRetentionDays = pruneDays)
        outcome.onSuccess {
            settingsRepository.setMysqlLastSyncTs(now)
            logCollector.markShipped(logs)
            if (pruneDays != null) {
                settingsRepository.setString(SettingsRepository.KEY_MYSQL_LOG_PRUNED_TS, now.toString())
            }
        }.onFailure { Log.w(TAG, "sync failed: ${it.message}") }
        return outcome
    }

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
     * Log rows older than [LOG_RETENTION_DAYS] are dropped on at most one sync per
     * [PRUNE_INTERVAL_MS], from the same transaction that writes new ones — a separate pruning job
     * would be a second hub session for no benefit. Null when a pass isn't due.
     *
     * The cadence is hourly rather than daily because a pass deletes at most [MysqlSyncClient]'s
     * 5 000 rows per statement: a hub that has run for a month has far more to drop, and a daily
     * pass would delete slower than two apps write.
     */
    private suspend fun pruneDue(now: Long): Int? {
        val lastPruned = settingsRepository.getString(SettingsRepository.KEY_MYSQL_LOG_PRUNED_TS, "0")
            .toLongOrNull() ?: 0L
        if (now - lastPruned < PRUNE_INTERVAL_MS) return null
        return LOG_RETENTION_DAYS
    }

    companion object {
        private const val TAG = "MysqlSync"
        private const val PRUNE_INTERVAL_MS = 60 * 60 * 1000L

        /** How far back `app_logs` is kept on the hub: one month, counting both writers' rows. */
        private const val LOG_RETENTION_DAYS = 30
    }
}

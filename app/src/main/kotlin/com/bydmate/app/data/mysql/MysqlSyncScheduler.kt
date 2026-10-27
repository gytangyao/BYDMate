package com.bydmate.app.data.mysql

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bydmate.app.data.repository.SettingsRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides at ignition whether a MySQL sync should run and hands it to [MysqlSyncWorker].
 *
 * Mirrors [com.bydmate.app.data.backup.AutoBackupScheduler]: no periodic job, because the head unit
 * sleeps most of the day and a periodic job drifts to random wake-ups. "Keep the phone fresh" means
 * "sync on the first ignition after enough time has passed since the last successful run". A manual
 * "Sync now" from Settings bypasses the interval.
 */
@Singleton
class MysqlSyncScheduler @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        private const val TAG = "MysqlSync"
        private const val MIN_INTERVAL_MS = 5L * 60 * 1000 // don't re-hit the hub on every quick restart
        private const val START_DELAY_SEC = 20L // let the head unit finish booting its own network
        /**
         * A trip-end sync ships within seconds of the ignition-off tick; the short delay lets the
         * counter snapshot for that same tick land in Room first (it is written right after the
         * trip row) without keeping the head unit awake any longer.
         */
        private const val TRIP_END_DELAY_SEC = 10L
        /** A stop-and-go errand closes several trips; one hub session per couple of minutes is enough. */
        private const val TRIP_END_COOLDOWN_MS = 2L * 60 * 1000
    }

    @Volatile private var lastTripEndEnqueueMs = 0L

    suspend fun enqueueIfDue(context: Context) {
        if (settingsRepository.getString(SettingsRepository.KEY_MYSQL_ENABLED, "true") == "false") return
        val last = settingsRepository.getMysqlLastSyncTs()
        val now = System.currentTimeMillis()
        if (last in 1 until now && now - last < MIN_INTERVAL_MS) {
            Log.d(TAG, "sync not due (last $last)")
            return
        }
        enqueue(context, tag = "ignition")
    }

    /**
     * A trip just closed. The ignition-off tick writes the trip, its points and the counter
     * snapshot, then this ships them — the phone shows the drive minutes after it ends instead of
     * waiting for the next ignition, which may be the next morning. Bypasses [MIN_INTERVAL_MS]
     * (that interval exists to avoid quick restarts, not to delay a finished drive) but keeps its
     * own short cooldown.
     */
    suspend fun enqueueTripEnd(context: Context) {
        if (settingsRepository.getString(SettingsRepository.KEY_MYSQL_ENABLED, "true") == "false") return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastTripEndEnqueueMs < TRIP_END_COOLDOWN_MS) return
            lastTripEndEnqueueMs = now
        }
        enqueue(context, tag = "trip_end")
    }

    /** Immediate, user-requested sync (Settings «Sync now»); ignores the interval but not the config. */
    suspend fun enqueueNow(context: Context) = enqueue(context, tag = "manual")

    private suspend fun enqueue(context: Context, tag: String) {
        // Same blank-means-default rule as MysqlSyncRepository.loadConfig(): the compiled-in hub
        // answers until the driver types over it.
        val host = runCatching { settingsRepository.getString(SettingsRepository.KEY_MYSQL_HOST, "") }
            .getOrDefault("").trim().ifEmpty { MysqlDefaults.HOST }
        if (host.isBlank()) {
            Log.i(TAG, "enqueue($tag) skipped: no host")
            return
        }
        val delaySec = when (tag) {
            "manual" -> 0L
            "trip_end" -> TRIP_END_DELAY_SEC
            else -> START_DELAY_SEC
        }
        val request = OneTimeWorkRequestBuilder<MysqlSyncWorker>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        Log.i(TAG, "MySQL sync enqueued ($tag)")
    }

    private val WORK_NAME get() = MysqlSyncWorker.WORK_NAME

    fun cancelScheduled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

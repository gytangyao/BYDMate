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
    }

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
        val request = OneTimeWorkRequestBuilder<MysqlSyncWorker>()
            .setInitialDelay(if (tag == "manual") 0L else START_DELAY_SEC, TimeUnit.SECONDS)
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

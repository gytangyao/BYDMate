package com.bydmate.app.data.mysql

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one [MysqlSyncRepository.syncHistory] pass. Retries on failure with WorkManager's backoff
 * so a head unit that just lost its connection still delivers once the network returns; a hard
 * "not configured" is a success (nothing to do), never a retry loop.
 */
@HiltWorker
class MysqlSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MysqlSyncRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!repository.isEnabled()) {
            Log.i(TAG, "MySQL sync disabled, no-op")
            return@withContext Result.success()
        }
        val outcome = repository.syncHistory()
        when {
            outcome.isSuccess -> {
                Log.i(TAG, "sync done: ${outcome.getOrNull()}")
                Result.success()
            }
            // "MySQL not configured" is not retryable from a worker — nothing changes until the
            // user edits Settings. Treat it as a no-op success so WorkManager backs off cleanly.
            outcome.exceptionOrNull() is IllegalStateException -> {
                Log.w(TAG, "sync misconfigured: ${outcome.exceptionOrNull()?.message}")
                Result.success()
            }
            else -> {
                Log.w(TAG, "sync failed, will retry: ${outcome.exceptionOrNull()?.message}")
                Result.retry()
            }
        }
    }

    companion object {
        const val WORK_NAME = "mysql_sync"
        private const val TAG = "MysqlSync"
    }
}

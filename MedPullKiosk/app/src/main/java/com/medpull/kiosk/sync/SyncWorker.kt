package com.medpull.kiosk.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background worker for syncing offline operations
 * Runs periodically to process sync queue
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: SyncManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "sync_worker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Starting sync work")

            val processedCount = syncManager.processPendingOperations()

            Log.d(TAG, "Sync work completed: $processedCount operations processed")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync work failed", e)

            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

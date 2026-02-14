package com.medpull.kiosk.sync

import android.util.Log
import com.google.gson.Gson
import com.medpull.kiosk.data.local.dao.SyncQueueDao
import com.medpull.kiosk.data.local.entities.SyncOperationType
import com.medpull.kiosk.data.local.entities.SyncQueueEntity
import com.medpull.kiosk.data.local.entities.SyncQueueStatus
import com.medpull.kiosk.data.remote.aws.S3Service
import com.medpull.kiosk.data.repository.AuthRepository
import com.medpull.kiosk.utils.NetworkMonitor
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages synchronization of offline operations with AWS
 * Refreshes credentials before processing queued operations
 */
@Singleton
class SyncManager @Inject constructor(
    private val syncQueueDao: SyncQueueDao,
    private val networkMonitor: NetworkMonitor,
    private val s3Service: S3Service,
    private val gson: Gson,
    private val authRepository: AuthRepository
) {

    companion object {
        private const val TAG = "SyncManager"
    }

    /**
     * Add an operation to the sync queue
     */
    suspend fun queueOperation(
        operationType: SyncOperationType,
        entityId: String,
        payload: Any,
        priority: Int = 0
    ): Long {
        val operation = SyncQueueEntity(
            operationType = operationType.name,
            entityId = entityId,
            payload = gson.toJson(payload),
            priority = priority,
            status = SyncQueueStatus.PENDING.name
        )

        val id = syncQueueDao.insertSyncOperation(operation)
        Log.d(TAG, "Queued operation: type=${operationType.name}, entity=$entityId, id=$id")

        return id
    }

    /**
     * Process all pending sync operations
     * Returns number of operations processed successfully
     */
    suspend fun processPendingOperations(): Int {
        if (!networkMonitor.isCurrentlyConnected()) {
            Log.d(TAG, "Not online, skipping sync")
            return 0
        }

        // Check authentication before processing
        if (!authRepository.isAuthenticated()) {
            Log.e(TAG, "User not authenticated, cannot process sync operations")
            return 0
        }

        // Refresh tokens if needed before processing operations
        val credentialError = authRepository.refreshTokensIfNeeded()
        if (credentialError != null) {
            Log.e(TAG, "Failed to refresh tokens before sync: $credentialError")
            return 0
        }

        val pendingOps = syncQueueDao.getPendingSyncOperations()
        Log.d(TAG, "Processing ${pendingOps.size} pending operations")

        var successCount = 0

        for (operation in pendingOps) {
            try {
                processOperation(operation)
                successCount++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process operation ${operation.id}", e)
                handleOperationFailure(operation, e)
            }
        }

        // Retry failed operations that haven't exceeded max retries
        val retryableOps = syncQueueDao.getRetryableSyncOperations()
        Log.d(TAG, "Retrying ${retryableOps.size} failed operations")

        for (operation in retryableOps) {
            try {
                // Reset to pending and process
                syncQueueDao.resetToPending(operation.id, System.currentTimeMillis())
                processOperation(operation)
                successCount++
            } catch (e: Exception) {
                Log.e(TAG, "Retry failed for operation ${operation.id}", e)
                handleOperationFailure(operation, e)
            }
        }

        // Clean up old completed operations (older than 7 days)
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        syncQueueDao.deleteCompletedBefore(sevenDaysAgo)

        Log.d(TAG, "Sync completed: $successCount operations processed")
        return successCount
    }

    /**
     * Process a single sync operation
     */
    private suspend fun processOperation(operation: SyncQueueEntity) {
        Log.d(TAG, "Processing operation ${operation.id}: ${operation.operationType}")

        // Mark as in progress
        syncQueueDao.markAsInProgress(operation.id, System.currentTimeMillis())

        when (SyncOperationType.valueOf(operation.operationType)) {
            SyncOperationType.UPLOAD_FORM -> {
                val payload = gson.fromJson(operation.payload, UploadFormPayload::class.java)
                // The actual upload logic is in FormRepository
                // This is handled by the repository layer
                Log.d(TAG, "Form upload operation for: ${payload.formId}")
            }

            SyncOperationType.UPLOAD_FILE -> {
                val payload = gson.fromJson(operation.payload, UploadFilePayload::class.java)
                val file = java.io.File(payload.localFilePath)

                if (!file.exists()) {
                    throw Exception("File not found: ${payload.localFilePath}")
                }

                // Extract userId and folder from s3Key
                // s3Key format: "folder/userId/filename"
                val parts = payload.s3Key.split("/")
                val folder = if (parts.size > 2) "${parts[0]}/" else "forms/"
                val userId = if (parts.size > 2) parts[1] else "unknown"

                s3Service.uploadFileSync(
                    file = file,
                    folder = folder,
                    userId = userId
                )
            }

            SyncOperationType.SYNC_AUDIT_LOG -> {
                val payload = gson.fromJson(operation.payload, SyncAuditLogPayload::class.java)
                // Audit log sync is handled by AuditRepository directly
                Log.d(TAG, "Audit log sync for logs: ${payload.logIds}")
            }

            SyncOperationType.UPDATE_FORM_STATUS -> {
                val payload = gson.fromJson(operation.payload, UpdateFormStatusPayload::class.java)
                // Form status updates are handled by FormRepository
                Log.d(TAG, "Form status update for: ${payload.formId}")
            }

            SyncOperationType.DELETE_FORM -> {
                val payload = gson.fromJson(operation.payload, DeleteFormPayload::class.java)
                // Form deletion is handled by FormRepository
                Log.d(TAG, "Form deletion for: ${payload.formId}")
            }

            SyncOperationType.EXPORT_FORM -> {
                val payload = gson.fromJson(operation.payload, ExportFormPayload::class.java)
                // Form export is handled by FormRepository
                Log.d(TAG, "Form export for: ${payload.formId}")
            }
        }

        // Mark as completed
        syncQueueDao.markAsCompleted(operation.id, System.currentTimeMillis())
        Log.d(TAG, "Operation ${operation.id} completed successfully")
    }

    /**
     * Handle operation failure
     */
    private suspend fun handleOperationFailure(operation: SyncQueueEntity, error: Exception) {
        val errorMessage = error.message ?: "Unknown error"

        if (operation.retryCount >= operation.maxRetries) {
            Log.e(TAG, "Operation ${operation.id} failed after ${operation.retryCount} retries: $errorMessage")
        }

        syncQueueDao.markAsFailed(
            id = operation.id,
            error = errorMessage,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Get count of pending operations
     */
    suspend fun getPendingCount(): Int {
        return syncQueueDao.getPendingCount()
    }

    /**
     * Clear all completed operations
     */
    suspend fun clearCompleted() {
        syncQueueDao.deleteAllCompleted()
    }
}

/**
 * Payload data classes for different operation types
 */
data class UploadFormPayload(
    val formId: String,
    val userId: String,
    val localFilePath: String
)

data class UploadFilePayload(
    val localFilePath: String,
    val s3Key: String
)

data class SyncAuditLogPayload(
    val logIds: List<Long>
)

data class UpdateFormStatusPayload(
    val formId: String,
    val status: String
)

data class DeleteFormPayload(
    val formId: String
)

data class ExportFormPayload(
    val formId: String,
    val userId: String
)

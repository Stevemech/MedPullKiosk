package com.medpull.kiosk.data.local.dao

import androidx.room.*
import com.medpull.kiosk.data.local.entities.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for sync queue operations
 * Manages offline operation queue for background sync
 */
@Dao
interface SyncQueueDao {

    /**
     * Get all pending sync operations ordered by priority (descending) and created time (ascending)
     */
    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY priority DESC, createdAt ASC")
    suspend fun getPendingSyncOperations(): List<SyncQueueEntity>

    /**
     * Get pending sync operations as Flow for observation
     */
    @Query("SELECT * FROM sync_queue WHERE status = 'PENDING' ORDER BY priority DESC, createdAt ASC")
    fun getPendingSyncOperationsFlow(): Flow<List<SyncQueueEntity>>

    /**
     * Get sync operation by ID
     */
    @Query("SELECT * FROM sync_queue WHERE id = :id")
    suspend fun getSyncOperationById(id: Long): SyncQueueEntity?

    /**
     * Get sync operations by entity ID
     */
    @Query("SELECT * FROM sync_queue WHERE entityId = :entityId ORDER BY createdAt DESC")
    suspend fun getSyncOperationsByEntityId(entityId: String): List<SyncQueueEntity>

    /**
     * Get sync operations by type
     */
    @Query("SELECT * FROM sync_queue WHERE operationType = :operationType AND status = 'PENDING'")
    suspend fun getSyncOperationsByType(operationType: String): List<SyncQueueEntity>

    /**
     * Get failed sync operations that can be retried
     */
    @Query("SELECT * FROM sync_queue WHERE status = 'FAILED' AND retryCount < maxRetries ORDER BY priority DESC, createdAt ASC")
    suspend fun getRetryableSyncOperations(): List<SyncQueueEntity>

    /**
     * Get count of pending operations
     */
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'PENDING'")
    suspend fun getPendingCount(): Int

    /**
     * Get count of pending operations as Flow
     */
    @Query("SELECT COUNT(*) FROM sync_queue WHERE status = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>

    /**
     * Insert a new sync operation
     */
    @Insert
    suspend fun insertSyncOperation(operation: SyncQueueEntity): Long

    /**
     * Update sync operation
     */
    @Update
    suspend fun updateSyncOperation(operation: SyncQueueEntity)

    /**
     * Delete sync operation
     */
    @Delete
    suspend fun deleteSyncOperation(operation: SyncQueueEntity)

    /**
     * Delete sync operation by ID
     */
    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun deleteSyncOperationById(id: Long)

    /**
     * Delete completed operations older than timestamp
     */
    @Query("DELETE FROM sync_queue WHERE status = 'COMPLETED' AND completedAt < :timestamp")
    suspend fun deleteCompletedBefore(timestamp: Long)

    /**
     * Delete all completed operations
     */
    @Query("DELETE FROM sync_queue WHERE status = 'COMPLETED'")
    suspend fun deleteAllCompleted()

    /**
     * Mark operation as in progress
     */
    @Query("UPDATE sync_queue SET status = 'IN_PROGRESS', lastAttemptAt = :timestamp WHERE id = :id")
    suspend fun markAsInProgress(id: Long, timestamp: Long)

    /**
     * Mark operation as completed
     */
    @Query("UPDATE sync_queue SET status = 'COMPLETED', completedAt = :timestamp WHERE id = :id")
    suspend fun markAsCompleted(id: Long, timestamp: Long)

    /**
     * Mark operation as failed
     */
    @Query("UPDATE sync_queue SET status = 'FAILED', retryCount = retryCount + 1, lastError = :error, lastAttemptAt = :timestamp WHERE id = :id")
    suspend fun markAsFailed(id: Long, error: String, timestamp: Long)

    /**
     * Reset operation to pending for retry
     */
    @Query("UPDATE sync_queue SET status = 'PENDING', lastAttemptAt = :timestamp WHERE id = :id")
    suspend fun resetToPending(id: Long, timestamp: Long)

    /**
     * Get all sync operations (for debugging)
     */
    @Query("SELECT * FROM sync_queue ORDER BY createdAt DESC")
    suspend fun getAllSyncOperations(): List<SyncQueueEntity>
}

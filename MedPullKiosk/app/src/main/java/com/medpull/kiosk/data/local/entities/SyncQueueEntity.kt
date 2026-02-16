package com.medpull.kiosk.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for tracking operations that need to be synced when online
 * Used for offline mode support
 */
@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Type of operation: UPLOAD_FORM, UPLOAD_FILE, SYNC_AUDIT_LOG, etc.
     */
    val operationType: String,

    /**
     * ID of the related entity (form ID, file ID, etc.)
     */
    val entityId: String,

    /**
     * JSON payload with operation details
     */
    val payload: String,

    /**
     * Number of retry attempts
     */
    val retryCount: Int = 0,

    /**
     * Maximum retry attempts before giving up
     */
    val maxRetries: Int = 3,

    /**
     * Last error message if operation failed
     */
    val lastError: String? = null,

    /**
     * Priority: higher number = higher priority
     */
    val priority: Int = 0,

    /**
     * Status: PENDING, IN_PROGRESS, FAILED, COMPLETED
     */
    val status: String = "PENDING",

    /**
     * When the operation was created
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * When the operation was last attempted
     */
    val lastAttemptAt: Long? = null,

    /**
     * When the operation completed successfully
     */
    val completedAt: Long? = null
)

/**
 * Sync operation types
 */
enum class SyncOperationType {
    UPLOAD_FORM,
    UPLOAD_FILE,
    SYNC_AUDIT_LOG,
    UPDATE_FORM_STATUS,
    DELETE_FORM,
    EXPORT_FORM,
    FHIR_EXPORT_FORM
}

/**
 * Sync queue status
 */
enum class SyncQueueStatus {
    PENDING,
    IN_PROGRESS,
    FAILED,
    COMPLETED
}

package com.medpull.kiosk.data.repository

import com.google.gson.Gson
import com.medpull.kiosk.data.local.dao.AuditLogDao
import com.medpull.kiosk.data.local.entities.AuditLogEntity
import com.medpull.kiosk.data.models.AuditLog
import com.medpull.kiosk.data.remote.aws.S3Service
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for audit log operations
 * Includes S3 sync for HIPAA compliance
 */
@Singleton
class AuditRepository @Inject constructor(
    private val auditLogDao: AuditLogDao,
    private val s3Service: S3Service,
    private val gson: Gson
) {

    /**
     * Insert audit log
     */
    suspend fun insertAuditLog(log: AuditLogEntity) {
        auditLogDao.insertLog(log)
    }

    /**
     * Get unsynced logs
     */
    suspend fun getUnsyncedLogs(): List<AuditLog> {
        return auditLogDao.getUnsyncedLogs().map { it.toDomain() }
    }

    /**
     * Get unsynced logs as Flow
     */
    fun getUnsyncedLogsFlow(): Flow<List<AuditLog>> {
        return auditLogDao.getUnsyncedLogsFlow().map { logs ->
            logs.map { it.toDomain() }
        }
    }

    /**
     * Mark logs as synced
     */
    suspend fun markLogsAsSynced(logIds: List<String>) {
        auditLogDao.markLogsAsSynced(logIds)
    }

    /**
     * Get logs by user
     */
    suspend fun getLogsByUserId(userId: String): List<AuditLog> {
        return auditLogDao.getLogsByUserId(userId).map { it.toDomain() }
    }

    /**
     * Get recent logs
     */
    suspend fun getRecentLogs(limit: Int = 100): List<AuditLog> {
        return auditLogDao.getRecentLogs(limit).map { it.toDomain() }
    }

    /**
     * Clean up old synced logs (older than 30 days)
     */
    suspend fun cleanupOldLogs() {
        val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
        auditLogDao.deleteSyncedLogsBefore(thirtyDaysAgo)
    }

    /**
     * Sync audit logs to S3
     */
    suspend fun syncLogsToS3(userId: String): SyncResult {
        val unsyncedLogs = getUnsyncedLogs()

        if (unsyncedLogs.isEmpty()) {
            return SyncResult.Success(0)
        }

        return try {
            // Convert logs to JSON
            val logsJson = gson.toJson(unsyncedLogs)

            // Upload to S3
            val success = s3Service.uploadAuditLog(
                logData = logsJson,
                userId = userId,
                timestamp = System.currentTimeMillis()
            )

            if (success) {
                // Mark logs as synced
                val logIds = unsyncedLogs.map { it.id }
                markLogsAsSynced(logIds)
                SyncResult.Success(unsyncedLogs.size)
            } else {
                SyncResult.Error("Failed to upload audit logs to S3")
            }
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Audit sync failed")
        }
    }

    /**
     * Get unsynced log count
     */
    suspend fun getUnsyncedLogCount(): Int {
        return auditLogDao.getUnsyncedLogCount()
    }

    /**
     * Sync pending logs to S3
     * Used by SyncManager for background sync
     */
    suspend fun syncPendingLogs(): Boolean {
        val unsyncedLogs = getUnsyncedLogs()

        if (unsyncedLogs.isEmpty()) {
            return true
        }

        return try {
            // Convert logs to JSON
            val logsJson = gson.toJson(unsyncedLogs)

            // Use first user ID from logs, or "system" if none
            val userId = unsyncedLogs.firstOrNull()?.userId ?: "system"

            // Upload to S3
            val success = s3Service.uploadAuditLog(
                logData = logsJson,
                userId = userId,
                timestamp = System.currentTimeMillis()
            )

            if (success) {
                // Mark logs as synced
                val logIds = unsyncedLogs.map { it.id }
                markLogsAsSynced(logIds)
            }

            success
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Sync result sealed class
 */
sealed class SyncResult {
    data class Success(val syncedCount: Int) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

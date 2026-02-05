package com.medpull.kiosk.data.local.dao

import androidx.room.*
import com.medpull.kiosk.data.local.entities.AuditLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for AuditLog operations
 */
@Dao
interface AuditLogDao {

    @Query("SELECT * FROM audit_logs WHERE id = :logId")
    suspend fun getLogById(logId: String): AuditLogEntity?

    @Query("SELECT * FROM audit_logs WHERE userId = :userId ORDER BY timestamp DESC")
    suspend fun getLogsByUserId(userId: String): List<AuditLogEntity>

    @Query("SELECT * FROM audit_logs WHERE synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedLogs(): List<AuditLogEntity>

    @Query("SELECT * FROM audit_logs WHERE synced = 0 ORDER BY timestamp ASC")
    fun getUnsyncedLogsFlow(): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 100): List<AuditLogEntity>

    @Query("SELECT * FROM audit_logs WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getLogsByTimeRange(startTime: Long, endTime: Long): List<AuditLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AuditLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<AuditLogEntity>)

    @Update
    suspend fun updateLog(log: AuditLogEntity)

    @Query("UPDATE audit_logs SET synced = 1 WHERE id = :logId")
    suspend fun markLogAsSynced(logId: String)

    @Query("UPDATE audit_logs SET synced = 1 WHERE id IN (:logIds)")
    suspend fun markLogsAsSynced(logIds: List<String>)

    @Delete
    suspend fun deleteLog(log: AuditLogEntity)

    @Query("DELETE FROM audit_logs WHERE timestamp < :timestamp AND synced = 1")
    suspend fun deleteSyncedLogsBefore(timestamp: Long)

    @Query("SELECT COUNT(*) FROM audit_logs WHERE synced = 0")
    suspend fun getUnsyncedLogCount(): Int
}

package com.medpull.kiosk.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.medpull.kiosk.data.models.AuditLog

/**
 * Room entity for AuditLog
 */
@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val userId: String,
    val action: String,
    val resourceType: String?,
    val resourceId: String?,
    val ipAddress: String,
    val deviceId: String,
    val description: String?,
    val metadata: String?,
    val synced: Boolean = false
) {
    fun toDomain(): AuditLog = AuditLog(
        id = id,
        timestamp = timestamp,
        userId = userId,
        action = action,
        resourceType = resourceType,
        resourceId = resourceId,
        ipAddress = ipAddress,
        deviceId = deviceId,
        description = description,
        metadata = metadata,
        synced = synced
    )

    companion object {
        fun fromDomain(log: AuditLog): AuditLogEntity = AuditLogEntity(
            id = log.id,
            timestamp = log.timestamp,
            userId = log.userId,
            action = log.action,
            resourceType = log.resourceType,
            resourceId = log.resourceId,
            ipAddress = log.ipAddress,
            deviceId = log.deviceId,
            description = log.description,
            metadata = log.metadata,
            synced = log.synced
        )
    }
}

package com.medpull.kiosk.data.models

/**
 * Audit log domain model
 */
data class AuditLog(
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
)

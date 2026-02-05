package com.medpull.kiosk.security

import android.content.Context
import com.medpull.kiosk.data.local.entities.AuditLogEntity
import com.medpull.kiosk.data.repository.AuditRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HIPAA-compliant audit logging
 * Logs all PHI access and modifications
 */
@Singleton
class HipaaAuditLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auditRepository: AuditRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Log an audit event
     */
    fun log(
        userId: String?,
        action: String,
        resourceType: String?,
        resourceId: String?,
        description: String? = null,
        metadata: Map<String, String>? = null
    ) {
        scope.launch {
            val auditLog = AuditLogEntity(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                userId = userId ?: "ANONYMOUS",
                action = action,
                resourceType = resourceType,
                resourceId = resourceId,
                ipAddress = getDeviceIpAddress(),
                deviceId = getDeviceId(),
                description = description,
                metadata = metadata?.entries?.joinToString(";") { "${it.key}=${it.value}" },
                synced = false
            )

            auditRepository.insertAuditLog(auditLog)
        }
    }

    /**
     * Log user login
     */
    fun logLogin(userId: String, success: Boolean) {
        log(
            userId = userId,
            action = "LOGIN",
            resourceType = "USER",
            resourceId = userId,
            description = if (success) "User logged in successfully" else "Login failed",
            metadata = mapOf("success" to success.toString())
        )
    }

    /**
     * Log user logout
     */
    fun logLogout(userId: String, reason: String = "USER_INITIATED") {
        log(
            userId = userId,
            action = "LOGOUT",
            resourceType = "USER",
            resourceId = userId,
            description = "User logged out",
            metadata = mapOf("reason" to reason)
        )
    }

    /**
     * Log form access
     */
    fun logFormAccess(userId: String, formId: String, action: String) {
        log(
            userId = userId,
            action = action,
            resourceType = "FORM",
            resourceId = formId,
            description = "Form accessed: $action"
        )
    }

    /**
     * Log form upload
     */
    fun logFormUpload(userId: String, formId: String, fileName: String) {
        log(
            userId = userId,
            action = "FORM_UPLOAD",
            resourceType = "FORM",
            resourceId = formId,
            description = "Form uploaded",
            metadata = mapOf("fileName" to fileName)
        )
    }

    /**
     * Log form edit
     */
    fun logFormEdit(userId: String, formId: String, fieldName: String) {
        log(
            userId = userId,
            action = "FORM_EDIT",
            resourceType = "FORM",
            resourceId = formId,
            description = "Form field edited",
            metadata = mapOf("fieldName" to fieldName)
        )
    }

    /**
     * Log form export
     */
    fun logFormExport(userId: String, formId: String, exportType: String) {
        log(
            userId = userId,
            action = "FORM_EXPORT",
            resourceType = "FORM",
            resourceId = formId,
            description = "Form exported",
            metadata = mapOf("exportType" to exportType)
        )
    }

    /**
     * Log AI query
     */
    fun logAiQuery(userId: String, query: String, language: String) {
        log(
            userId = userId,
            action = "AI_QUERY",
            resourceType = "AI",
            resourceId = null,
            description = "AI assistance requested",
            metadata = mapOf(
                "language" to language,
                "queryLength" to query.length.toString()
            )
        )
    }

    /**
     * Log session timeout
     */
    fun logSessionTimeout(userId: String) {
        log(
            userId = userId,
            action = "SESSION_TIMEOUT",
            resourceType = "USER",
            resourceId = userId,
            description = "Session expired due to inactivity"
        )
    }

    /**
     * Log security event
     */
    fun logSecurityEvent(userId: String?, eventType: String, description: String) {
        log(
            userId = userId,
            action = "SECURITY_EVENT",
            resourceType = "SECURITY",
            resourceId = null,
            description = description,
            metadata = mapOf("eventType" to eventType)
        )
    }

    /**
     * Get device IP address
     */
    private fun getDeviceIpAddress(): String {
        return try {
            InetAddress.getLocalHost().hostAddress ?: "UNKNOWN"
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    /**
     * Get device ID (for audit purposes)
     */
    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"
    }
}

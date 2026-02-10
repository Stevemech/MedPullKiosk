package com.medpull.kiosk.data.repository

import android.util.Log
import com.medpull.kiosk.data.local.dao.AuditLogDao
import com.medpull.kiosk.data.local.entities.AuditLogEntity
import com.medpull.kiosk.data.models.FormField
import com.medpull.kiosk.data.remote.ai.AiResponse
import com.medpull.kiosk.data.remote.ai.BedrockService
import com.medpull.kiosk.utils.Constants
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for AI assistance operations.
 * Uses AWS Bedrock (Claude) via existing Cognito credentials.
 */
@Singleton
class AiRepository @Inject constructor(
    private val bedrockService: BedrockService,
    private val auditLogDao: AuditLogDao,
    private val authRepository: AuthRepository
) {

    companion object {
        private const val TAG = "AiRepository"
    }

    /**
     * Send a chat message to AI
     */
    suspend fun sendChatMessage(
        message: String,
        language: String,
        formContext: String? = null
    ): AiChatResult {
        return try {
            logAiQuery(message)

            when (val response = bedrockService.sendMessage(message, formContext, language)) {
                is AiResponse.Success -> {
                    Log.d(TAG, "AI response received")
                    AiChatResult.Success(response.message)
                }
                is AiResponse.Error -> {
                    Log.e(TAG, "AI error: ${response.message}")
                    AiChatResult.Error(response.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in AI chat", e)
            AiChatResult.Error("Failed to get AI response: ${e.message}")
        }
    }

    /**
     * Get field suggestion from AI
     */
    suspend fun suggestFieldValue(
        field: FormField,
        language: String
    ): AiChatResult {
        return try {
            logAiQuery("Field suggestion: ${field.fieldName}")

            when (val response = bedrockService.suggestFieldValue(
                fieldName = field.translatedText ?: field.fieldName,
                fieldType = field.fieldType.name,
                language = language
            )) {
                is AiResponse.Success -> AiChatResult.Success(response.message)
                is AiResponse.Error -> AiChatResult.Error(response.message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error suggesting field value", e)
            AiChatResult.Error("Failed to get suggestion: ${e.message}")
        }
    }

    /**
     * Explain a medical term
     */
    suspend fun explainTerm(
        term: String,
        language: String
    ): AiChatResult {
        return try {
            logAiQuery("Explain term: $term")

            when (val response = bedrockService.explainMedicalTerm(term, language)) {
                is AiResponse.Success -> AiChatResult.Success(response.message)
                is AiResponse.Error -> AiChatResult.Error(response.message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error explaining term", e)
            AiChatResult.Error("Failed to explain term: ${e.message}")
        }
    }

    /**
     * Get help with a specific form field
     */
    suspend fun getFieldHelp(
        field: FormField,
        language: String
    ): AiChatResult {
        val question = "What information should I provide for the field '${field.translatedText ?: field.fieldName}'?"
        return sendChatMessage(question, language)
    }

    /**
     * Log AI query for audit
     */
    private suspend fun logAiQuery(query: String) {
        try {
            val userId = authRepository.getCurrentUserId() ?: "unknown"
            val auditLog = AuditLogEntity(
                id = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                userId = userId,
                action = Constants.Audit.ACTION_AI_QUERY,
                resourceType = "AI",
                resourceId = null,
                ipAddress = "local",
                deviceId = "tablet",
                description = "AI Query: ${query.take(100)}",
                metadata = null
            )
            auditLogDao.insertLog(auditLog)
        } catch (e: Exception) {
            Log.e(TAG, "Error logging AI query", e)
        }
    }
}

/**
 * AI chat result sealed class
 */
sealed class AiChatResult {
    data class Success(val message: String) : AiChatResult()
    data class Error(val message: String) : AiChatResult()
}

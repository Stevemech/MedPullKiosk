package com.medpull.kiosk.data.remote.aws

import com.google.gson.Gson
import com.medpull.kiosk.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AWS API Gateway service for Lambda invocations
 * Handles REST API calls to backend Lambda functions
 */
@Singleton
class ApiGatewayService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {

    private val baseUrl = Constants.AWS.API_ENDPOINT

    /**
     * Invoke Lambda function via API Gateway
     */
    suspend fun <T> invokeLambda(
        endpoint: String,
        method: String = "POST",
        body: Any? = null,
        authToken: String? = null,
        responseClass: Class<T>
    ): ApiGatewayResult<T> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl$endpoint"

            val requestBuilder = Request.Builder()
                .url(url)
                .method(
                    method,
                    body?.let {
                        val jsonBody = gson.toJson(it)
                        jsonBody.toRequestBody("application/json".toMediaType())
                    }
                )

            // Add authorization header if token provided
            authToken?.let {
                requestBuilder.addHeader("Authorization", "Bearer $it")
            }

            val request = requestBuilder.build()

            val response = okHttpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val result = gson.fromJson(responseBody, responseClass)
                ApiGatewayResult.Success(result)
            } else {
                ApiGatewayResult.Error(
                    "API call failed: ${response.code} ${response.message}"
                )
            }
        } catch (e: Exception) {
            ApiGatewayResult.Error(e.message ?: "API Gateway invocation failed")
        }
    }

    /**
     * Process form with Textract (via Lambda)
     */
    suspend fun processFormWithTextract(
        s3Key: String,
        userId: String,
        authToken: String
    ): ApiGatewayResult<TextractLambdaResponse> {
        val requestBody = mapOf(
            "s3Key" to s3Key,
            "userId" to userId,
            "action" to "extract_fields"
        )

        return invokeLambda(
            endpoint = "/textract/analyze",
            method = "POST",
            body = requestBody,
            authToken = authToken,
            responseClass = TextractLambdaResponse::class.java
        )
    }

    /**
     * Translate form fields (via Lambda)
     */
    suspend fun translateFormFields(
        fields: Map<String, String>,
        targetLanguage: String,
        authToken: String
    ): ApiGatewayResult<TranslationLambdaResponse> {
        val requestBody = mapOf(
            "fields" to fields,
            "targetLanguage" to targetLanguage,
            "action" to "translate"
        )

        return invokeLambda(
            endpoint = "/translate/batch",
            method = "POST",
            body = requestBody,
            authToken = authToken,
            responseClass = TranslationLambdaResponse::class.java
        )
    }

    /**
     * Generate filled PDF (via Lambda)
     */
    suspend fun generateFilledPdf(
        originalS3Key: String,
        fieldValues: Map<String, String>,
        userId: String,
        authToken: String
    ): ApiGatewayResult<PdfGenerationResponse> {
        val requestBody = mapOf(
            "originalS3Key" to originalS3Key,
            "fieldValues" to fieldValues,
            "userId" to userId,
            "action" to "generate_pdf"
        )

        return invokeLambda(
            endpoint = "/pdf/generate",
            method = "POST",
            body = requestBody,
            authToken = authToken,
            responseClass = PdfGenerationResponse::class.java
        )
    }

    /**
     * Upload audit logs (via Lambda)
     */
    suspend fun uploadAuditLogs(
        logs: List<Map<String, Any>>,
        userId: String,
        authToken: String
    ): ApiGatewayResult<AuditUploadResponse> {
        val requestBody = mapOf(
            "logs" to logs,
            "userId" to userId,
            "action" to "upload_audit"
        )

        return invokeLambda(
            endpoint = "/audit/upload",
            method = "POST",
            body = requestBody,
            authToken = authToken,
            responseClass = AuditUploadResponse::class.java
        )
    }

    /**
     * Get user profile (via Lambda)
     */
    suspend fun getUserProfile(
        userId: String,
        authToken: String
    ): ApiGatewayResult<UserProfileResponse> {
        return invokeLambda(
            endpoint = "/user/profile/$userId",
            method = "GET",
            body = null,
            authToken = authToken,
            responseClass = UserProfileResponse::class.java
        )
    }

    /**
     * Health check
     */
    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * API Gateway result sealed class
 */
sealed class ApiGatewayResult<out T> {
    data class Success<T>(val data: T) : ApiGatewayResult<T>()
    data class Error(val message: String) : ApiGatewayResult<Nothing>()
}

/**
 * Lambda response models
 */
data class TextractLambdaResponse(
    val success: Boolean,
    val fields: List<Map<String, Any>>?,
    val message: String?
)

data class TranslationLambdaResponse(
    val success: Boolean,
    val translatedFields: Map<String, String>?,
    val message: String?
)

data class PdfGenerationResponse(
    val success: Boolean,
    val s3Key: String?,
    val message: String?
)

data class AuditUploadResponse(
    val success: Boolean,
    val uploadedCount: Int,
    val message: String?
)

data class UserProfileResponse(
    val success: Boolean,
    val user: Map<String, Any>?,
    val message: String?
)

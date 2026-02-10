package com.medpull.kiosk.data.remote.ai

import android.util.Log
import com.amazonaws.DefaultRequest
import com.amazonaws.auth.AWS4Signer
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.http.HttpMethodName
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.medpull.kiosk.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AWS Bedrock service for AI assistance using Claude.
 * Calls the Bedrock InvokeModel REST API directly with SigV4-signed requests,
 * using the existing Cognito Identity Pool credentials (no extra API key needed).
 */
@Singleton
class BedrockService @Inject constructor(
    private val credentialsProvider: AWSCredentialsProvider,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "BedrockService"
        private const val MODEL_ID = "anthropic.claude-3-haiku-20240307-v1:0"
        private const val MAX_TOKENS = 512
        private const val SERVICE_NAME = "bedrock"
    }

    private val endpoint = "https://bedrock-runtime.${Constants.AWS.REGION}.amazonaws.com"

    /**
     * Send a chat message to Claude via Bedrock
     */
    suspend fun sendMessage(
        message: String,
        context: String? = null,
        language: String = "en"
    ): AiResponse = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = buildSystemPrompt(language, context)

            val requestBody = BedrockClaudeRequest(
                anthropicVersion = "bedrock-2023-05-31",
                maxTokens = MAX_TOKENS,
                system = systemPrompt,
                messages = listOf(
                    BedrockMessage(role = "user", content = message)
                )
            )

            val bodyJson = gson.toJson(requestBody)
            val url = "$endpoint/model/$MODEL_ID/invoke"

            // Build a signable AWS request
            val awsRequest = DefaultRequest<Void>(SERVICE_NAME).apply {
                httpMethod = HttpMethodName.POST
                this.endpoint = URI.create(this@BedrockService.endpoint)
                resourcePath = "/model/$MODEL_ID/invoke"
                addHeader("Content-Type", "application/json")
                addHeader("Accept", "application/json")
                content = ByteArrayInputStream(bodyJson.toByteArray(Charsets.UTF_8))
            }

            // Sign with SigV4
            val signer = AWS4Signer()
            signer.setServiceName(SERVICE_NAME)
            signer.setRegionName(Constants.AWS.REGION)
            signer.sign(awsRequest, credentialsProvider.credentials)

            // Build OkHttp request with signed headers
            val okRequestBuilder = Request.Builder().url(url)
            for ((key, value) in awsRequest.headers) {
                okRequestBuilder.addHeader(key, value)
            }
            okRequestBuilder.post(bodyJson.toRequestBody("application/json".toMediaType()))

            val response = okHttpClient.newCall(okRequestBuilder.build()).execute()

            if (response.isSuccessful) {
                val responseJson = response.body?.string()
                if (responseJson != null) {
                    val claudeResponse = gson.fromJson(responseJson, BedrockClaudeResponse::class.java)
                    val text = claudeResponse.content?.firstOrNull()?.text
                    if (!text.isNullOrBlank()) {
                        Log.d(TAG, "Bedrock response received (${text.length} chars)")
                        AiResponse.Success(text)
                    } else {
                        AiResponse.Error("Empty response from AI")
                    }
                } else {
                    AiResponse.Error("Empty response body from Bedrock")
                }
            } else {
                val errorBody = response.body?.string()
                Log.e(TAG, "Bedrock API error: ${response.code} - $errorBody")
                when (response.code) {
                    403 -> AiResponse.Error("AI service not authorized. Check Bedrock model access in AWS console.")
                    429 -> AiResponse.Error("Rate limit exceeded. Please try again shortly.")
                    else -> AiResponse.Error("AI request failed (${response.code})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calling Bedrock", e)
            AiResponse.Error("Failed to connect to AI: ${e.message}")
        }
    }

    /**
     * Get field suggestion
     */
    suspend fun suggestFieldValue(
        fieldName: String,
        fieldType: String,
        language: String = "en"
    ): AiResponse {
        val prompt = """
            For a medical form field named "$fieldName" of type $fieldType,
            suggest an appropriate example value and explain what should go in this field.
        """.trimIndent()
        return sendMessage(prompt, null, language)
    }

    /**
     * Explain medical term
     */
    suspend fun explainMedicalTerm(
        term: String,
        language: String = "en"
    ): AiResponse {
        val prompt = "What does the medical term '$term' mean? Explain in simple terms."
        return sendMessage(prompt, null, language)
    }

    private fun buildSystemPrompt(language: String, context: String?): String {
        val languageName = when (language) {
            "es" -> "Spanish"
            "zh" -> "Chinese"
            "fr" -> "French"
            "hi" -> "Hindi"
            "ar" -> "Arabic"
            else -> "English"
        }

        val base = """
            You are a helpful medical form assistant on a patient kiosk. Your role is to:
            1. Help users understand medical form fields and what information is being asked for
            2. Suggest appropriate values for form fields (e.g. date formats, common entries)
            3. Explain medical terminology in simple, easy-to-understand terms
            4. Answer questions about the form they are filling out
            5. Provide all guidance in $languageName

            Keep responses concise (2-3 sentences max). Always respond in $languageName.
            Never provide medical advice or diagnoses. Only help with form-filling questions.
        """.trimIndent()

        return if (context != null) {
            "$base\n\nCurrent form context:\n$context"
        } else {
            base
        }
    }
}

// --- Bedrock Claude request/response models ---

data class BedrockClaudeRequest(
    @SerializedName("anthropic_version")
    val anthropicVersion: String,
    @SerializedName("max_tokens")
    val maxTokens: Int,
    val system: String? = null,
    val messages: List<BedrockMessage>
)

data class BedrockMessage(
    val role: String,
    val content: String
)

data class BedrockClaudeResponse(
    val content: List<BedrockContent>?,
    @SerializedName("stop_reason")
    val stopReason: String?
)

data class BedrockContent(
    val type: String?,
    val text: String?
)

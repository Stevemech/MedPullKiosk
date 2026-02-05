package com.medpull.kiosk.data.remote.aws

import com.amazonaws.services.textract.AmazonTextractClient
import com.amazonaws.services.textract.model.*
import com.medpull.kiosk.data.models.BoundingBox
import com.medpull.kiosk.data.models.FieldType
import com.medpull.kiosk.data.models.FormField
import com.medpull.kiosk.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AWS Textract service for form field extraction
 * Analyzes documents and extracts form fields
 */
@Singleton
class TextractService @Inject constructor(
    private val textractClient: AmazonTextractClient,
    private val s3Service: S3Service
) {

    /**
     * Analyze document and extract form fields
     */
    suspend fun analyzeDocument(s3Key: String, formId: String): TextractResult = withContext(Dispatchers.IO) {
        try {
            // Create S3 object reference
            val s3Object = S3Object().apply {
                bucket = Constants.AWS.S3_BUCKET
                name = s3Key
            }

            val document = Document().withS3Object(s3Object)

            // Analyze document with FORMS feature
            val request = AnalyzeDocumentRequest()
                .withDocument(document)
                .withFeatureTypes("FORMS", "TABLES")

            val result = textractClient.analyzeDocument(request)

            // Extract form fields from result
            val formFields = extractFormFields(result, formId)

            TextractResult.Success(formFields)
        } catch (e: Exception) {
            TextractResult.Error(e.message ?: "Textract analysis failed")
        }
    }

    /**
     * Extract form fields from Textract response
     */
    private fun extractFormFields(result: AnalyzeDocumentResult, formId: String): List<FormField> {
        val fields = mutableListOf<FormField>()
        val blocks = result.blocks
        val keyMap = mutableMapOf<String, Block>()
        val valueMap = mutableMapOf<String, Block>()
        val blockMap = blocks.associateBy { it.id }

        // Build key-value maps
        blocks.forEach { block ->
            when (block.blockType) {
                "KEY_VALUE_SET" -> {
                    if (block.entityTypes?.contains("KEY") == true) {
                        keyMap[block.id] = block
                    } else if (block.entityTypes?.contains("VALUE") == true) {
                        valueMap[block.id] = block
                    }
                }
            }
        }

        // Process key-value pairs
        keyMap.forEach { (keyId, keyBlock) ->
            // Find corresponding value
            val valueId = keyBlock.relationships
                ?.firstOrNull { it.type == "VALUE" }
                ?.ids?.firstOrNull()

            val valueBlock = valueId?.let { valueMap[it] }

            // Extract text from key
            val keyText = getBlockText(keyBlock, blockMap)

            // Extract text from value (if exists)
            val valueText = valueBlock?.let { getBlockText(it, blockMap) } ?: ""

            // Get bounding box
            val boundingBox = keyBlock.geometry?.boundingBox?.let {
                BoundingBox(
                    left = it.left,
                    top = it.top,
                    width = it.width,
                    height = it.height,
                    page = keyBlock.page ?: 1
                )
            }

            // Determine field type
            val fieldType = determineFieldType(keyText, valueText)

            // Create form field
            if (keyText.isNotBlank()) {
                fields.add(
                    FormField(
                        id = UUID.randomUUID().toString(),
                        formId = formId,
                        fieldName = keyText.trim(),
                        fieldType = fieldType,
                        originalText = keyText.trim(),
                        translatedText = null,
                        value = valueText.trim().takeIf { it.isNotBlank() },
                        boundingBox = boundingBox,
                        confidence = keyBlock.confidence ?: 0f,
                        required = false, // Cannot determine from Textract
                        page = keyBlock.page ?: 1
                    )
                )
            }
        }

        return fields.filter { it.confidence >= Constants.Pdf.FORM_FIELD_CONFIDENCE_THRESHOLD }
    }

    /**
     * Get text from a block by following CHILD relationships
     */
    private fun getBlockText(block: Block, blockMap: Map<String, Block>): String {
        val text = StringBuilder()

        block.relationships?.forEach { relationship ->
            if (relationship.type == "CHILD") {
                relationship.ids.forEach { childId ->
                    val childBlock = blockMap[childId]
                    if (childBlock?.blockType == "WORD" || childBlock?.blockType == "SELECTION_ELEMENT") {
                        childBlock.text?.let {
                            if (text.isNotEmpty()) text.append(" ")
                            text.append(it)
                        }
                    }
                }
            }
        }

        return text.toString()
    }

    /**
     * Determine field type based on key text and value
     */
    private fun determineFieldType(keyText: String, valueText: String): FieldType {
        val lowerKey = keyText.lowercase()

        return when {
            lowerKey.contains("date") || lowerKey.contains("dob") || lowerKey.contains("birth") -> FieldType.DATE
            lowerKey.contains("phone") || lowerKey.contains("tel") || lowerKey.contains("mobile") -> FieldType.NUMBER
            lowerKey.contains("email") || lowerKey.contains("e-mail") -> FieldType.TEXT
            lowerKey.contains("age") || lowerKey.contains("weight") || lowerKey.contains("height") -> FieldType.NUMBER
            lowerKey.contains("signature") || lowerKey.contains("sign here") -> FieldType.SIGNATURE
            lowerKey.contains("check") || lowerKey.contains("yes/no") || lowerKey.contains("select") -> FieldType.CHECKBOX
            valueText.isBlank() && keyText.endsWith(":") -> FieldType.TEXT
            else -> FieldType.TEXT
        }
    }

    /**
     * Start asynchronous document analysis (for large documents)
     */
    suspend fun startDocumentAnalysis(s3Key: String): String? = withContext(Dispatchers.IO) {
        try {
            val s3Object = S3Object().apply {
                bucket = Constants.AWS.S3_BUCKET
                name = s3Key
            }

            val document = Document().withS3Object(s3Object)

            val request = StartDocumentAnalysisRequest()
                .withDocumentLocation(DocumentLocation().withS3Object(s3Object))
                .withFeatureTypes("FORMS", "TABLES")

            val result = textractClient.startDocumentAnalysis(request)
            result.jobId
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get document analysis results (for async jobs)
     */
    suspend fun getDocumentAnalysis(jobId: String, formId: String): TextractResult = withContext(Dispatchers.IO) {
        try {
            val request = GetDocumentAnalysisRequest().withJobId(jobId)
            val result = textractClient.getDocumentAnalysis(request)

            when (result.jobStatus) {
                "SUCCEEDED" -> {
                    val formFields = extractFormFields(
                        AnalyzeDocumentResult().withBlocks(result.blocks),
                        formId
                    )
                    TextractResult.Success(formFields)
                }
                "FAILED" -> TextractResult.Error("Analysis job failed")
                "IN_PROGRESS" -> TextractResult.InProgress
                else -> TextractResult.Error("Unknown job status: ${result.jobStatus}")
            }
        } catch (e: Exception) {
            TextractResult.Error(e.message ?: "Failed to get analysis results")
        }
    }
}

/**
 * Textract result sealed class
 */
sealed class TextractResult {
    data class Success(val fields: List<FormField>) : TextractResult()
    object InProgress : TextractResult()
    data class Error(val message: String) : TextractResult()
}

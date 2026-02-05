package com.medpull.kiosk.data.repository

import com.medpull.kiosk.data.local.dao.FormFieldDao
import com.medpull.kiosk.data.models.FormField
import com.medpull.kiosk.data.remote.aws.TranslationResult
import com.medpull.kiosk.data.remote.aws.TranslationService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for translation operations
 * Handles field translation with local caching
 */
@Singleton
class TranslationRepository @Inject constructor(
    private val translationService: TranslationService,
    private val formFieldDao: FormFieldDao
) {

    /**
     * Translate form fields to target language
     */
    suspend fun translateFormFields(
        fields: List<FormField>,
        targetLanguage: String
    ): Map<String, String> {
        // Build map of field names to original text
        val fieldsToTranslate = fields.associate { field ->
            field.id to (field.originalText ?: field.fieldName)
        }

        // Translate using AWS Translate
        val translatedFields = translationService.translateFormFields(
            fieldsToTranslate,
            targetLanguage
        )

        // Update database with translations
        translatedFields.forEach { (fieldId, translatedText) ->
            formFieldDao.updateFieldTranslation(fieldId, translatedText)
        }

        return translatedFields
    }

    /**
     * Translate single text
     */
    suspend fun translateText(
        text: String,
        targetLanguage: String
    ): String? {
        return when (val result = translationService.translateToLanguage(text, targetLanguage)) {
            is TranslationResult.Success -> result.translatedText
            is TranslationResult.Error -> null
        }
    }

    /**
     * Translate user input back to English for export
     */
    suspend fun translateToEnglish(
        fieldValues: Map<String, String>,
        sourceLanguage: String
    ): Map<String, String> {
        return translationService.translateFieldValuesToEnglish(
            fieldValues,
            sourceLanguage
        )
    }

    /**
     * Check if translation is needed
     */
    fun isTranslationNeeded(targetLanguage: String): Boolean {
        return targetLanguage != "en"
    }
}

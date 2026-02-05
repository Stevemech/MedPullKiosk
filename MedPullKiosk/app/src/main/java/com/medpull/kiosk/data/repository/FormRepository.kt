package com.medpull.kiosk.data.repository

import android.util.Log
import com.medpull.kiosk.data.local.dao.FormDao
import com.medpull.kiosk.data.local.dao.FormFieldDao
import com.medpull.kiosk.data.local.entities.FormEntity
import com.medpull.kiosk.data.local.entities.FormFieldEntity
import com.medpull.kiosk.data.local.entities.SyncOperationType
import com.medpull.kiosk.data.models.Form
import com.medpull.kiosk.data.models.FormField
import com.medpull.kiosk.data.models.FormStatus
import com.medpull.kiosk.data.remote.aws.S3Service
import com.medpull.kiosk.data.remote.aws.TextractResult
import com.medpull.kiosk.data.remote.aws.TextractService
import com.medpull.kiosk.data.remote.aws.UploadResult
import com.medpull.kiosk.sync.SyncManager
import com.medpull.kiosk.sync.UploadFormPayload
import com.medpull.kiosk.utils.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for form operations
 * Integrates AWS services with local database
 */
@Singleton
class FormRepository @Inject constructor(
    private val formDao: FormDao,
    private val formFieldDao: FormFieldDao,
    private val s3Service: S3Service,
    private val textractService: TextractService,
    private val networkMonitor: NetworkMonitor,
    private val syncManager: SyncManager,
    private val authRepository: AuthRepository
) {

    companion object {
        private const val TAG = "FormRepository"
    }

    /**
     * Get form by ID with fields
     */
    suspend fun getFormById(formId: String): Form? {
        val formEntity = formDao.getFormById(formId) ?: return null
        val fields = formFieldDao.getFieldsByFormId(formId)
        return formEntity.toDomain(fields)
    }

    /**
     * Get form by ID as Flow
     */
    fun getFormByIdFlow(formId: String): Flow<Form?> {
        return combine(
            formDao.getFormByIdFlow(formId),
            formFieldDao.getFieldsByFormIdFlow(formId)
        ) { form, fields ->
            form?.toDomain(fields)
        }
    }

    /**
     * Get forms by user ID
     */
    suspend fun getFormsByUserId(userId: String): List<Form> {
        val forms = formDao.getFormsByUserId(userId)
        return forms.map { form ->
            val fields = formFieldDao.getFieldsByFormId(form.id)
            form.toDomain(fields)
        }
    }

    /**
     * Get forms by user ID as Flow
     */
    fun getFormsByUserIdFlow(userId: String): Flow<List<Form>> {
        return formDao.getFormsByUserIdFlow(userId).map { forms ->
            forms.map { form ->
                val fields = formFieldDao.getFieldsByFormId(form.id)
                form.toDomain(fields)
            }
        }
    }

    /**
     * Save form
     */
    suspend fun saveForm(form: Form) {
        formDao.insertForm(FormEntity.fromDomain(form))
        if (form.fields.isNotEmpty()) {
            formFieldDao.insertFields(form.fields.map { FormFieldEntity.fromDomain(it) })
        }
    }

    /**
     * Update form status
     */
    suspend fun updateFormStatus(formId: String, status: FormStatus) {
        formDao.updateFormStatus(formId, status.name, System.currentTimeMillis())
    }

    /**
     * Update field value
     */
    suspend fun updateFieldValue(fieldId: String, value: String?) {
        formFieldDao.updateFieldValue(fieldId, value)
    }

    /**
     * Delete form
     */
    suspend fun deleteForm(formId: String) {
        formDao.deleteFormById(formId)
    }

    /**
     * Upload and process form
     * Handles offline mode by queuing for later sync
     */
    suspend fun uploadAndProcessForm(
        file: File,
        userId: String,
        formId: String
    ): FormProcessResult {
        // Check if user is still authenticated
        if (!authRepository.isAuthenticated()) {
            Log.e(TAG, "Upload cancelled: user not authenticated")
            updateFormStatus(formId, FormStatus.ERROR)
            return FormProcessResult.Error("Upload cancelled: Please log in again")
        }

        // Check if online
        if (!networkMonitor.isCurrentlyConnected()) {
            Log.d(TAG, "Offline: queuing form processing for $formId")

            // Queue for later processing
            syncManager.queueOperation(
                operationType = SyncOperationType.UPLOAD_FORM,
                entityId = formId,
                payload = UploadFormPayload(
                    formId = formId,
                    userId = userId,
                    localFilePath = file.absolutePath
                ),
                priority = 1
            )

            // Update form status to pending sync
            updateFormStatus(formId, FormStatus.PENDING_SYNC)

            return FormProcessResult.QueuedForSync(
                "Form will be processed when online"
            )
        }

        // Refresh tokens if needed before AWS operations
        val tokensValid = authRepository.refreshTokensIfNeeded()
        if (!tokensValid) {
            Log.e(TAG, "Failed to refresh tokens for upload")
            updateFormStatus(formId, FormStatus.ERROR)
            return FormProcessResult.Error("Session expired. Please log in again")
        }

        // Upload to S3
        val uploadResult = s3Service.uploadFileSync(file, "forms/", userId)

        return when (uploadResult) {
            is UploadResult.Success -> {
                // Update form with S3 key
                formDao.updateFormS3Key(formId, uploadResult.s3Key, System.currentTimeMillis())

                // Update status to processing
                updateFormStatus(formId, FormStatus.PROCESSING)

                // Extract fields with Textract
                val textractResult = textractService.analyzeDocument(uploadResult.s3Key, formId)

                when (textractResult) {
                    is TextractResult.Success -> {
                        // Save fields to database
                        formFieldDao.insertFields(
                            textractResult.fields.map { FormFieldEntity.fromDomain(it) }
                        )

                        // Update status to ready
                        updateFormStatus(formId, FormStatus.READY)
                        formDao.updateFormStatus(
                            formId,
                            FormStatus.READY.name,
                            System.currentTimeMillis()
                        )

                        FormProcessResult.Success(textractResult.fields)
                    }
                    is TextractResult.Error -> {
                        updateFormStatus(formId, FormStatus.ERROR)
                        FormProcessResult.Error(textractResult.message)
                    }
                    TextractResult.InProgress -> {
                        FormProcessResult.Processing
                    }
                }
            }
            is UploadResult.Error -> {
                updateFormStatus(formId, FormStatus.ERROR)
                FormProcessResult.Error(uploadResult.message)
            }
            is UploadResult.QueuedForSync -> {
                updateFormStatus(formId, FormStatus.PENDING_SYNC)
                FormProcessResult.QueuedForSync(uploadResult.message)
            }
        }
    }

    /**
     * Get fields by form ID
     */
    suspend fun getFormFields(formId: String): List<FormField> {
        return formFieldDao.getFieldsByFormId(formId).map { it.toDomain() }
    }

    /**
     * Update multiple field values
     */
    suspend fun updateFieldValues(fieldValues: Map<String, String>) {
        fieldValues.forEach { (fieldId, value) ->
            formFieldDao.updateFieldValue(fieldId, value)
        }
    }

    /**
     * Check if all required fields are filled
     */
    suspend fun areAllRequiredFieldsFilled(formId: String): Boolean {
        val fields = formFieldDao.getFieldsByFormId(formId)
        return fields
            .filter { it.required }
            .all { !it.value.isNullOrBlank() }
    }

    /**
     * Get form completion percentage
     */
    suspend fun getFormCompletionPercentage(formId: String): Float {
        val fields = formFieldDao.getFieldsByFormId(formId)
        if (fields.isEmpty()) return 0f

        val filledCount = fields.count { !it.value.isNullOrBlank() }
        return (filledCount.toFloat() / fields.size) * 100
    }
}

/**
 * Form process result sealed class
 */
sealed class FormProcessResult {
    data class Success(val fields: List<FormField>) : FormProcessResult()
    object Processing : FormProcessResult()
    data class Error(val message: String) : FormProcessResult()
    data class QueuedForSync(val message: String) : FormProcessResult()
}

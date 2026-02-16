package com.medpull.kiosk.healthcare.repository

import android.util.Log
import com.medpull.kiosk.data.local.dao.FhirMappingDao
import com.medpull.kiosk.data.local.dao.SyncQueueDao
import com.medpull.kiosk.data.local.entities.FhirMappingEntity
import com.medpull.kiosk.data.local.entities.SyncOperationType
import com.medpull.kiosk.data.local.entities.SyncQueueEntity
import com.medpull.kiosk.data.models.Form
import com.medpull.kiosk.data.models.FormField
import com.medpull.kiosk.data.repository.FormRepository
import com.medpull.kiosk.healthcare.auth.DynamicFhirBaseUrlInterceptor
import com.medpull.kiosk.healthcare.client.FhirClient
import com.medpull.kiosk.healthcare.client.FhirServerConfig
import com.medpull.kiosk.healthcare.mapper.AppToHealthcareMapper
import com.medpull.kiosk.healthcare.models.HealthcarePatient
import com.medpull.kiosk.security.HipaaAuditLogger
import com.medpull.kiosk.security.SecureStorageManager
import com.medpull.kiosk.utils.PdfUtils
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates FHIR operations: patient search/import, form export, connection verification.
 * All operations are HIPAA audit-logged.
 */
@Singleton
class FhirRepository @Inject constructor(
    private val fhirClient: FhirClient,
    private val mapper: AppToHealthcareMapper,
    private val fhirMappingDao: FhirMappingDao,
    private val syncQueueDao: SyncQueueDao,
    private val formRepository: FormRepository,
    private val pdfUtils: PdfUtils,
    private val auditLogger: HipaaAuditLogger,
    private val secureStorageManager: SecureStorageManager,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "FhirRepository"
        private const val KEY_FHIR_SERVER_URL = "fhir_server_url"
        private const val KEY_FHIR_AUTH_TYPE = "fhir_auth_type"
        private const val KEY_FHIR_CLIENT_ID = "fhir_client_id"
    }

    /**
     * Search patients on the FHIR server by name or MRN.
     */
    suspend fun searchPatients(
        userId: String,
        name: String? = null,
        mrn: String? = null
    ): Result<List<HealthcarePatient>> {
        auditLogger.log(
            userId = userId,
            action = "FHIR_PATIENT_SEARCH",
            resourceType = "Patient",
            resourceId = null,
            description = "FHIR patient search",
            metadata = buildMap {
                if (name != null) put("searchName", name)
                if (mrn != null) put("searchMrn", mrn)
            }
        )

        return fhirClient.searchPatients(name = name, identifier = mrn)
    }

    /**
     * Import a patient from the FHIR server and map demographics to form fields for pre-fill.
     * Returns a map of fieldId -> suggested value.
     */
    suspend fun importPatientToForm(
        userId: String,
        patientId: String,
        formId: String,
        fields: List<FormField>
    ): Result<Map<String, String>> {
        auditLogger.log(
            userId = userId,
            action = "FHIR_PATIENT_IMPORT",
            resourceType = "Patient",
            resourceId = patientId,
            description = "Importing FHIR patient to pre-fill form",
            metadata = mapOf("formId" to formId)
        )

        return try {
            val patientResult = fhirClient.readPatient(patientId)
            val patient = patientResult.getOrThrow()

            // Save the FHIR mapping
            fhirMappingDao.insert(
                FhirMappingEntity(
                    localId = formId,
                    fhirResourceId = patientId,
                    resourceType = "Patient",
                    fhirServerUrl = getServerUrl()
                )
            )

            val suggestions = mapper.mapPatientToFormFields(patient, fields)

            auditLogger.log(
                userId = userId,
                action = "FHIR_PATIENT_IMPORT_SUCCESS",
                resourceType = "Patient",
                resourceId = patientId,
                description = "Patient data mapped to ${suggestions.size} form fields",
                metadata = mapOf("formId" to formId, "fieldsMapped" to suggestions.size.toString())
            )

            Result.success(suggestions)
        } catch (e: Exception) {
            Log.e(TAG, "Patient import failed", e)
            auditLogger.log(
                userId = userId,
                action = "FHIR_PATIENT_IMPORT_FAILED",
                resourceType = "Patient",
                resourceId = patientId,
                description = "Import failed: ${e.message}"
            )
            Result.failure(e)
        }
    }

    /**
     * Export a completed form to the FHIR server as QuestionnaireResponse + DocumentReference.
     */
    suspend fun exportFormToFhir(
        userId: String,
        form: Form,
        patientFhirId: String? = null,
        pdfData: ByteArray? = null
    ): Result<ExportResult> {
        auditLogger.log(
            userId = userId,
            action = "FHIR_FORM_EXPORT",
            resourceType = "QuestionnaireResponse",
            resourceId = form.id,
            description = "Exporting form to FHIR server"
        )

        return try {
            // 1. Create QuestionnaireResponse
            val qr = mapper.formToQuestionnaireResponse(form).copy(
                subjectId = patientFhirId
            )
            val qrResult = fhirClient.createQuestionnaireResponse(qr)
            val qrId = qrResult.getOrThrow()

            // Save mapping
            fhirMappingDao.insert(
                FhirMappingEntity(
                    localId = form.id,
                    fhirResourceId = qrId,
                    resourceType = "QuestionnaireResponse",
                    fhirServerUrl = getServerUrl()
                )
            )

            // 2. Create DocumentReference with PDF if data available
            var docRefId: String? = null
            if (pdfData != null) {
                val docRef = mapper.formToDocumentReference(form, patientFhirId, pdfData)
                val docResult = fhirClient.createDocumentReference(docRef)
                docRefId = docResult.getOrNull()

                if (docRefId != null) {
                    fhirMappingDao.insert(
                        FhirMappingEntity(
                            localId = form.id,
                            fhirResourceId = docRefId,
                            resourceType = "DocumentReference",
                            fhirServerUrl = getServerUrl()
                        )
                    )
                }
            }

            auditLogger.log(
                userId = userId,
                action = "FHIR_FORM_EXPORT_SUCCESS",
                resourceType = "QuestionnaireResponse",
                resourceId = qrId,
                description = "Form exported to FHIR successfully",
                metadata = buildMap {
                    put("formId", form.id)
                    put("questionnaireResponseId", qrId)
                    if (docRefId != null) put("documentReferenceId", docRefId)
                }
            )

            Result.success(
                ExportResult(
                    questionnaireResponseId = qrId,
                    documentReferenceId = docRefId
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "FHIR export failed", e)
            auditLogger.log(
                userId = userId,
                action = "FHIR_FORM_EXPORT_FAILED",
                resourceType = "QuestionnaireResponse",
                resourceId = form.id,
                description = "Export failed: ${e.message}"
            )

            // Queue for offline sync
            queueForSync(form.id, userId)

            Result.failure(e)
        }
    }

    /**
     * Queue a FHIR export for later when connectivity is restored.
     */
    private suspend fun queueForSync(formId: String, userId: String) {
        try {
            val payload = gson.toJson(mapOf("formId" to formId, "userId" to userId))
            syncQueueDao.insertSyncOperation(
                SyncQueueEntity(
                    operationType = SyncOperationType.FHIR_EXPORT_FORM.name,
                    entityId = formId,
                    payload = payload,
                    priority = 1
                )
            )
            Log.d(TAG, "FHIR export queued for sync: $formId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue FHIR export for sync", e)
        }
    }

    /**
     * Verify the configured FHIR server is reachable.
     */
    suspend fun verifyConnection(userId: String): Result<Boolean> {
        auditLogger.log(
            userId = userId,
            action = "FHIR_CONNECTION_TEST",
            resourceType = "CapabilityStatement",
            resourceId = null,
            description = "Testing FHIR server connection"
        )

        val result = fhirClient.verifyConnection()

        auditLogger.log(
            userId = userId,
            action = if (result.isSuccess) "FHIR_CONNECTION_SUCCESS" else "FHIR_CONNECTION_FAILED",
            resourceType = "CapabilityStatement",
            resourceId = null,
            description = if (result.isSuccess) "FHIR server connection verified" else "Connection failed: ${result.exceptionOrNull()?.message}"
        )

        return result
    }

    // ── Server Config Persistence ──

    fun saveServerConfig(config: FhirServerConfig) {
        secureStorageManager.saveSecureString(KEY_FHIR_SERVER_URL, config.serverUrl)
        secureStorageManager.saveSecureString(KEY_FHIR_AUTH_TYPE, config.authType.name)
        secureStorageManager.saveSecureString(KEY_FHIR_CLIENT_ID, config.smartClientId)
        // Also update DynamicFhirBaseUrlInterceptor's stored URL
        secureStorageManager.saveSecureString(DynamicFhirBaseUrlInterceptor.KEY_FHIR_SERVER_URL, config.serverUrl)
    }

    fun loadServerConfig(): FhirServerConfig {
        return FhirServerConfig(
            serverUrl = secureStorageManager.getSecureString(KEY_FHIR_SERVER_URL) ?: "",
            authType = try {
                com.medpull.kiosk.healthcare.client.FhirAuthType.valueOf(
                    secureStorageManager.getSecureString(KEY_FHIR_AUTH_TYPE) ?: "NONE"
                )
            } catch (e: Exception) {
                com.medpull.kiosk.healthcare.client.FhirAuthType.NONE
            },
            smartClientId = secureStorageManager.getSecureString(KEY_FHIR_CLIENT_ID) ?: ""
        )
    }

    fun getServerUrl(): String {
        return secureStorageManager.getSecureString(KEY_FHIR_SERVER_URL) ?: ""
    }

    /**
     * Get the FHIR resource ID mapped to a local entity.
     */
    suspend fun getFhirId(localId: String, resourceType: String): String? {
        return fhirMappingDao.getByLocalId(localId, resourceType)?.fhirResourceId
    }
}

data class ExportResult(
    val questionnaireResponseId: String,
    val documentReferenceId: String? = null
)

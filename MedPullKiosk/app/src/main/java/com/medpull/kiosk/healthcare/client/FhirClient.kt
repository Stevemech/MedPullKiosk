package com.medpull.kiosk.healthcare.client

import android.util.Log
import ca.uhn.fhir.context.FhirContext
import com.medpull.kiosk.healthcare.adapter.fhir.FhirDocumentMapper
import com.medpull.kiosk.healthcare.adapter.fhir.FhirPatientMapper
import com.medpull.kiosk.healthcare.adapter.fhir.FhirResponseMapper
import com.medpull.kiosk.healthcare.models.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.hl7.fhir.r4.model.Bundle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level FHIR client that provides typed operations using neutral healthcare models.
 */
@Singleton
class FhirClient @Inject constructor(
    private val fhirApiService: FhirApiService,
    private val fhirContext: FhirContext,
    private val patientMapper: FhirPatientMapper,
    private val responseMapper: FhirResponseMapper,
    private val documentMapper: FhirDocumentMapper
) {
    companion object {
        private const val TAG = "FhirClient"
        private val FHIR_JSON = "application/fhir+json".toMediaType()
    }

    private val jsonParser by lazy { fhirContext.newJsonParser() }

    /**
     * Verify server connectivity by fetching metadata (CapabilityStatement).
     */
    suspend fun verifyConnection(): Result<Boolean> {
        return try {
            val response = fhirApiService.getMetadata()
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Server returned ${response.code()}: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection verification failed", e)
            Result.failure(e)
        }
    }

    /**
     * Search patients by name or identifier.
     */
    suspend fun searchPatients(
        name: String? = null,
        identifier: String? = null
    ): Result<List<HealthcarePatient>> {
        return try {
            val params = mutableMapOf<String, String>()
            if (!name.isNullOrBlank()) params["name"] = name
            if (!identifier.isNullOrBlank()) params["identifier"] = identifier
            params["_count"] = "20"

            val response = fhirApiService.searchPatients(params)
            if (response.isSuccessful) {
                val body = response.body()?.string() ?: return Result.success(emptyList())
                val bundle = jsonParser.parseResource(Bundle::class.java, body)
                val patients = bundle.entry
                    ?.filter { it.resource is org.hl7.fhir.r4.model.Patient }
                    ?.map { patientMapper.fromFhir(it.resource as org.hl7.fhir.r4.model.Patient) }
                    ?: emptyList()
                Result.success(patients)
            } else {
                Result.failure(Exception("Search failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Patient search failed", e)
            Result.failure(e)
        }
    }

    /**
     * Read a single patient by FHIR ID.
     */
    suspend fun readPatient(id: String): Result<HealthcarePatient> {
        return try {
            val response = fhirApiService.readPatient(id)
            if (response.isSuccessful) {
                val body = response.body()?.string()
                    ?: return Result.failure(Exception("Empty response body"))
                val fhirPatient = jsonParser.parseResource(
                    org.hl7.fhir.r4.model.Patient::class.java, body
                )
                Result.success(patientMapper.fromFhir(fhirPatient))
            } else {
                Result.failure(Exception("Read failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Patient read failed", e)
            Result.failure(e)
        }
    }

    /**
     * Create a QuestionnaireResponse on the FHIR server.
     * Returns the server-assigned ID.
     */
    suspend fun createQuestionnaireResponse(
        response: HealthcareQuestionnaireResponse
    ): Result<String> {
        return try {
            val fhirResponse = responseMapper.toFhir(response)
            val json = jsonParser.encodeResourceToString(fhirResponse)
            val requestBody = json.toRequestBody(FHIR_JSON)

            val httpResponse = fhirApiService.createQuestionnaireResponse(requestBody)
            if (httpResponse.isSuccessful) {
                val location = httpResponse.headers()["Location"] ?: ""
                val fhirId = extractIdFromLocation(location)
                    ?: httpResponse.body()?.string()?.let { parseIdFromBody(it) }
                    ?: ""
                Result.success(fhirId)
            } else {
                Result.failure(Exception("Create failed: ${httpResponse.code()} ${httpResponse.message()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "QuestionnaireResponse creation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Create a DocumentReference on the FHIR server.
     * Returns the server-assigned ID.
     */
    suspend fun createDocumentReference(
        doc: HealthcareDocumentReference
    ): Result<String> {
        return try {
            val fhirDoc = documentMapper.toFhir(doc)
            val json = jsonParser.encodeResourceToString(fhirDoc)
            val requestBody = json.toRequestBody(FHIR_JSON)

            val httpResponse = fhirApiService.createDocumentReference(requestBody)
            if (httpResponse.isSuccessful) {
                val location = httpResponse.headers()["Location"] ?: ""
                val fhirId = extractIdFromLocation(location)
                    ?: httpResponse.body()?.string()?.let { parseIdFromBody(it) }
                    ?: ""
                Result.success(fhirId)
            } else {
                Result.failure(Exception("Create failed: ${httpResponse.code()} ${httpResponse.message()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "DocumentReference creation failed", e)
            Result.failure(e)
        }
    }

    private fun extractIdFromLocation(location: String): String? {
        // Location header format: [base]/ResourceType/[id]/_history/[version]
        val parts = location.split("/")
        val idx = parts.indexOfLast { it.matches(Regex("[a-zA-Z]+")) && parts.getOrNull(parts.indexOf(it) + 1)?.matches(Regex("[a-zA-Z0-9-]+")) == true }
        return if (idx >= 0 && idx + 1 < parts.size) parts[idx + 1] else null
    }

    private fun parseIdFromBody(body: String): String? {
        return try {
            val resource = jsonParser.parseResource(body)
            resource.idElement?.idPart
        } catch (e: Exception) {
            null
        }
    }
}

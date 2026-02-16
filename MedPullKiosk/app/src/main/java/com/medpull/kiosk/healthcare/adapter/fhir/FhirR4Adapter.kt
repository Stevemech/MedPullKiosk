package com.medpull.kiosk.healthcare.adapter.fhir

import ca.uhn.fhir.context.FhirContext
import com.medpull.kiosk.healthcare.adapter.HealthcareProtocolAdapter
import com.medpull.kiosk.healthcare.models.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FHIR R4 implementation of HealthcareProtocolAdapter.
 * Uses HAPI FHIR JSON parser for serialization/deserialization.
 */
@Singleton
class FhirR4Adapter @Inject constructor(
    private val fhirContext: FhirContext,
    private val patientMapper: FhirPatientMapper,
    private val questionnaireMapper: FhirQuestionnaireMapper,
    private val responseMapper: FhirResponseMapper,
    private val documentMapper: FhirDocumentMapper
) : HealthcareProtocolAdapter {

    private val jsonParser by lazy {
        fhirContext.newJsonParser().setPrettyPrint(false)
    }

    override fun serializePatient(patient: HealthcarePatient): String {
        val fhirPatient = patientMapper.toFhir(patient)
        return jsonParser.encodeResourceToString(fhirPatient)
    }

    override fun deserializePatient(data: String): HealthcarePatient {
        val fhirPatient = jsonParser.parseResource(
            org.hl7.fhir.r4.model.Patient::class.java, data
        )
        return patientMapper.fromFhir(fhirPatient)
    }

    override fun serializeQuestionnaire(questionnaire: HealthcareQuestionnaire): String {
        val fhirQuestionnaire = questionnaireMapper.toFhir(questionnaire)
        return jsonParser.encodeResourceToString(fhirQuestionnaire)
    }

    override fun deserializeQuestionnaire(data: String): HealthcareQuestionnaire {
        val fhirQuestionnaire = jsonParser.parseResource(
            org.hl7.fhir.r4.model.Questionnaire::class.java, data
        )
        return questionnaireMapper.fromFhir(fhirQuestionnaire)
    }

    override fun serializeQuestionnaireResponse(response: HealthcareQuestionnaireResponse): String {
        val fhirResponse = responseMapper.toFhir(response)
        return jsonParser.encodeResourceToString(fhirResponse)
    }

    override fun deserializeQuestionnaireResponse(data: String): HealthcareQuestionnaireResponse {
        val fhirResponse = jsonParser.parseResource(
            org.hl7.fhir.r4.model.QuestionnaireResponse::class.java, data
        )
        return responseMapper.fromFhir(fhirResponse)
    }

    override fun serializeDocumentReference(doc: HealthcareDocumentReference): String {
        val fhirDoc = documentMapper.toFhir(doc)
        return jsonParser.encodeResourceToString(fhirDoc)
    }

    override fun deserializeDocumentReference(data: String): HealthcareDocumentReference {
        val fhirDoc = jsonParser.parseResource(
            org.hl7.fhir.r4.model.DocumentReference::class.java, data
        )
        return documentMapper.fromFhir(fhirDoc)
    }
}

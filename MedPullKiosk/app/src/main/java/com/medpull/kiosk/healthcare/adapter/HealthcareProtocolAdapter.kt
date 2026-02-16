package com.medpull.kiosk.healthcare.adapter

import com.medpull.kiosk.healthcare.models.*

/**
 * Protocol-agnostic adapter interface for serializing/deserializing healthcare resources.
 * Implement for each standard: FHIR R4 now, HL7 v2 later.
 */
interface HealthcareProtocolAdapter {
    fun serializePatient(patient: HealthcarePatient): String
    fun deserializePatient(data: String): HealthcarePatient

    fun serializeQuestionnaire(questionnaire: HealthcareQuestionnaire): String
    fun deserializeQuestionnaire(data: String): HealthcareQuestionnaire

    fun serializeQuestionnaireResponse(response: HealthcareQuestionnaireResponse): String
    fun deserializeQuestionnaireResponse(data: String): HealthcareQuestionnaireResponse

    fun serializeDocumentReference(doc: HealthcareDocumentReference): String
    fun deserializeDocumentReference(data: String): HealthcareDocumentReference
}

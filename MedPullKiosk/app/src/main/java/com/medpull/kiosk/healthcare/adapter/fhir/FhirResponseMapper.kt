package com.medpull.kiosk.healthcare.adapter.fhir

import com.medpull.kiosk.healthcare.models.*
import org.hl7.fhir.r4.model.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps between HealthcareQuestionnaireResponse and FHIR R4 QuestionnaireResponse resource.
 */
@Singleton
class FhirResponseMapper @Inject constructor() {

    fun toFhir(response: HealthcareQuestionnaireResponse): QuestionnaireResponse {
        return QuestionnaireResponse().apply {
            if (response.id != null) {
                id = response.id
            }
            if (response.questionnaireId != null) {
                questionnaire = "Questionnaire/${response.questionnaireId}"
            }
            status = when (response.status) {
                "in-progress" -> QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS
                "completed" -> QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
                "amended" -> QuestionnaireResponse.QuestionnaireResponseStatus.AMENDED
                "stopped" -> QuestionnaireResponse.QuestionnaireResponseStatus.STOPPED
                else -> QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
            }
            if (response.authoredDate != null) {
                authoredElement = DateTimeType(response.authoredDate)
            }
            if (response.subjectId != null) {
                subject = Reference("Patient/${response.subjectId}")
            }
            item = response.items.map { mapItemToFhir(it) }
        }
    }

    fun fromFhir(fhirResponse: QuestionnaireResponse): HealthcareQuestionnaireResponse {
        return HealthcareQuestionnaireResponse(
            id = fhirResponse.idElement?.idPart,
            questionnaireId = fhirResponse.questionnaire
                ?.removePrefix("Questionnaire/"),
            status = when (fhirResponse.status) {
                QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS -> "in-progress"
                QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED -> "completed"
                QuestionnaireResponse.QuestionnaireResponseStatus.AMENDED -> "amended"
                QuestionnaireResponse.QuestionnaireResponseStatus.STOPPED -> "stopped"
                else -> "completed"
            },
            authoredDate = fhirResponse.authoredElement?.valueAsString,
            subjectId = fhirResponse.subject?.referenceElement?.idPart,
            items = fhirResponse.item?.map { mapItemFromFhir(it) } ?: emptyList()
        )
    }

    private fun mapItemToFhir(
        item: HealthcareResponseItem
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent {
        return QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            linkId = item.linkId
            text = item.text
            answer = item.answers.map { mapAnswerToFhir(it) }
            this.item = item.items.map { mapItemToFhir(it) }
        }
    }

    private fun mapAnswerToFhir(
        answer: HealthcareResponseAnswer
    ): QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent {
        return QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
            when {
                answer.valueString != null -> value = StringType(answer.valueString)
                answer.valueBoolean != null -> value = BooleanType(answer.valueBoolean)
                answer.valueInteger != null -> value = IntegerType(answer.valueInteger)
                answer.valueDecimal != null -> value = DecimalType(answer.valueDecimal)
                answer.valueDate != null -> value = DateType(answer.valueDate)
                answer.valueCoding != null -> value = Coding().apply {
                    system = answer.valueCoding.system
                    code = answer.valueCoding.code
                    display = answer.valueCoding.display
                }
            }
        }
    }

    private fun mapItemFromFhir(
        fhirItem: QuestionnaireResponse.QuestionnaireResponseItemComponent
    ): HealthcareResponseItem {
        return HealthcareResponseItem(
            linkId = fhirItem.linkId ?: "",
            text = fhirItem.text,
            answers = fhirItem.answer?.map { mapAnswerFromFhir(it) } ?: emptyList(),
            items = fhirItem.item?.map { mapItemFromFhir(it) } ?: emptyList()
        )
    }

    private fun mapAnswerFromFhir(
        fhirAnswer: QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent
    ): HealthcareResponseAnswer {
        val value = fhirAnswer.value
        return HealthcareResponseAnswer(
            valueString = (value as? StringType)?.value,
            valueBoolean = (value as? BooleanType)?.value,
            valueInteger = (value as? IntegerType)?.value,
            valueDecimal = (value as? DecimalType)?.value?.toDouble(),
            valueDate = (value as? DateType)?.valueAsString,
            valueCoding = (value as? Coding)?.let {
                HealthcareCoding(system = it.system, code = it.code ?: "", display = it.display)
            }
        )
    }
}

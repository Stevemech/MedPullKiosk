package com.medpull.kiosk.healthcare.adapter.fhir

import com.medpull.kiosk.healthcare.models.*
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Questionnaire
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps between HealthcareQuestionnaire and FHIR R4 Questionnaire resource.
 */
@Singleton
class FhirQuestionnaireMapper @Inject constructor(
    private val fieldTypeMapper: FhirFieldTypeMapper
) {

    fun toFhir(questionnaire: HealthcareQuestionnaire): Questionnaire {
        return Questionnaire().apply {
            if (questionnaire.id != null) {
                id = questionnaire.id
            }
            title = questionnaire.title
            status = when (questionnaire.status) {
                "active" -> Enumerations.PublicationStatus.ACTIVE
                "draft" -> Enumerations.PublicationStatus.DRAFT
                "retired" -> Enumerations.PublicationStatus.RETIRED
                else -> Enumerations.PublicationStatus.ACTIVE
            }
            item = questionnaire.items.map { mapItemToFhir(it) }
        }
    }

    fun fromFhir(fhirQuestionnaire: Questionnaire): HealthcareQuestionnaire {
        return HealthcareQuestionnaire(
            id = fhirQuestionnaire.idElement?.idPart,
            title = fhirQuestionnaire.title,
            status = when (fhirQuestionnaire.status) {
                Enumerations.PublicationStatus.ACTIVE -> "active"
                Enumerations.PublicationStatus.DRAFT -> "draft"
                Enumerations.PublicationStatus.RETIRED -> "retired"
                else -> "active"
            },
            items = fhirQuestionnaire.item?.map { mapItemFromFhir(it) } ?: emptyList()
        )
    }

    private fun mapItemToFhir(item: HealthcareQuestionnaireItem): Questionnaire.QuestionnaireItemComponent {
        return Questionnaire.QuestionnaireItemComponent().apply {
            linkId = item.linkId
            text = item.text
            type = fieldTypeMapper.toFhirItemType(item.type)
            required = item.required
            this.item = item.items.map { mapItemToFhir(it) }

            item.answerOptions.forEach { option ->
                addAnswerOption(
                    Questionnaire.QuestionnaireItemAnswerOptionComponent().apply {
                        value = Coding().apply {
                            system = option.system
                            code = option.code
                            display = option.display
                        }
                    }
                )
            }
        }
    }

    private fun mapItemFromFhir(fhirItem: Questionnaire.QuestionnaireItemComponent): HealthcareQuestionnaireItem {
        return HealthcareQuestionnaireItem(
            linkId = fhirItem.linkId ?: "",
            text = fhirItem.text,
            type = fieldTypeMapper.fromFhirItemType(fhirItem.type),
            required = fhirItem.required,
            items = fhirItem.item?.map { mapItemFromFhir(it) } ?: emptyList(),
            answerOptions = fhirItem.answerOption?.mapNotNull { option ->
                val coding = option.valueCoding
                if (coding != null) {
                    HealthcareCoding(
                        system = coding.system,
                        code = coding.code ?: "",
                        display = coding.display
                    )
                } else null
            } ?: emptyList()
        )
    }
}

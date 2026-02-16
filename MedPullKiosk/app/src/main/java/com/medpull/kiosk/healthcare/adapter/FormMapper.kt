package com.medpull.kiosk.healthcare.adapter

import com.medpull.kiosk.data.models.Form
import com.medpull.kiosk.data.models.FormField
import com.medpull.kiosk.healthcare.models.HealthcareQuestionnaire
import com.medpull.kiosk.healthcare.models.HealthcareQuestionnaireResponse

/**
 * Maps between app Form/FormField models and neutral healthcare questionnaire models.
 */
interface FormMapper {
    fun formToQuestionnaire(form: Form): HealthcareQuestionnaire
    fun formToQuestionnaireResponse(form: Form): HealthcareQuestionnaireResponse
    fun questionnaireToFields(questionnaire: HealthcareQuestionnaire, formId: String): List<FormField>
}

package com.medpull.kiosk.healthcare.mapper

import com.medpull.kiosk.data.models.*
import com.medpull.kiosk.healthcare.adapter.fhir.FhirFieldTypeMapper
import com.medpull.kiosk.healthcare.models.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps between app domain models and neutral healthcare models.
 * Includes heuristic field matching for patient data pre-fill.
 */
@Singleton
class AppToHealthcareMapper @Inject constructor(
    private val fieldTypeMapper: FhirFieldTypeMapper
) {

    // ── User <-> HealthcarePatient ──

    fun userToPatient(user: User): HealthcarePatient {
        return HealthcarePatient(
            id = null,
            givenNames = listOfNotNull(user.firstName),
            familyName = user.lastName,
            email = user.email,
            identifiers = listOf(
                HealthcareIdentifier(
                    system = "urn:medpull:kiosk:user-id",
                    value = user.id,
                    use = IdentifierUse.USUAL
                )
            )
        )
    }

    fun patientToUser(patient: HealthcarePatient, existingUser: User? = null): User {
        return User(
            id = existingUser?.id ?: UUID.randomUUID().toString(),
            email = patient.email ?: existingUser?.email ?: "",
            username = existingUser?.username ?: patient.email ?: patient.fullName.lowercase().replace(" ", "."),
            firstName = patient.givenNames.firstOrNull() ?: existingUser?.firstName,
            lastName = patient.familyName ?: existingUser?.lastName,
            preferredLanguage = existingUser?.preferredLanguage ?: "en",
            createdAt = existingUser?.createdAt ?: System.currentTimeMillis(),
            lastLoginAt = existingUser?.lastLoginAt
        )
    }

    // ── Form <-> HealthcareQuestionnaire ──

    fun formToQuestionnaire(form: Form): HealthcareQuestionnaire {
        return HealthcareQuestionnaire(
            id = form.id,
            title = form.fileName.removeSuffix(".pdf"),
            status = "active",
            items = form.fields
                .filter { it.fieldType != FieldType.STATIC_LABEL }
                .map { field ->
                    HealthcareQuestionnaireItem(
                        linkId = field.id,
                        text = field.fieldName,
                        type = fieldTypeMapper.fieldTypeToQuestionnaireItemType(field.fieldType),
                        required = field.required
                    )
                }
        )
    }

    fun formToQuestionnaireResponse(form: Form): HealthcareQuestionnaireResponse {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        return HealthcareQuestionnaireResponse(
            questionnaireId = form.id,
            status = if (form.status == FormStatus.COMPLETED || form.status == FormStatus.EXPORTED) {
                "completed"
            } else {
                "in-progress"
            },
            authoredDate = dateFormat.format(Date()),
            items = form.fields
                .filter { it.fieldType != FieldType.STATIC_LABEL && it.value != null }
                .map { field ->
                    HealthcareResponseItem(
                        linkId = field.id,
                        text = field.fieldName,
                        answers = listOf(fieldValueToAnswer(field))
                    )
                }
        )
    }

    private fun fieldValueToAnswer(field: FormField): HealthcareResponseAnswer {
        val value = field.value ?: ""
        return when (field.fieldType) {
            FieldType.CHECKBOX -> HealthcareResponseAnswer(
                valueBoolean = value.equals("true", ignoreCase = true) ||
                        value.equals("yes", ignoreCase = true) ||
                        value == "1"
            )
            FieldType.NUMBER -> {
                val intVal = value.toIntOrNull()
                val decVal = value.toDoubleOrNull()
                when {
                    intVal != null -> HealthcareResponseAnswer(valueInteger = intVal)
                    decVal != null -> HealthcareResponseAnswer(valueDecimal = decVal)
                    else -> HealthcareResponseAnswer(valueString = value)
                }
            }
            FieldType.DATE -> HealthcareResponseAnswer(valueDate = value)
            else -> HealthcareResponseAnswer(valueString = value)
        }
    }

    // ── Patient Pre-Fill: heuristic field matching ──

    /**
     * Attempts to pre-fill form fields from patient demographics using heuristic name matching.
     * Returns a map of fieldId -> suggested value.
     */
    fun mapPatientToFormFields(
        patient: HealthcarePatient,
        fields: List<FormField>
    ): Map<String, String> {
        val suggestions = mutableMapOf<String, String>()

        for (field in fields) {
            val name = field.fieldName.lowercase().trim()
            val matchedValue = matchFieldToPatientData(name, patient)
            if (matchedValue != null) {
                suggestions[field.id] = matchedValue
            }
        }

        return suggestions
    }

    private fun matchFieldToPatientData(fieldName: String, patient: HealthcarePatient): String? {
        return when {
            // Full name
            fieldName.matches(Regex(".*(full|patient|complete)\\s*name.*")) ->
                patient.fullName.takeIf { it.isNotBlank() }

            // First name
            fieldName.matches(Regex(".*(first|given|fore)\\s*name.*")) ||
            fieldName == "first name" || fieldName == "given name" ->
                patient.givenNames.firstOrNull()

            // Last name
            fieldName.matches(Regex(".*(last|family|sur)\\s*name.*")) ||
            fieldName == "last name" || fieldName == "family name" ->
                patient.familyName

            // Middle name
            fieldName.matches(Regex(".*middle\\s*name.*")) ->
                patient.givenNames.getOrNull(1)

            // Date of birth
            fieldName.matches(Regex(".*(date.*birth|dob|birth.*date|born).*")) ->
                patient.dateOfBirth

            // Gender / Sex
            fieldName.matches(Regex(".*(gender|sex).*")) ->
                patient.gender.name.lowercase().replaceFirstChar { it.uppercase() }

            // Email
            fieldName.matches(Regex(".*(e-?mail|email.*address).*")) ->
                patient.email

            // Phone
            fieldName.matches(Regex(".*(phone|telephone|tel|mobile|cell).*")) ->
                patient.telecom.firstOrNull {
                    it.system == ContactPointSystem.PHONE || it.system == ContactPointSystem.SMS
                }?.value

            // MRN / Patient ID
            fieldName.matches(Regex(".*(mrn|medical.*record|patient.*id|chart.*number).*")) ->
                patient.mrn

            // Street address
            fieldName.matches(Regex(".*(street|address\\s*(line)?\\s*1?).*")) &&
            !fieldName.contains("city") && !fieldName.contains("state") &&
            !fieldName.contains("zip") ->
                patient.addresses.firstOrNull()?.lines?.firstOrNull()

            // Address line 2
            fieldName.matches(Regex(".*(address\\s*(line)?\\s*2|apt|suite|unit).*")) ->
                patient.addresses.firstOrNull()?.lines?.getOrNull(1)

            // City
            fieldName.matches(Regex(".*(city|town|municipality).*")) ->
                patient.addresses.firstOrNull()?.city

            // State
            fieldName.matches(Regex(".*(state|province|region).*")) &&
            !fieldName.contains("zip") ->
                patient.addresses.firstOrNull()?.state

            // Zip / Postal code
            fieldName.matches(Regex(".*(zip|postal|postcode|post.*code).*")) ->
                patient.addresses.firstOrNull()?.postalCode

            // Country
            fieldName.matches(Regex(".*(country|nation).*")) ->
                patient.addresses.firstOrNull()?.country

            else -> null
        }
    }

    // ── DocumentReference from Form ──

    fun formToDocumentReference(
        form: Form,
        patientId: String?,
        pdfData: ByteArray
    ): HealthcareDocumentReference {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        return HealthcareDocumentReference(
            status = DocumentStatus.CURRENT,
            type = HealthcareCoding(
                system = "http://loinc.org",
                code = "74465-6",
                display = "Questionnaire response Document"
            ),
            subjectId = patientId,
            date = dateFormat.format(Date()),
            description = "Completed form: ${form.fileName}",
            content = listOf(
                HealthcareAttachment(
                    contentType = "application/pdf",
                    data = pdfData,
                    title = form.fileName
                )
            )
        )
    }
}

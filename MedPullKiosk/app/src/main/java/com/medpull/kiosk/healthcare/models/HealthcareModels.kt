package com.medpull.kiosk.healthcare.models

/**
 * Protocol-agnostic healthcare data models.
 * These neutral models decouple the app from any specific healthcare standard (FHIR, HL7 v2, etc.)
 */

// ── Supporting Types ──

enum class AdministrativeGender {
    MALE, FEMALE, OTHER, UNKNOWN
}

enum class IdentifierUse {
    USUAL, OFFICIAL, TEMP, SECONDARY, OLD
}

enum class ContactPointSystem {
    PHONE, FAX, EMAIL, PAGER, URL, SMS, OTHER
}

enum class ContactPointUse {
    HOME, WORK, TEMP, OLD, MOBILE
}

enum class AddressUse {
    HOME, WORK, TEMP, OLD, BILLING
}

enum class QuestionnaireItemType {
    GROUP, DISPLAY, BOOLEAN, DECIMAL, INTEGER, DATE, DATE_TIME, TIME,
    STRING, TEXT, URL, CHOICE, OPEN_CHOICE, ATTACHMENT, REFERENCE, QUANTITY
}

enum class DocumentStatus {
    CURRENT, SUPERSEDED, ENTERED_IN_ERROR
}

data class HealthcareIdentifier(
    val system: String? = null,
    val value: String,
    val use: IdentifierUse = IdentifierUse.USUAL
)

data class HealthcareAddress(
    val use: AddressUse = AddressUse.HOME,
    val lines: List<String> = emptyList(),
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null
)

data class HealthcareContactPoint(
    val system: ContactPointSystem,
    val value: String,
    val use: ContactPointUse = ContactPointUse.HOME
)

data class HealthcareCoding(
    val system: String? = null,
    val code: String,
    val display: String? = null
)

data class HealthcareAttachment(
    val contentType: String,
    val data: ByteArray? = null,
    val url: String? = null,
    val title: String? = null,
    val size: Long? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HealthcareAttachment) return false
        return contentType == other.contentType && data.contentEquals(other.data) &&
                url == other.url && title == other.title && size == other.size
    }

    override fun hashCode(): Int {
        var result = contentType.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        result = 31 * result + (url?.hashCode() ?: 0)
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (size?.hashCode() ?: 0)
        return result
    }
}

// ── Core Resource Models ──

data class HealthcarePatient(
    val id: String? = null,
    val identifiers: List<HealthcareIdentifier> = emptyList(),
    val givenNames: List<String> = emptyList(),
    val familyName: String? = null,
    val dateOfBirth: String? = null,
    val gender: AdministrativeGender = AdministrativeGender.UNKNOWN,
    val addresses: List<HealthcareAddress> = emptyList(),
    val telecom: List<HealthcareContactPoint> = emptyList(),
    val email: String? = null
) {
    val fullName: String
        get() = (givenNames + listOfNotNull(familyName)).joinToString(" ")

    val mrn: String?
        get() = identifiers.firstOrNull {
            it.system?.contains("mrn", ignoreCase = true) == true ||
            it.use == IdentifierUse.USUAL
        }?.value
}

data class HealthcareQuestionnaire(
    val id: String? = null,
    val title: String? = null,
    val status: String = "active",
    val items: List<HealthcareQuestionnaireItem> = emptyList()
)

data class HealthcareQuestionnaireItem(
    val linkId: String,
    val text: String? = null,
    val type: QuestionnaireItemType = QuestionnaireItemType.STRING,
    val required: Boolean = false,
    val items: List<HealthcareQuestionnaireItem> = emptyList(),
    val answerOptions: List<HealthcareCoding> = emptyList()
)

data class HealthcareQuestionnaireResponse(
    val id: String? = null,
    val questionnaireId: String? = null,
    val status: String = "completed",
    val authoredDate: String? = null,
    val subjectId: String? = null,
    val items: List<HealthcareResponseItem> = emptyList()
)

data class HealthcareResponseItem(
    val linkId: String,
    val text: String? = null,
    val answers: List<HealthcareResponseAnswer> = emptyList(),
    val items: List<HealthcareResponseItem> = emptyList()
)

data class HealthcareResponseAnswer(
    val valueString: String? = null,
    val valueBoolean: Boolean? = null,
    val valueInteger: Int? = null,
    val valueDecimal: Double? = null,
    val valueDate: String? = null,
    val valueCoding: HealthcareCoding? = null
)

data class HealthcareDocumentReference(
    val id: String? = null,
    val status: DocumentStatus = DocumentStatus.CURRENT,
    val type: HealthcareCoding? = null,
    val subjectId: String? = null,
    val date: String? = null,
    val description: String? = null,
    val content: List<HealthcareAttachment> = emptyList()
)

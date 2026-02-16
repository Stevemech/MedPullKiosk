package com.medpull.kiosk.healthcare.adapter.fhir

import com.medpull.kiosk.healthcare.models.*
import org.hl7.fhir.r4.model.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps between HealthcarePatient and FHIR R4 Patient resource.
 */
@Singleton
class FhirPatientMapper @Inject constructor() {

    fun toFhir(patient: HealthcarePatient): Patient {
        return Patient().apply {
            if (patient.id != null) {
                id = patient.id
            }

            // Name
            if (patient.givenNames.isNotEmpty() || patient.familyName != null) {
                val humanName = HumanName()
                patient.givenNames.forEach { humanName.addGiven(it) }
                if (patient.familyName != null) {
                    humanName.family = patient.familyName
                }
                addName(humanName)
            }

            // Date of birth
            if (patient.dateOfBirth != null) {
                birthDateElement = DateType(patient.dateOfBirth)
            }

            // Gender
            gender = when (patient.gender) {
                AdministrativeGender.MALE -> Enumerations.AdministrativeGender.MALE
                AdministrativeGender.FEMALE -> Enumerations.AdministrativeGender.FEMALE
                AdministrativeGender.OTHER -> Enumerations.AdministrativeGender.OTHER
                AdministrativeGender.UNKNOWN -> Enumerations.AdministrativeGender.UNKNOWN
            }

            // Identifiers
            patient.identifiers.forEach { identifier ->
                addIdentifier(Identifier().apply {
                    system = identifier.system
                    value = identifier.value
                    use = when (identifier.use) {
                        IdentifierUse.USUAL -> Identifier.IdentifierUse.USUAL
                        IdentifierUse.OFFICIAL -> Identifier.IdentifierUse.OFFICIAL
                        IdentifierUse.TEMP -> Identifier.IdentifierUse.TEMP
                        IdentifierUse.SECONDARY -> Identifier.IdentifierUse.SECONDARY
                        IdentifierUse.OLD -> Identifier.IdentifierUse.OLD
                    }
                })
            }

            // Addresses
            patient.addresses.forEach { addr ->
                addAddress(Address().apply {
                    use = when (addr.use) {
                        AddressUse.HOME -> Address.AddressUse.HOME
                        AddressUse.WORK -> Address.AddressUse.WORK
                        AddressUse.TEMP -> Address.AddressUse.TEMP
                        AddressUse.OLD -> Address.AddressUse.OLD
                        AddressUse.BILLING -> Address.AddressUse.BILLING
                    }
                    addr.lines.forEach { addLine(it) }
                    city = addr.city
                    state = addr.state
                    postalCode = addr.postalCode
                    country = addr.country
                })
            }

            // Telecom
            patient.telecom.forEach { contact ->
                addTelecom(ContactPoint().apply {
                    system = when (contact.system) {
                        ContactPointSystem.PHONE -> ContactPoint.ContactPointSystem.PHONE
                        ContactPointSystem.FAX -> ContactPoint.ContactPointSystem.FAX
                        ContactPointSystem.EMAIL -> ContactPoint.ContactPointSystem.EMAIL
                        ContactPointSystem.PAGER -> ContactPoint.ContactPointSystem.PAGER
                        ContactPointSystem.URL -> ContactPoint.ContactPointSystem.URL
                        ContactPointSystem.SMS -> ContactPoint.ContactPointSystem.SMS
                        ContactPointSystem.OTHER -> ContactPoint.ContactPointSystem.OTHER
                    }
                    value = contact.value
                    use = when (contact.use) {
                        ContactPointUse.HOME -> ContactPoint.ContactPointUse.HOME
                        ContactPointUse.WORK -> ContactPoint.ContactPointUse.WORK
                        ContactPointUse.TEMP -> ContactPoint.ContactPointUse.TEMP
                        ContactPointUse.OLD -> ContactPoint.ContactPointUse.OLD
                        ContactPointUse.MOBILE -> ContactPoint.ContactPointUse.MOBILE
                    }
                })
            }

            // Email as telecom
            if (patient.email != null) {
                addTelecom(ContactPoint().apply {
                    system = ContactPoint.ContactPointSystem.EMAIL
                    value = patient.email
                })
            }
        }
    }

    fun fromFhir(fhirPatient: Patient): HealthcarePatient {
        val name = fhirPatient.nameFirstRep

        return HealthcarePatient(
            id = fhirPatient.idElement?.idPart,
            givenNames = name.given?.map { it.value } ?: emptyList(),
            familyName = name.family,
            dateOfBirth = fhirPatient.birthDateElement?.valueAsString,
            gender = when (fhirPatient.gender) {
                Enumerations.AdministrativeGender.MALE -> AdministrativeGender.MALE
                Enumerations.AdministrativeGender.FEMALE -> AdministrativeGender.FEMALE
                Enumerations.AdministrativeGender.OTHER -> AdministrativeGender.OTHER
                else -> AdministrativeGender.UNKNOWN
            },
            identifiers = fhirPatient.identifier?.map { id ->
                HealthcareIdentifier(
                    system = id.system,
                    value = id.value ?: "",
                    use = when (id.use) {
                        Identifier.IdentifierUse.USUAL -> IdentifierUse.USUAL
                        Identifier.IdentifierUse.OFFICIAL -> IdentifierUse.OFFICIAL
                        Identifier.IdentifierUse.TEMP -> IdentifierUse.TEMP
                        Identifier.IdentifierUse.SECONDARY -> IdentifierUse.SECONDARY
                        Identifier.IdentifierUse.OLD -> IdentifierUse.OLD
                        else -> IdentifierUse.USUAL
                    }
                )
            } ?: emptyList(),
            addresses = fhirPatient.address?.map { addr ->
                HealthcareAddress(
                    use = when (addr.use) {
                        Address.AddressUse.HOME -> AddressUse.HOME
                        Address.AddressUse.WORK -> AddressUse.WORK
                        Address.AddressUse.TEMP -> AddressUse.TEMP
                        Address.AddressUse.OLD -> AddressUse.OLD
                        Address.AddressUse.BILLING -> AddressUse.BILLING
                        else -> AddressUse.HOME
                    },
                    lines = addr.line?.map { it.value } ?: emptyList(),
                    city = addr.city,
                    state = addr.state,
                    postalCode = addr.postalCode,
                    country = addr.country
                )
            } ?: emptyList(),
            telecom = fhirPatient.telecom
                ?.filter { it.system != ContactPoint.ContactPointSystem.EMAIL }
                ?.map { cp ->
                    HealthcareContactPoint(
                        system = when (cp.system) {
                            ContactPoint.ContactPointSystem.PHONE -> ContactPointSystem.PHONE
                            ContactPoint.ContactPointSystem.FAX -> ContactPointSystem.FAX
                            ContactPoint.ContactPointSystem.EMAIL -> ContactPointSystem.EMAIL
                            ContactPoint.ContactPointSystem.PAGER -> ContactPointSystem.PAGER
                            ContactPoint.ContactPointSystem.URL -> ContactPointSystem.URL
                            ContactPoint.ContactPointSystem.SMS -> ContactPointSystem.SMS
                            else -> ContactPointSystem.OTHER
                        },
                        value = cp.value ?: "",
                        use = when (cp.use) {
                            ContactPoint.ContactPointUse.HOME -> ContactPointUse.HOME
                            ContactPoint.ContactPointUse.WORK -> ContactPointUse.WORK
                            ContactPoint.ContactPointUse.TEMP -> ContactPointUse.TEMP
                            ContactPoint.ContactPointUse.OLD -> ContactPointUse.OLD
                            ContactPoint.ContactPointUse.MOBILE -> ContactPointUse.MOBILE
                            else -> ContactPointUse.HOME
                        }
                    )
                } ?: emptyList(),
            email = fhirPatient.telecom
                ?.firstOrNull { it.system == ContactPoint.ContactPointSystem.EMAIL }
                ?.value
        )
    }
}

package com.medpull.kiosk.healthcare.adapter

import com.medpull.kiosk.data.models.User
import com.medpull.kiosk.healthcare.models.HealthcarePatient

/**
 * Maps between app User model and neutral HealthcarePatient.
 */
interface PatientMapper {
    fun userToPatient(user: User): HealthcarePatient
    fun patientToUser(patient: HealthcarePatient, existingUser: User? = null): User
}

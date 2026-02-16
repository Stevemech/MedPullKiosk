package com.medpull.kiosk.healthcare.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medpull.kiosk.data.models.FormField
import com.medpull.kiosk.data.repository.AuthRepository
import com.medpull.kiosk.healthcare.models.HealthcarePatient
import com.medpull.kiosk.healthcare.repository.FhirRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FhirImportViewModel @Inject constructor(
    private val fhirRepository: FhirRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    companion object {
        private const val TAG = "FhirImportVM"
    }

    private val _state = MutableStateFlow(FhirImportState())
    val state: StateFlow<FhirImportState> = _state.asStateFlow()

    fun searchPatients(query: String) {
        if (query.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(isSearching = true, error = null) }

            val userId = authRepository.getCurrentUserId() ?: "unknown"
            val result = fhirRepository.searchPatients(
                userId = userId,
                name = query
            )

            _state.update {
                it.copy(
                    isSearching = false,
                    patients = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun importPatient(
        patient: HealthcarePatient,
        formId: String,
        fields: List<FormField>
    ) {
        val patientId = patient.id ?: return

        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, error = null) }

            val userId = authRepository.getCurrentUserId() ?: "unknown"
            val result = fhirRepository.importPatientToForm(
                userId = userId,
                patientId = patientId,
                formId = formId,
                fields = fields
            )

            _state.update {
                it.copy(
                    isImporting = false,
                    importedValues = result.getOrDefault(emptyMap()),
                    importSuccess = result.isSuccess,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun clearState() {
        _state.update { FhirImportState() }
    }
}

data class FhirImportState(
    val isSearching: Boolean = false,
    val isImporting: Boolean = false,
    val patients: List<HealthcarePatient> = emptyList(),
    val importedValues: Map<String, String> = emptyMap(),
    val importSuccess: Boolean = false,
    val error: String? = null
)

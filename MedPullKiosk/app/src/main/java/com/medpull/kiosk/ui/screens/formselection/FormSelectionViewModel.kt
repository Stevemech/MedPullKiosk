package com.medpull.kiosk.ui.screens.formselection

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medpull.kiosk.data.models.Form
import com.medpull.kiosk.data.models.FormStatus
import com.medpull.kiosk.data.repository.AuthRepository
import com.medpull.kiosk.data.repository.FormProcessResult
import com.medpull.kiosk.data.repository.FormRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for form selection and upload screen
 */
@HiltViewModel
class FormSelectionViewModel @Inject constructor(
    private val formRepository: FormRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    companion object {
        private const val TAG = "FormSelectionViewModel"
    }

    private val _state = MutableStateFlow(FormSelectionState())
    val state: StateFlow<FormSelectionState> = _state.asStateFlow()

    init {
        loadForms()
    }

    /**
     * Load user's forms
     */
    private fun loadForms() {
        viewModelScope.launch {
            try {
                val userId = authRepository.getCurrentUserId()
                if (userId != null) {
                    formRepository.getFormsByUserIdFlow(userId)
                        .catch { e ->
                            Log.e(TAG, "Error loading forms", e)
                            _state.update { it.copy(error = "Failed to load forms: ${e.message}") }
                        }
                        .collect { forms ->
                            _state.update { it.copy(forms = forms, isLoading = false) }
                        }
                } else {
                    _state.update { it.copy(error = "No user logged in", isLoading = false) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading forms", e)
                _state.update { it.copy(error = "Failed to load forms: ${e.message}", isLoading = false) }
            }
        }
    }

    /**
     * Upload and process a form file
     */
    fun uploadForm(file: File) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isUploading = true, uploadProgress = 0f, error = null) }

                val userId = authRepository.getCurrentUserId()
                if (userId == null) {
                    _state.update { it.copy(isUploading = false, error = "No user logged in") }
                    return@launch
                }

                // Create form entity
                val formId = UUID.randomUUID().toString()
                val form = Form(
                    id = formId,
                    userId = userId,
                    fileName = file.name,
                    originalFileUri = file.absolutePath,
                    status = FormStatus.UPLOADING,
                    fields = emptyList(),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                // Save form to database
                formRepository.saveForm(form)

                Log.d(TAG, "Starting form upload: ${file.name}")

                // Simulate upload progress (in real implementation, track S3 upload progress)
                for (progress in 0..100 step 10) {
                    _state.update { it.copy(uploadProgress = progress / 100f) }
                    kotlinx.coroutines.delay(100)
                }

                // Upload and process form
                val result = formRepository.uploadAndProcessForm(file, userId, formId)

                when (result) {
                    is FormProcessResult.Success -> {
                        Log.d(TAG, "Form processed successfully: ${result.fields.size} fields")
                        _state.update {
                            it.copy(
                                isUploading = false,
                                uploadProgress = 1f,
                                successMessage = "Form uploaded and processed successfully"
                            )
                        }
                        // Clear success message after 3 seconds
                        kotlinx.coroutines.delay(3000)
                        _state.update { it.copy(successMessage = null) }
                    }
                    is FormProcessResult.Processing -> {
                        _state.update {
                            it.copy(
                                isUploading = false,
                                successMessage = "Form is being processed..."
                            )
                        }
                    }
                    is FormProcessResult.Error -> {
                        Log.e(TAG, "Form processing error: ${result.message}")
                        _state.update {
                            it.copy(
                                isUploading = false,
                                error = "Failed to process form: ${result.message}"
                            )
                        }
                    }
                    is FormProcessResult.QueuedForSync -> {
                        _state.update {
                            it.copy(
                                isUploading = false,
                                successMessage = "Form queued for upload when online"
                            )
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error uploading form", e)
                _state.update {
                    it.copy(
                        isUploading = false,
                        error = "Failed to upload form: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Delete a form
     */
    fun deleteForm(formId: String) {
        viewModelScope.launch {
            try {
                formRepository.deleteForm(formId)
                Log.d(TAG, "Form deleted: $formId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting form", e)
                _state.update { it.copy(error = "Failed to delete form: ${e.message}") }
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Clear success message
     */
    fun clearSuccessMessage() {
        _state.update { it.copy(successMessage = null) }
    }

    /**
     * Refresh forms list
     */
    fun refreshForms() {
        _state.update { it.copy(isLoading = true) }
        loadForms()
    }
}

/**
 * Form selection UI state
 */
data class FormSelectionState(
    val forms: List<Form> = emptyList(),
    val isLoading: Boolean = true,
    val isUploading: Boolean = false,
    val uploadProgress: Float = 0f,
    val error: String? = null,
    val successMessage: String? = null
)

package com.medpull.kiosk.ui.screens.export

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medpull.kiosk.data.models.FormStatus
import com.medpull.kiosk.data.repository.FormRepository
import com.medpull.kiosk.data.repository.StorageRepository
import com.medpull.kiosk.utils.PdfUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ViewModel for export screen
 */
@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val formRepository: FormRepository,
    private val storageRepository: StorageRepository,
    private val pdfUtils: PdfUtils,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "ExportViewModel"
    }

    private val formId: String = savedStateHandle.get<String>("formId") ?: ""

    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state.asStateFlow()

    init {
        loadForm()
    }

    /**
     * Load form details
     */
    private fun loadForm() {
        viewModelScope.launch {
            try {
                formRepository.getFormByIdFlow(formId)
                    .collect { form ->
                        if (form != null) {
                            val completionPercentage = formRepository.getFormCompletionPercentage(formId)
                            _state.update {
                                it.copy(
                                    form = form,
                                    completionPercentage = completionPercentage,
                                    isLoading = false,
                                    canExport = completionPercentage >= 100f
                                )
                            }
                        } else {
                            _state.update {
                                it.copy(
                                    error = "Form not found",
                                    isLoading = false
                                )
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading form", e)
                _state.update {
                    it.copy(
                        error = "Failed to load form: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * Export to S3
     */
    fun exportToS3() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isExporting = true, error = null) }

                val form = _state.value.form
                if (form == null) {
                    _state.update { it.copy(error = "No form to export", isExporting = false) }
                    return@launch
                }

                // Generate filled PDF
                val filledPdfResult = pdfUtils.generateFilledPdf(
                    originalPdfPath = form.originalFileUri,
                    fields = form.fields,
                    outputDir = context.cacheDir
                )

                if (filledPdfResult == null) {
                    _state.update {
                        it.copy(
                            error = "Failed to generate PDF",
                            isExporting = false
                        )
                    }
                    return@launch
                }

                val filledPdf = filledPdfResult // Non-null at this point

                // Upload to S3
                val result = storageRepository.uploadFilledForm(filledPdf, form.userId, formId)

                if (result.isSuccess) {
                    // Update form status
                    formRepository.updateFormStatus(formId, FormStatus.EXPORTED)

                    _state.update {
                        it.copy(
                            isExporting = false,
                            exportSuccess = true,
                            exportMessage = "Form exported to cloud storage successfully"
                        )
                    }

                    Log.d(TAG, "Form exported to S3 successfully")
                } else {
                    _state.update {
                        it.copy(
                            error = "Failed to upload to cloud: ${result.exceptionOrNull()?.message}",
                            isExporting = false
                        )
                    }
                }

                // Cleanup temp file
                filledPdf.delete()

            } catch (e: Exception) {
                Log.e(TAG, "Error exporting to S3", e)
                _state.update {
                    it.copy(
                        error = "Export failed: ${e.message}",
                        isExporting = false
                    )
                }
            }
        }
    }

    /**
     * Export to local storage
     */
    fun exportToLocal() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isExporting = true, error = null) }

                val form = _state.value.form
                if (form == null) {
                    _state.update { it.copy(error = "No form to export", isExporting = false) }
                    return@launch
                }

                // Generate filled PDF
                val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
                val filledPdfResult = pdfUtils.generateFilledPdf(
                    originalPdfPath = form.originalFileUri,
                    fields = form.fields,
                    outputDir = outputDir
                )

                if (filledPdfResult == null) {
                    _state.update {
                        it.copy(
                            error = "Failed to generate PDF",
                            isExporting = false
                        )
                    }
                    return@launch
                }

                val filledPdf = filledPdfResult // Non-null at this point

                // Update form status
                formRepository.updateFormStatus(formId, FormStatus.EXPORTED)

                _state.update {
                    it.copy(
                        isExporting = false,
                        exportSuccess = true,
                        exportMessage = "Form saved to: ${filledPdf.absolutePath}",
                        localFilePath = filledPdf.absolutePath
                    )
                }

                Log.d(TAG, "Form exported locally: ${filledPdf.absolutePath}")

            } catch (e: Exception) {
                Log.e(TAG, "Error exporting locally", e)
                _state.update {
                    it.copy(
                        error = "Export failed: ${e.message}",
                        isExporting = false
                    )
                }
            }
        }
    }

    /**
     * Preview export
     */
    fun previewExport() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(isGeneratingPreview = true, error = null) }

                val form = _state.value.form
                if (form == null) {
                    _state.update {
                        it.copy(
                            error = "No form to preview",
                            isGeneratingPreview = false
                        )
                    }
                    return@launch
                }

                // Generate preview PDF
                val previewPdf = pdfUtils.generateFilledPdf(
                    originalPdfPath = form.originalFileUri,
                    fields = form.fields,
                    outputDir = context.cacheDir
                )

                if (previewPdf != null) {
                    _state.update {
                        it.copy(
                            previewFilePath = previewPdf.absolutePath,
                            isGeneratingPreview = false
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            error = "Failed to generate preview",
                            isGeneratingPreview = false
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error generating preview", e)
                _state.update {
                    it.copy(
                        error = "Preview failed: ${e.message}",
                        isGeneratingPreview = false
                    )
                }
            }
        }
    }

    /**
     * Clear success state
     */
    fun clearSuccess() {
        _state.update {
            it.copy(
                exportSuccess = false,
                exportMessage = null
            )
        }
    }

    /**
     * Clear error
     */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Set navigation flag
     */
    fun navigateBack() {
        _state.update { it.copy(shouldNavigateBack = true) }
    }

    /**
     * Reset navigation flag
     */
    fun resetNavigation() {
        _state.update { it.copy(shouldNavigateBack = false) }
    }
}

/**
 * Export UI state
 */
data class ExportState(
    val form: com.medpull.kiosk.data.models.Form? = null,
    val completionPercentage: Float = 0f,
    val canExport: Boolean = false,
    val isLoading: Boolean = true,
    val isExporting: Boolean = false,
    val isGeneratingPreview: Boolean = false,
    val exportSuccess: Boolean = false,
    val exportMessage: String? = null,
    val previewFilePath: String? = null,
    val localFilePath: String? = null,
    val error: String? = null,
    val shouldNavigateBack: Boolean = false
)

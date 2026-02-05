package com.medpull.kiosk.ui.screens.language

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.medpull.kiosk.utils.LanguageOption
import com.medpull.kiosk.utils.LocaleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Language Selection screen
 */
@HiltViewModel
class LanguageSelectionViewModel @Inject constructor(
    private val localeManager: LocaleManager,
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LanguageSelectionUiState())
    val uiState: StateFlow<LanguageSelectionUiState> = _uiState.asStateFlow()

    init {
        loadLanguages()
        loadCurrentLanguage()
    }

    /**
     * Load available languages
     */
    private fun loadLanguages() {
        val languages = localeManager.getSupportedLanguages()
        _uiState.value = _uiState.value.copy(
            availableLanguages = languages
        )
    }

    /**
     * Load current language selection
     */
    private fun loadCurrentLanguage() {
        viewModelScope.launch {
            val currentCode = localeManager.getCurrentLanguage(getApplication())
            _uiState.value = _uiState.value.copy(
                selectedLanguage = currentCode
            )
        }
    }

    /**
     * Select language
     */
    fun selectLanguage(languageCode: String) {
        _uiState.value = _uiState.value.copy(
            selectedLanguage = languageCode
        )
    }

    /**
     * Confirm language selection and apply
     */
    fun confirmLanguage(onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                val selectedCode = _uiState.value.selectedLanguage
                localeManager.setLanguage(getApplication(), selectedCode)

                // Language will be applied on next screen load
                onComplete()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message
                )
            }
        }
    }
}

/**
 * UI state for language selection
 */
data class LanguageSelectionUiState(
    val availableLanguages: List<LanguageOption> = emptyList(),
    val selectedLanguage: String = "en",
    val error: String? = null
)

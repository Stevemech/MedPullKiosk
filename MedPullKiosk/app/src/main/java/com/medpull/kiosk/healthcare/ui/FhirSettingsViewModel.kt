package com.medpull.kiosk.healthcare.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medpull.kiosk.data.repository.AuthRepository
import com.medpull.kiosk.healthcare.client.FhirAuthType
import com.medpull.kiosk.healthcare.client.FhirServerConfig
import com.medpull.kiosk.healthcare.repository.FhirRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FhirSettingsViewModel @Inject constructor(
    private val fhirRepository: FhirRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    companion object {
        private const val TAG = "FhirSettingsVM"
    }

    private val _state = MutableStateFlow(FhirSettingsState())
    val state: StateFlow<FhirSettingsState> = _state.asStateFlow()

    init {
        loadConfig()
    }

    private fun loadConfig() {
        val config = fhirRepository.loadServerConfig()
        _state.update {
            it.copy(
                serverUrl = config.serverUrl,
                authType = config.authType,
                smartClientId = config.smartClientId,
                isConfigured = config.isConfigured
            )
        }
    }

    fun updateServerUrl(url: String) {
        _state.update { it.copy(serverUrl = url, connectionStatus = null) }
    }

    fun updateAuthType(type: FhirAuthType) {
        _state.update { it.copy(authType = type, connectionStatus = null) }
    }

    fun updateSmartClientId(clientId: String) {
        _state.update { it.copy(smartClientId = clientId) }
    }

    fun saveConfig() {
        val config = FhirServerConfig(
            serverUrl = _state.value.serverUrl.trim(),
            authType = _state.value.authType,
            smartClientId = _state.value.smartClientId.trim()
        )
        fhirRepository.saveServerConfig(config)
        _state.update { it.copy(isConfigured = config.isConfigured, saveSuccess = true) }
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(isTesting = true, connectionStatus = null) }

            // Save config first so interceptors have the URL
            saveConfig()

            val userId = authRepository.getCurrentUserId() ?: "unknown"
            val result = fhirRepository.verifyConnection(userId)

            _state.update {
                it.copy(
                    isTesting = false,
                    connectionStatus = if (result.isSuccess) {
                        ConnectionStatus.SUCCESS
                    } else {
                        ConnectionStatus.FAILED
                    },
                    connectionError = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun clearSaveSuccess() {
        _state.update { it.copy(saveSuccess = false) }
    }
}

data class FhirSettingsState(
    val serverUrl: String = "",
    val authType: FhirAuthType = FhirAuthType.NONE,
    val smartClientId: String = "",
    val isConfigured: Boolean = false,
    val isTesting: Boolean = false,
    val connectionStatus: ConnectionStatus? = null,
    val connectionError: String? = null,
    val saveSuccess: Boolean = false
)

enum class ConnectionStatus {
    SUCCESS, FAILED
}

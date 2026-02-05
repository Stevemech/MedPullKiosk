package com.medpull.kiosk.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medpull.kiosk.data.repository.AuthRepository
import com.medpull.kiosk.data.repository.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Verification screen
 */
@HiltViewModel
class VerificationViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VerificationUiState())
    val uiState: StateFlow<VerificationUiState> = _uiState.asStateFlow()

    /**
     * Set email
     */
    fun setEmail(email: String) {
        _uiState.value = _uiState.value.copy(email = email)
    }

    /**
     * Update code field
     */
    fun onCodeChanged(code: String) {
        // Only allow digits and limit to 6 characters
        val filtered = code.filter { it.isDigit() }.take(6)
        _uiState.value = _uiState.value.copy(
            code = filtered,
            error = null
        )
    }

    /**
     * Verify email with code
     */
    fun verify(onSuccess: () -> Unit, onNavigateToLogin: () -> Unit) {
        val email = _uiState.value.email
        val code = _uiState.value.code

        // Validate input
        if (email.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                error = "Email is missing. Please go back and register again."
            )
            return
        }

        if (code.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                error = "Please enter the verification code"
            )
            return
        }

        if (code.length != 6) {
            _uiState.value = _uiState.value.copy(
                error = "Verification code must be 6 digits"
            )
            return
        }

        // Start verification
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null
        )

        viewModelScope.launch {
            try {
                when (val result = authRepository.confirmSignUp(email, code)) {
                    is AuthResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = null,
                            successMessage = "Email verified successfully! Please login."
                        )
                        // Navigate to login after a short delay
                        kotlinx.coroutines.delay(1500)
                        onNavigateToLogin()
                    }
                    is AuthResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                    is AuthResult.RequiresConfirmation -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Unexpected state: ${result.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Verification failed"
                )
            }
        }
    }

    /**
     * Resend verification code
     */
    fun resendCode() {
        val email = _uiState.value.email

        if (email.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                error = "Email is missing. Please go back and register again."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isResending = true,
            error = null,
            successMessage = null
        )

        viewModelScope.launch {
            try {
                when (val result = authRepository.resendConfirmationCode(email)) {
                    is AuthResult.Success, is AuthResult.RequiresConfirmation -> {
                        _uiState.value = _uiState.value.copy(
                            isResending = false,
                            successMessage = "A new verification code has been sent to your email."
                        )
                    }
                    is AuthResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isResending = false,
                            error = result.message
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isResending = false,
                    error = e.message ?: "Failed to resend code"
                )
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

/**
 * UI state for verification screen
 */
data class VerificationUiState(
    val email: String = "",
    val code: String = "",
    val isLoading: Boolean = false,
    val isResending: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null
)

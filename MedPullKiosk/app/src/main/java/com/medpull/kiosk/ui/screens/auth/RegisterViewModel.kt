package com.medpull.kiosk.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.medpull.kiosk.data.repository.AuthRepository
import com.medpull.kiosk.data.repository.AuthResult
import com.medpull.kiosk.utils.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Register screen
 */
@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    /**
     * Update email field
     */
    fun onEmailChanged(email: String) {
        _uiState.value = _uiState.value.copy(
            email = email,
            error = null
        )
    }

    /**
     * Update password field
     */
    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(
            password = password,
            error = null
        )
    }

    /**
     * Update confirm password field
     */
    fun onConfirmPasswordChanged(confirmPassword: String) {
        _uiState.value = _uiState.value.copy(
            confirmPassword = confirmPassword,
            error = null
        )
    }

    /**
     * Update first name field
     */
    fun onFirstNameChanged(firstName: String) {
        _uiState.value = _uiState.value.copy(
            firstName = firstName,
            error = null
        )
    }

    /**
     * Update last name field
     */
    fun onLastNameChanged(lastName: String) {
        _uiState.value = _uiState.value.copy(
            lastName = lastName,
            error = null
        )
    }

    /**
     * Perform registration
     */
    fun register(onSuccess: () -> Unit) {
        val email = _uiState.value.email.trim()
        val password = _uiState.value.password
        val confirmPassword = _uiState.value.confirmPassword
        val firstName = _uiState.value.firstName.trim()
        val lastName = _uiState.value.lastName.trim()

        // Validate input
        if (email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                error = "Please fill in all required fields"
            )
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.value = _uiState.value.copy(
                error = "Please enter a valid email address"
            )
            return
        }

        if (password.length < 12) {
            _uiState.value = _uiState.value.copy(
                error = "Password must be at least 12 characters"
            )
            return
        }

        // Validate password complexity (AWS Cognito requirements)
        val hasUpperCase = password.any { it.isUpperCase() }
        val hasLowerCase = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecialChar = password.any { !it.isLetterOrDigit() }

        if (!hasUpperCase || !hasLowerCase || !hasDigit || !hasSpecialChar) {
            _uiState.value = _uiState.value.copy(
                error = "Password must contain uppercase, lowercase, number, and special character"
            )
            return
        }

        if (password != confirmPassword) {
            _uiState.value = _uiState.value.copy(
                error = "Passwords do not match"
            )
            return
        }

        // Start registration
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null
        )

        viewModelScope.launch {
            try {
                when (val result = authRepository.signUp(
                    email = email,
                    password = password,
                    firstName = firstName.takeIf { it.isNotEmpty() },
                    lastName = lastName.takeIf { it.isNotEmpty() }
                )) {
                    is AuthResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = null,
                            registrationSuccess = true
                        )
                        // Start session
                        sessionManager.startSession()
                        onSuccess()
                    }
                    is AuthResult.RequiresConfirmation -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = null,
                            requiresConfirmation = true,
                            confirmationMessage = result.message
                        )
                    }
                    is AuthResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Registration failed"
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
 * UI state for register screen
 */
data class RegisterUiState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val registrationSuccess: Boolean = false,
    val requiresConfirmation: Boolean = false,
    val confirmationMessage: String? = null
)

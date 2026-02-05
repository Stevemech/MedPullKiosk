package com.medpull.kiosk.data.repository

import com.medpull.kiosk.data.local.dao.UserDao
import com.medpull.kiosk.data.local.entities.UserEntity
import com.medpull.kiosk.data.models.User
import com.medpull.kiosk.data.remote.aws.CognitoAuthServiceV2
import com.medpull.kiosk.data.remote.aws.SignInResult
import com.medpull.kiosk.data.remote.aws.CognitoSignUpResult
import com.medpull.kiosk.security.SecureStorageManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for authentication operations
 * Integrates AWS Cognito with local caching
 */
@Singleton
class AuthRepository @Inject constructor(
    private val userDao: UserDao,
    private val secureStorage: SecureStorageManager,
    private val cognitoAuthService: CognitoAuthServiceV2
) {

    /**
     * Get current user from cache
     */
    suspend fun getCurrentUser(userId: String): User? {
        return userDao.getUserById(userId)?.toDomain()
    }

    /**
     * Get current user as Flow
     */
    fun getCurrentUserFlow(userId: String): Flow<User?> {
        return userDao.getUserByIdFlow(userId).map { it?.toDomain() }
    }

    /**
     * Save user to cache
     */
    suspend fun saveUser(user: User) {
        userDao.insertUser(UserEntity.fromDomain(user))
    }

    /**
     * Update last login time
     */
    suspend fun updateLastLogin(userId: String) {
        userDao.updateLastLogin(userId, System.currentTimeMillis())
    }

    /**
     * Clear user data (logout)
     */
    suspend fun clearUserData(userId: String) {
        userDao.deleteUserById(userId)
        secureStorage.clearAll()
    }

    /**
     * Check if user is authenticated
     */
    fun isAuthenticated(): Boolean {
        return secureStorage.hasAuthToken()
    }

    /**
     * Get auth token
     */
    fun getAuthToken(): String? {
        return secureStorage.getAuthToken()
    }

    /**
     * Save auth tokens
     */
    fun saveAuthTokens(authToken: String, refreshToken: String) {
        secureStorage.saveAuthToken(authToken)
        secureStorage.saveRefreshToken(refreshToken)
    }

    /**
     * Sign up new user with Cognito
     */
    suspend fun signUp(
        email: String,
        password: String,
        firstName: String? = null,
        lastName: String? = null
    ): AuthResult {
        return try {
            val result = cognitoAuthService.signUp(email, password, firstName, lastName)
            when (result) {
                is CognitoSignUpResult.Success -> {
                    if (result.isConfirmed) {
                        AuthResult.Success("Account created successfully")
                    } else {
                        AuthResult.RequiresConfirmation(
                            "Please check ${result.destination} for confirmation code"
                        )
                    }
                }
                is CognitoSignUpResult.Error -> AuthResult.Error(result.message)
            }
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Sign up failed")
        }
    }

    /**
     * Sign in user with Cognito
     */
    suspend fun signIn(email: String, password: String): AuthResult {
        return try {
            val result = cognitoAuthService.signIn(email, password)
            when (result) {
                is SignInResult.Success -> {
                    // Save user to local database
                    saveUser(result.user)

                    // Save tokens to secure storage
                    saveAuthTokens(result.accessToken, result.refreshToken)

                    // Save user ID and email
                    secureStorage.saveSecureString("user_id", result.user.id)
                    secureStorage.saveSecureString("user_email", email)

                    // Update last login
                    updateLastLogin(result.user.id)

                    AuthResult.Success("Signed in successfully")
                }
                is SignInResult.Error -> AuthResult.Error(result.message)
            }
        } catch (e: Exception) {
            // Check if user is not confirmed
            val errorMessage = e.message ?: ""
            if (errorMessage.contains("User is not confirmed", ignoreCase = true) ||
                errorMessage.contains("UserNotConfirmed", ignoreCase = true)) {
                AuthResult.RequiresConfirmation("Please verify your email address first")
            } else {
                AuthResult.Error(e.message ?: "Sign in failed")
            }
        }
    }

    /**
     * Sign out user
     */
    suspend fun signOut() {
        val email = secureStorage.getSecureString("user_email")
        val userId = secureStorage.getSecureString("user_id")

        email?.let { cognitoAuthService.signOut(it) }
        userId?.let { clearUserData(it) }
    }

    /**
     * Confirm sign up with verification code
     */
    suspend fun confirmSignUp(email: String, code: String): AuthResult {
        return try {
            val success = cognitoAuthService.confirmSignUp(email, code)
            if (success) {
                AuthResult.Success("Email confirmed successfully")
            } else {
                AuthResult.Error("Confirmation failed")
            }
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Confirmation failed")
        }
    }

    /**
     * Resend confirmation code
     */
    suspend fun resendConfirmationCode(email: String): AuthResult {
        return try {
            val destination = cognitoAuthService.resendConfirmationCode(email)
            AuthResult.Success("Code sent to $destination")
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to resend code")
        }
    }

    /**
     * Initiate forgot password
     */
    suspend fun forgotPassword(email: String): AuthResult {
        return try {
            val destination = cognitoAuthService.forgotPassword(email)
            AuthResult.Success("Reset code sent to $destination")
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to initiate password reset")
        }
    }

    /**
     * Confirm forgot password with code
     */
    suspend fun confirmForgotPassword(
        email: String,
        code: String,
        newPassword: String
    ): AuthResult {
        return try {
            val success = cognitoAuthService.confirmForgotPassword(email, code, newPassword)
            if (success) {
                AuthResult.Success("Password reset successfully")
            } else {
                AuthResult.Error("Password reset failed")
            }
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Password reset failed")
        }
    }

    /**
     * Get current user ID
     */
    fun getCurrentUserId(): String? {
        return secureStorage.getSecureString("user_id")
    }

    /**
     * Get current user email
     */
    fun getCurrentUserEmail(): String? {
        return secureStorage.getSecureString("user_email")
    }
}

/**
 * Auth result sealed class
 */
sealed class AuthResult {
    data class Success(val message: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
    data class RequiresConfirmation(val message: String) : AuthResult()
}

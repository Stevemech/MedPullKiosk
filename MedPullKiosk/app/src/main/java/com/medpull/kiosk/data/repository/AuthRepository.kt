package com.medpull.kiosk.data.repository

import android.util.Log
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.medpull.kiosk.data.local.dao.UserDao
import com.medpull.kiosk.data.local.entities.UserEntity
import com.medpull.kiosk.data.models.User
import com.medpull.kiosk.data.remote.aws.CognitoAuthServiceV2
import com.medpull.kiosk.data.remote.aws.SignInResult
import com.medpull.kiosk.data.remote.aws.CognitoSignUpResult
import com.medpull.kiosk.security.SecureStorageManager
import com.medpull.kiosk.utils.Constants
import com.medpull.kiosk.utils.JwtUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
    private val cognitoAuthService: CognitoAuthServiceV2,
    private val credentialsProvider: CognitoCachingCredentialsProvider
) {

    companion object {
        private const val TAG = "AuthRepository"
    }

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
     * Get ID token
     */
    fun getIdToken(): String? {
        return secureStorage.getIdToken()
    }

    /**
     * Save auth tokens including ID token
     */
    fun saveAuthTokens(authToken: String, idToken: String, refreshToken: String) {
        secureStorage.saveAuthToken(authToken)
        secureStorage.saveIdToken(idToken)
        secureStorage.saveRefreshToken(refreshToken)
    }

    /**
     * Link Cognito User Pool authentication to Identity Pool credentials.
     * This allows the user to assume the authenticated IAM role.
     *
     * CognitoCachingCredentialsProvider persists logins to SharedPreferences.
     * clear() wipes identity ID + STS credentials but NOT the cached logins map.
     * We must explicitly overwrite the logins (via setLogins) before and after
     * clearing so no stale token survives in SharedPreferences or in memory.
     */
    private suspend fun linkUserPoolToIdentityPool(idToken: String) {
        val loginKey = "cognito-idp.${Constants.AWS.REGION}.amazonaws.com/${Constants.AWS.USER_POOL_ID}"
        Log.d(TAG, "Linking Identity Pool with login key: $loginKey")

        // SDK's setLogins() already:
        //   1. Updates the in-memory loginsMap
        //   2. Sets needIdentityRefresh = true (forces new GetId on next refresh)
        //   3. Calls clearCredentials() (wipes cached STS)
        //
        // We intentionally do NOT call clear() — it wipes awsKeyValueStore
        // which can race with the setLogins save and cause stale reads.
        //
        // All operations run on the same IO thread to avoid the non-volatile
        // loginsMap visibility bug in the AWS SDK.
        withContext(Dispatchers.IO) {
            credentialsProvider.logins = java.util.HashMap<String, String>().apply {
                put(loginKey, idToken)
            }

            credentialsProvider.refresh()
            Log.d(TAG, "Identity Pool credentials refreshed successfully")
        }
    }

    /**
     * Clear credentials provider logins (for logout)
     */
    private fun clearIdentityPoolCredentials() {
        credentialsProvider.logins = emptyMap()
        credentialsProvider.clear()
    }

    /**
     * Check if ID token is expired or expiring soon
     * @return true if token needs refresh
     */
    fun needsTokenRefresh(): Boolean {
        val idToken = secureStorage.getIdToken() ?: return true
        return JwtUtils.isTokenExpiringSoon(idToken, thresholdSeconds = 300) // 5 minutes
    }

    /**
     * Refresh tokens if needed and ensure the credentials provider is fully
     * linked with valid STS credentials before any AWS operation.
     *
     * Returns null on success, or an error message string on failure.
     */
    suspend fun refreshTokensIfNeeded(): String? {
        return try {
            var idToken = secureStorage.getIdToken()

            // If the stored token is expired or expiring soon, get a new one
            if (idToken == null || JwtUtils.isTokenExpiringSoon(idToken, thresholdSeconds = 300)) {
                Log.d(TAG, "ID token missing or expiring, attempting refresh")
                idToken = refreshAndGetNewIdToken()
                if (idToken == null) {
                    return "Session expired. Please sign out and sign back in."
                }
            }

            // Full re-link: clears caches, sets fresh logins, and eagerly
            // refreshes STS credentials — all on the same IO thread to
            // avoid the non-volatile loginsMap visibility bug in the SDK.
            // Throws if the credential exchange fails.
            linkUserPoolToIdentityPool(idToken)

            Log.d(TAG, "Credentials provider linked and verified")
            null // success
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing tokens / linking credentials", e)
            "Credential error: ${e.message}"
        }
    }

    /**
     * Attempt to refresh the Cognito session and return a fresh ID token.
     * Returns null if refresh fails (e.g., refresh token expired).
     */
    private suspend fun refreshAndGetNewIdToken(): String? {
        val email = secureStorage.getSecureString("user_email")
        if (email == null) {
            Log.e(TAG, "Cannot refresh tokens: no email found")
            return null
        }

        Log.d(TAG, "Refreshing session for: $email")
        val session = cognitoAuthService.refreshSession(email)

        if (session == null) {
            Log.e(TAG, "refreshSession returned null")
            return null
        }

        val newIdToken = session.idToken.jwtToken

        // Guard against the SDK returning a cached expired session
        if (JwtUtils.isTokenExpired(newIdToken, bufferSeconds = 0)) {
            Log.e(TAG, "Refreshed session returned expired ID token (exp: ${JwtUtils.getExpirationTime(newIdToken)})")
            return null
        }

        // Persist new tokens
        saveAuthTokens(
            session.accessToken.jwtToken,
            newIdToken,
            session.refreshToken.token
        )
        Log.d(TAG, "New tokens saved successfully")
        return newIdToken
    }

    /**
     * Restore session from stored tokens
     * Call this on app startup to restore credentials
     */
    suspend fun restoreSession(): Boolean {
        return try {
            val idToken = secureStorage.getIdToken()
            val email = secureStorage.getSecureString("user_email")

            if (idToken == null || email == null) {
                Log.d(TAG, "No stored session to restore")
                return false
            }

            // Check if token is expired
            if (JwtUtils.isTokenExpired(idToken, bufferSeconds = 0)) {
                Log.d(TAG, "Stored token expired, attempting refresh")
                return refreshTokensIfNeeded() == null
            }

            // Token still valid, restore credentials
            linkUserPoolToIdentityPool(idToken)
            Log.d(TAG, "Session restored successfully for: $email")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring session", e)
            false
        }
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

                    // Save tokens to secure storage (including ID token)
                    saveAuthTokens(result.accessToken, result.idToken, result.refreshToken)

                    // Save user ID and email
                    secureStorage.saveSecureString("user_id", result.user.id)
                    secureStorage.saveSecureString("user_email", email)

                    // Link User Pool authentication to Identity Pool credentials.
                    // All logins + refresh run on the same IO thread to avoid
                    // the non-volatile loginsMap visibility bug in the AWS SDK.
                    linkUserPoolToIdentityPool(result.idToken)

                    // Update last login
                    updateLastLogin(result.user.id)

                    AuthResult.Success("Signed in successfully")
                }
                is SignInResult.Error -> AuthResult.Error(result.message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign in error", e)
            // Check if user is not confirmed
            val errorMessage = e.message ?: ""
            if (errorMessage.contains("User is not confirmed", ignoreCase = true) ||
                errorMessage.contains("UserNotConfirmed", ignoreCase = true)) {
                AuthResult.RequiresConfirmation("Please verify your email address first")
            } else {
                AuthResult.Error("Sign in failed: ${e.message}")
            }
        }
    }

    /**
     * Sign out user
     */
    suspend fun signOut() {
        val email = secureStorage.getSecureString("user_email")
        val userId = secureStorage.getSecureString("user_id")

        // Clear Identity Pool credentials
        clearIdentityPoolCredentials()

        // Sign out from Cognito User Pool
        email?.let { cognitoAuthService.signOut(it) }

        // Clear local data
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

package com.medpull.kiosk.data.remote.aws

import android.util.Log
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoDevice
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUser
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserCodeDeliveryDetails
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserPool
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserSession
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationDetails
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.ChallengeContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.MultiFactorAuthenticationContinuation
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.AuthenticationHandler
import com.medpull.kiosk.data.models.User
import com.medpull.kiosk.utils.Constants
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AWS Cognito authentication service - Version 2
 * Uses REST API for problematic SDK methods, SDK for working methods
 */
@Singleton
class CognitoAuthServiceV2 @Inject constructor(
    private val userPool: CognitoUserPool,
    private val cognitoApi: CognitoApiService
) {

    companion object {
        private const val TAG = "CognitoAuthServiceV2"
    }

    /**
     * Sign up a new user using REST API
     */
    suspend fun signUp(
        email: String,
        password: String,
        firstName: String? = null,
        lastName: String? = null
    ): CognitoSignUpResult {
        return try {
            val userAttributes = mutableListOf(
                AttributeType("email", email)
            )
            firstName?.let { userAttributes.add(AttributeType("given_name", it)) }
            lastName?.let { userAttributes.add(AttributeType("family_name", it)) }

            val request = SignUpRequest(
                clientId = Constants.AWS.CLIENT_ID,
                username = email,
                password = password,
                userAttributes = userAttributes
            )

            val response = cognitoApi.signUp(request = request)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.d(TAG, "Sign up successful for user: ${body.userSub}")
                CognitoSignUpResult.Success(
                    userId = body.userSub,
                    isConfirmed = body.userConfirmed,
                    destination = body.codeDeliveryDetails?.destination
                )
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Sign up failed: $errorBody")
                CognitoSignUpResult.Error(errorBody ?: "Sign up failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign up failed", e)
            CognitoSignUpResult.Error(e.message ?: "Sign up failed")
        }
    }

    /**
     * Sign in user with email and password (using SDK - this works!)
     */
    suspend fun signIn(email: String, password: String): SignInResult = suspendCancellableCoroutine { cont ->
        val cognitoUser = userPool.getUser(email)
        val authenticationDetails = AuthenticationDetails(email, password, null)

        cognitoUser.getSessionInBackground(object : AuthenticationHandler {
            override fun onSuccess(userSession: CognitoUserSession?, newDevice: CognitoDevice?) {
                if (userSession != null) {
                    val user = User(
                        id = cognitoUser.userId,
                        email = email,
                        username = cognitoUser.userId,
                        preferredLanguage = "en",
                        lastLoginAt = System.currentTimeMillis()
                    )

                    cont.resume(
                        SignInResult.Success(
                            user = user,
                            accessToken = userSession.accessToken.jwtToken,
                            idToken = userSession.idToken.jwtToken,
                            refreshToken = userSession.refreshToken.token
                        )
                    )
                } else {
                    cont.resumeWithException(Exception("Session is null"))
                }
            }

            override fun getAuthenticationDetails(authenticationContinuation: AuthenticationContinuation?, userId: String?) {
                authenticationContinuation?.setAuthenticationDetails(authenticationDetails)
                authenticationContinuation?.continueTask()
            }

            override fun getMFACode(multiFactorAuthenticationContinuation: MultiFactorAuthenticationContinuation?) {
                multiFactorAuthenticationContinuation?.continueTask()
            }

            override fun authenticationChallenge(challengeContinuation: ChallengeContinuation?) {
                challengeContinuation?.continueTask()
            }

            override fun onFailure(exception: Exception?) {
                cont.resumeWithException(exception ?: Exception("Unknown error"))
            }
        })
    }

    /**
     * Sign out current user
     */
    fun signOut(email: String) {
        val cognitoUser = userPool.getUser(email)
        cognitoUser.signOut()
    }

    /**
     * Get current user session
     */
    suspend fun getCurrentSession(email: String): CognitoUserSession? = suspendCancellableCoroutine { cont ->
        val cognitoUser = userPool.getUser(email)

        cognitoUser.getSessionInBackground(object : AuthenticationHandler {
            override fun onSuccess(userSession: CognitoUserSession?, newDevice: CognitoDevice?) {
                cont.resume(userSession)
            }

            override fun getAuthenticationDetails(authenticationContinuation: AuthenticationContinuation?, userId: String?) {
                cont.resume(null)
            }

            override fun getMFACode(multiFactorAuthenticationContinuation: MultiFactorAuthenticationContinuation?) {
                cont.resume(null)
            }

            override fun authenticationChallenge(challengeContinuation: ChallengeContinuation?) {
                cont.resume(null)
            }

            override fun onFailure(exception: Exception?) {
                cont.resume(null)
            }
        })
    }

    /**
     * Refresh access token using refresh token
     */
    suspend fun refreshSession(email: String): CognitoUserSession? = suspendCancellableCoroutine { continuation ->
        try {
            val cognitoUser = userPool.getUser(email)

            cognitoUser.getSession(object : AuthenticationHandler {
                override fun onSuccess(userSession: CognitoUserSession?, newDevice: CognitoDevice?) {
                    if (userSession != null) {
                        Log.d(TAG, "Session refreshed successfully for: $email")
                        continuation.resume(userSession)
                    } else {
                        Log.e(TAG, "Session refresh returned null session")
                        continuation.resume(null)
                    }
                }

                override fun getAuthenticationDetails(
                    authenticationContinuation: AuthenticationContinuation?,
                    userId: String?
                ) {
                    // Not needed for session refresh
                    Log.e(TAG, "Unexpected: getAuthenticationDetails called during refresh")
                    continuation.resume(null)
                }

                override fun getMFACode(multiFactorAuthenticationContinuation: MultiFactorAuthenticationContinuation?) {
                    // Not needed for session refresh
                    Log.e(TAG, "Unexpected: getMFACode called during refresh")
                    continuation.resume(null)
                }

                override fun authenticationChallenge(challengeContinuation: ChallengeContinuation?) {
                    // Not needed for session refresh
                    Log.e(TAG, "Unexpected: authenticationChallenge called during refresh")
                    continuation.resume(null)
                }

                override fun onFailure(exception: Exception?) {
                    Log.e(TAG, "Failed to refresh session for: $email", exception)
                    continuation.resume(null)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing session", e)
            continuation.resume(null)
        }
    }

    /**
     * Verify email with confirmation code using REST API
     */
    suspend fun confirmSignUp(email: String, confirmationCode: String): Boolean {
        return try {
            val request = ConfirmSignUpRequest(
                clientId = Constants.AWS.CLIENT_ID,
                username = email,
                confirmationCode = confirmationCode
            )

            val response = cognitoApi.confirmSignUp(request = request)

            if (response.isSuccessful) {
                Log.d(TAG, "Email confirmation successful for: $email")
                true
            } else {
                Log.e(TAG, "Email confirmation failed: ${response.errorBody()?.string()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Email confirmation failed", e)
            false
        }
    }

    /**
     * Resend confirmation code using REST API
     */
    suspend fun resendConfirmationCode(email: String): String? {
        return try {
            val request = ResendConfirmationCodeRequest(
                clientId = Constants.AWS.CLIENT_ID,
                username = email
            )

            val response = cognitoApi.resendConfirmationCode(request = request)

            if (response.isSuccessful && response.body() != null) {
                response.body()!!.codeDeliveryDetails.destination
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Resend confirmation code failed", e)
            null
        }
    }

    /**
     * Forgot password - initiate reset using REST API
     */
    suspend fun forgotPassword(email: String): String? {
        return try {
            val request = ForgotPasswordRequest(
                clientId = Constants.AWS.CLIENT_ID,
                username = email
            )

            val response = cognitoApi.forgotPassword(request = request)

            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "Password reset initiated for: $email")
                response.body()!!.codeDeliveryDetails.destination
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Forgot password failed", e)
            null
        }
    }

    /**
     * Confirm password reset using REST API
     */
    suspend fun confirmForgotPassword(email: String, confirmationCode: String, newPassword: String): Boolean {
        return try {
            val request = ConfirmForgotPasswordRequest(
                clientId = Constants.AWS.CLIENT_ID,
                username = email,
                confirmationCode = confirmationCode,
                password = newPassword
            )

            val response = cognitoApi.confirmForgotPassword(request = request)

            if (response.isSuccessful) {
                Log.d(TAG, "Password reset successful for: $email")
                true
            } else {
                Log.e(TAG, "Confirm password failed: ${response.errorBody()?.string()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Confirm password failed", e)
            false
        }
    }
}

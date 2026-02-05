package com.medpull.kiosk.data.remote.aws

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit interface for AWS Cognito REST API
 * Bypasses SDK interface compatibility issues
 */
interface CognitoApiService {

    @POST("/")
    suspend fun signUp(
        @Header("X-Amz-Target") target: String = "AWSCognitoIdentityProviderService.SignUp",
        @Header("Content-Type") contentType: String = "application/x-amz-json-1.1",
        @Body request: SignUpRequest
    ): Response<SignUpResponse>

    @POST("/")
    suspend fun confirmSignUp(
        @Header("X-Amz-Target") target: String = "AWSCognitoIdentityProviderService.ConfirmSignUp",
        @Header("Content-Type") contentType: String = "application/x-amz-json-1.1",
        @Body request: ConfirmSignUpRequest
    ): Response<ConfirmSignUpResponse>

    @POST("/")
    suspend fun initiateAuth(
        @Header("X-Amz-Target") target: String = "AWSCognitoIdentityProviderService.InitiateAuth",
        @Header("Content-Type") contentType: String = "application/x-amz-json-1.1",
        @Body request: InitiateAuthRequest
    ): Response<InitiateAuthResponse>

    @POST("/")
    suspend fun forgotPassword(
        @Header("X-Amz-Target") target: String = "AWSCognitoIdentityProviderService.ForgotPassword",
        @Header("Content-Type") contentType: String = "application/x-amz-json-1.1",
        @Body request: ForgotPasswordRequest
    ): Response<ForgotPasswordResponse>

    @POST("/")
    suspend fun confirmForgotPassword(
        @Header("X-Amz-Target") target: String = "AWSCognitoIdentityProviderService.ConfirmForgotPassword",
        @Header("Content-Type") contentType: String = "application/x-amz-json-1.1",
        @Body request: ConfirmForgotPasswordRequest
    ): Response<ConfirmForgotPasswordResponse>

    @POST("/")
    suspend fun resendConfirmationCode(
        @Header("X-Amz-Target") target: String = "AWSCognitoIdentityProviderService.ResendConfirmationCode",
        @Header("Content-Type") contentType: String = "application/x-amz-json-1.1",
        @Body request: ResendConfirmationCodeRequest
    ): Response<ResendConfirmationCodeResponse>
}

// Request/Response models

data class SignUpRequest(
    @SerializedName("ClientId") val clientId: String,
    @SerializedName("Username") val username: String,
    @SerializedName("Password") val password: String,
    @SerializedName("UserAttributes") val userAttributes: List<AttributeType>? = null
)

data class AttributeType(
    @SerializedName("Name") val name: String,
    @SerializedName("Value") val value: String
)

data class SignUpResponse(
    @SerializedName("UserConfirmed") val userConfirmed: Boolean,
    @SerializedName("CodeDeliveryDetails") val codeDeliveryDetails: CodeDeliveryDetailsType?,
    @SerializedName("UserSub") val userSub: String
)

data class CodeDeliveryDetailsType(
    @SerializedName("Destination") val destination: String?,
    @SerializedName("DeliveryMedium") val deliveryMedium: String?,
    @SerializedName("AttributeName") val attributeName: String?
)

data class ConfirmSignUpRequest(
    @SerializedName("ClientId") val clientId: String,
    @SerializedName("Username") val username: String,
    @SerializedName("ConfirmationCode") val confirmationCode: String
)

data class ConfirmSignUpResponse(
    // Empty response on success
    val status: String? = null
)

data class InitiateAuthRequest(
    @SerializedName("AuthFlow") val authFlow: String = "USER_PASSWORD_AUTH",
    @SerializedName("ClientId") val clientId: String,
    @SerializedName("AuthParameters") val authParameters: Map<String, String>
)

data class InitiateAuthResponse(
    @SerializedName("AuthenticationResult") val authenticationResult: AuthenticationResultType?,
    @SerializedName("ChallengeName") val challengeName: String?,
    @SerializedName("Session") val session: String?
)

data class AuthenticationResultType(
    @SerializedName("AccessToken") val accessToken: String,
    @SerializedName("ExpiresIn") val expiresIn: Int,
    @SerializedName("TokenType") val tokenType: String,
    @SerializedName("RefreshToken") val refreshToken: String?,
    @SerializedName("IdToken") val idToken: String
)

data class ForgotPasswordRequest(
    @SerializedName("ClientId") val clientId: String,
    @SerializedName("Username") val username: String
)

data class ForgotPasswordResponse(
    @SerializedName("CodeDeliveryDetails") val codeDeliveryDetails: CodeDeliveryDetailsType
)

data class ConfirmForgotPasswordRequest(
    @SerializedName("ClientId") val clientId: String,
    @SerializedName("Username") val username: String,
    @SerializedName("ConfirmationCode") val confirmationCode: String,
    @SerializedName("Password") val password: String
)

data class ConfirmForgotPasswordResponse(
    // Empty response on success
    val status: String? = null
)

data class ResendConfirmationCodeRequest(
    @SerializedName("ClientId") val clientId: String,
    @SerializedName("Username") val username: String
)

data class ResendConfirmationCodeResponse(
    @SerializedName("CodeDeliveryDetails") val codeDeliveryDetails: CodeDeliveryDetailsType
)

data class CognitoErrorResponse(
    @SerializedName("__type") val type: String,
    @SerializedName("message") val message: String
)

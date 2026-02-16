package com.medpull.kiosk.healthcare.auth

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that adds the SMART on FHIR Bearer token to outgoing requests.
 */
@Singleton
class SmartAuthInterceptor @Inject constructor(
    private val smartAuthManager: SmartAuthManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val token = smartAuthManager.getAccessToken()
            ?: return chain.proceed(originalRequest)

        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/fhir+json")
            .build()

        return chain.proceed(authenticatedRequest)
    }
}

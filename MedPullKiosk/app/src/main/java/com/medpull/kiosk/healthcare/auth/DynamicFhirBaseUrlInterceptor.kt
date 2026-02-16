package com.medpull.kiosk.healthcare.auth

import com.medpull.kiosk.security.SecureStorageManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that rewrites the base URL to the configured FHIR server URL
 * stored in SecureStorageManager. This allows runtime server configuration changes.
 */
@Singleton
class DynamicFhirBaseUrlInterceptor @Inject constructor(
    private val secureStorageManager: SecureStorageManager
) : Interceptor {

    companion object {
        const val KEY_FHIR_SERVER_URL = "fhir_server_url"
        const val PLACEHOLDER_HOST = "fhir.placeholder.local"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Only rewrite if using the placeholder host
        if (originalRequest.url.host != PLACEHOLDER_HOST) {
            return chain.proceed(originalRequest)
        }

        val serverUrl = secureStorageManager.getSecureString(KEY_FHIR_SERVER_URL)
            ?: return chain.proceed(originalRequest)

        val baseHttpUrl = serverUrl.trimEnd('/').toHttpUrlOrNull()
            ?: return chain.proceed(originalRequest)

        val newUrl = originalRequest.url.newBuilder()
            .scheme(baseHttpUrl.scheme)
            .host(baseHttpUrl.host)
            .port(baseHttpUrl.port)
            .encodedPath(
                baseHttpUrl.encodedPath.trimEnd('/') + "/" +
                originalRequest.url.encodedPath.trimStart('/')
            )
            .build()

        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }
}

package com.medpull.kiosk.healthcare.client

/**
 * Configuration for FHIR server connection.
 * Persisted via SecureStorageManager.
 */
data class FhirServerConfig(
    val serverUrl: String = "",
    val authType: FhirAuthType = FhirAuthType.NONE,
    val smartClientId: String = "",
    val smartRedirectUri: String = "com.medpull.kiosk://fhir-callback",
    val smartScopes: String = "openid fhirUser launch/patient patient/*.read patient/*.write"
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank()

    val metadataUrl: String
        get() = "${serverUrl.trimEnd('/')}/metadata"

    val baseUrl: String
        get() = serverUrl.trimEnd('/')
}

enum class FhirAuthType {
    NONE,
    SMART_ON_FHIR,
    BASIC,
    BEARER_TOKEN
}

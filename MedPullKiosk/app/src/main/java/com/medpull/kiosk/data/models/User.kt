package com.medpull.kiosk.data.models

/**
 * User domain model
 */
data class User(
    val id: String,
    val email: String,
    val username: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val preferredLanguage: String = "en",
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long? = null
)

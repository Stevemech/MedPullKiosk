package com.medpull.kiosk.utils

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Utility for JWT token operations
 */
object JwtUtils {

    /**
     * Decode JWT payload without verification
     * Returns the claims as a map
     */
    fun decodeJwt(token: String): Map<String, Any>? {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return null

            // Decode payload (second part)
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP),
                StandardCharsets.UTF_8
            )

            val jsonObject = JSONObject(payload)
            val claims = mutableMapOf<String, Any>()

            jsonObject.keys().forEach { key ->
                claims[key] = jsonObject.get(key)
            }

            claims
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get token expiration time in milliseconds
     */
    fun getExpirationTime(token: String): Long? {
        val claims = decodeJwt(token) ?: return null
        val exp = claims["exp"] as? Int ?: return null
        return exp.toLong() * 1000 // Convert to milliseconds
    }

    /**
     * Check if token is expired
     * @param bufferSeconds - Consider token expired N seconds before actual expiration (default 5 minutes)
     */
    fun isTokenExpired(token: String, bufferSeconds: Long = 300): Boolean {
        val expirationTime = getExpirationTime(token) ?: return true
        val currentTime = System.currentTimeMillis()
        val bufferMillis = bufferSeconds * 1000

        return currentTime >= (expirationTime - bufferMillis)
    }

    /**
     * Check if token will expire soon (within next 5 minutes)
     */
    fun isTokenExpiringSoon(token: String, thresholdSeconds: Long = 300): Boolean {
        val expirationTime = getExpirationTime(token) ?: return true
        val currentTime = System.currentTimeMillis()
        val threshold = thresholdSeconds * 1000

        return (expirationTime - currentTime) <= threshold
    }

    /**
     * Get time until token expires in milliseconds
     */
    fun getTimeUntilExpiration(token: String): Long? {
        val expirationTime = getExpirationTime(token) ?: return null
        val currentTime = System.currentTimeMillis()
        val timeRemaining = expirationTime - currentTime

        return if (timeRemaining > 0) timeRemaining else 0
    }
}

package com.medpull.kiosk.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.medpull.kiosk.utils.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages encrypted storage for sensitive data (tokens, credentials)
 * Uses Android's EncryptedSharedPreferences for HIPAA compliance
 */
@Singleton
class SecureStorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            Constants.Encryption.ENCRYPTED_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Save auth token securely
     */
    fun saveAuthToken(token: String) {
        encryptedPrefs.edit()
            .putString(Constants.Encryption.KEY_AUTH_TOKEN, token)
            .apply()
    }

    /**
     * Get auth token
     */
    fun getAuthToken(): String? {
        return encryptedPrefs.getString(Constants.Encryption.KEY_AUTH_TOKEN, null)
    }

    /**
     * Save ID token securely
     */
    fun saveIdToken(token: String) {
        encryptedPrefs.edit()
            .putString(Constants.Encryption.KEY_ID_TOKEN, token)
            .apply()
    }

    /**
     * Get ID token
     */
    fun getIdToken(): String? {
        return encryptedPrefs.getString(Constants.Encryption.KEY_ID_TOKEN, null)
    }

    /**
     * Save refresh token securely
     */
    fun saveRefreshToken(token: String) {
        encryptedPrefs.edit()
            .putString(Constants.Encryption.KEY_REFRESH_TOKEN, token)
            .apply()
    }

    /**
     * Get refresh token
     */
    fun getRefreshToken(): String? {
        return encryptedPrefs.getString(Constants.Encryption.KEY_REFRESH_TOKEN, null)
    }

    /**
     * Save generic secure data
     */
    fun saveSecureString(key: String, value: String) {
        encryptedPrefs.edit()
            .putString(key, value)
            .apply()
    }

    /**
     * Get generic secure data
     */
    fun getSecureString(key: String): String? {
        return encryptedPrefs.getString(key, null)
    }

    /**
     * Save boolean flag
     */
    fun saveSecureBoolean(key: String, value: Boolean) {
        encryptedPrefs.edit()
            .putBoolean(key, value)
            .apply()
    }

    /**
     * Get boolean flag
     */
    fun getSecureBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return encryptedPrefs.getBoolean(key, defaultValue)
    }

    /**
     * Remove specific key
     */
    fun remove(key: String) {
        encryptedPrefs.edit()
            .remove(key)
            .apply()
    }

    /**
     * Clear all secure storage (logout)
     */
    fun clearAll() {
        encryptedPrefs.edit()
            .clear()
            .apply()
    }

    /**
     * Check if auth token exists
     */
    fun hasAuthToken(): Boolean {
        return !getAuthToken().isNullOrEmpty()
    }

    /**
     * Check if refresh token exists
     */
    fun hasRefreshToken(): Boolean {
        return !getRefreshToken().isNullOrEmpty()
    }
}

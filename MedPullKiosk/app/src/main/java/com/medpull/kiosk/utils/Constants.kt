package com.medpull.kiosk.utils

import com.medpull.kiosk.BuildConfig

/**
 * Application-wide constants
 */
object Constants {

    // AWS Configuration
    object AWS {
        const val REGION = BuildConfig.AWS_REGION
        const val USER_POOL_ID = BuildConfig.AWS_USER_POOL_ID
        const val CLIENT_ID = BuildConfig.AWS_CLIENT_ID
        const val API_ENDPOINT = BuildConfig.AWS_API_ENDPOINT
        const val S3_BUCKET = BuildConfig.AWS_S3_BUCKET

        // S3 Folder structure
        const val S3_FORMS_FOLDER = "forms/"
        const val S3_FILLED_FORMS_FOLDER = "filled-forms/"
        const val S3_AUDIT_LOGS_FOLDER = "audit-logs/"
    }

    // Session Management
    object Session {
        const val TIMEOUT_MS = BuildConfig.SESSION_TIMEOUT_MS
        const val WARNING_THRESHOLD_MS = TIMEOUT_MS - 120000L // 2 minutes before timeout
        const val ACTIVITY_CHECK_INTERVAL_MS = 30000L // Check every 30 seconds
    }

    // Supported Languages
    object Languages {
        const val ENGLISH = "en"
        const val SPANISH = "es"
        const val CHINESE = "zh"
        const val FRENCH = "fr"
        const val HINDI = "hi"
        const val ARABIC = "ar"

        val ALL = listOf(ENGLISH, SPANISH, CHINESE, FRENCH, HINDI, ARABIC)

        fun getLanguageName(code: String): String = when (code) {
            ENGLISH -> "English"
            SPANISH -> "Español"
            CHINESE -> "中文"
            FRENCH -> "Français"
            HINDI -> "हिन्दी"
            ARABIC -> "العربية"
            else -> "English"
        }
    }

    // Database
    object Database {
        const val NAME = "medpull_kiosk.db"
        const val VERSION = 1
    }

    // DataStore
    object DataStore {
        const val PREFERENCES_NAME = "medpull_preferences"
        const val KEY_LANGUAGE = "selected_language"
        const val KEY_LAST_SYNC = "last_sync_timestamp"
        const val KEY_USER_ID = "user_id"
    }

    // Encryption
    object Encryption {
        const val KEYSTORE_ALIAS = "medpull_master_key"
        const val ENCRYPTED_PREFS_NAME = "medpull_secure_prefs"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }

    // PDF Processing
    object Pdf {
        const val MAX_FILE_SIZE_MB = 50
        const val SUPPORTED_FORMATS = "application/pdf"
        const val FORM_FIELD_CONFIDENCE_THRESHOLD = 0.85f
    }

    // Network
    object Network {
        const val CONNECT_TIMEOUT_SECONDS = 30L
        const val READ_TIMEOUT_SECONDS = 60L
        const val WRITE_TIMEOUT_SECONDS = 60L
        const val MAX_RETRY_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 1000L
    }

    // AI Assistance
    object AI {
        // Note: In production, this should be stored securely or fetched from backend
        const val OPENAI_API_KEY = "" // User needs to add their own API key
        const val OPENAI_MODEL = "gpt-3.5-turbo"
        const val CLAUDE_MODEL = "claude-3-sonnet-20240229"
        const val MAX_TOKENS = 500
        const val TEMPERATURE = 0.7f
        const val SYSTEM_PROMPT_TEMPLATE = """
            You are a helpful medical form assistant. The user is filling out a medical form in %s.
            Please provide assistance in %s. Be concise, accurate, and respectful of medical privacy.
            Do not provide medical advice - only help with form filling questions.
        """
    }

    // Audit Logging
    object Audit {
        const val ACTION_LOGIN = "LOGIN"
        const val ACTION_LOGOUT = "LOGOUT"
        const val ACTION_FORM_UPLOAD = "FORM_UPLOAD"
        const val ACTION_FORM_VIEW = "FORM_VIEW"
        const val ACTION_FORM_EDIT = "FORM_EDIT"
        const val ACTION_FORM_EXPORT = "FORM_EXPORT"
        const val ACTION_AI_QUERY = "AI_QUERY"
        const val ACTION_SESSION_TIMEOUT = "SESSION_TIMEOUT"
    }

    // UI
    object UI {
        const val SPLASH_DELAY_MS = 2000L
        const val TOAST_DURATION_MS = 3000L
        const val ANIMATION_DURATION_MS = 300L
    }

    // Validation
    object Validation {
        const val MIN_PASSWORD_LENGTH = 8
        const val PASSWORD_REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@\$!%*?&])[A-Za-z\\d@\$!%*?&]{8,}$"
        const val EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    }
}

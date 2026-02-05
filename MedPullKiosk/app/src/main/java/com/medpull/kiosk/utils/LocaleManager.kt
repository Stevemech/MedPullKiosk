package com.medpull.kiosk.utils

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages application locale and language switching
 * Persists language selection across app sessions
 */
@Singleton
class LocaleManager @Inject constructor() {

    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = Constants.DataStore.PREFERENCES_NAME
    )

    private var applicationContext: Context? = null

    companion object {
        private val LANGUAGE_KEY = stringPreferencesKey(Constants.DataStore.KEY_LANGUAGE)
    }

    /**
     * Initialize with application context
     */
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    /**
     * Get current language as Flow
     */
    fun getLanguageFlow(context: Context): Flow<String> {
        return context.dataStore.data.map { preferences ->
            preferences[LANGUAGE_KEY] ?: Constants.Languages.ENGLISH
        }
    }

    /**
     * Get current language synchronously (use sparingly)
     */
    fun getCurrentLanguage(context: Context): String = runBlocking {
        context.dataStore.data.map { preferences ->
            preferences[LANGUAGE_KEY] ?: Constants.Languages.ENGLISH
        }.first()
    }

    /**
     * Set language and update configuration
     */
    suspend fun setLanguage(context: Context, languageCode: String) {
        // Validate language code
        if (!Constants.Languages.ALL.contains(languageCode)) {
            throw IllegalArgumentException("Unsupported language: $languageCode")
        }

        // Save to DataStore
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = languageCode
        }
    }

    /**
     * Apply locale to context (returns updated context)
     */
    fun applyLocale(context: Context, languageCode: String): Context {
        val locale = getLocaleFromCode(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))

        return context.createConfigurationContext(config)
    }

    /**
     * Get Locale object from language code
     */
    private fun getLocaleFromCode(languageCode: String): Locale {
        return when (languageCode) {
            Constants.Languages.ENGLISH -> Locale.ENGLISH
            Constants.Languages.SPANISH -> Locale("es")
            Constants.Languages.CHINESE -> Locale.SIMPLIFIED_CHINESE
            Constants.Languages.FRENCH -> Locale.FRENCH
            Constants.Languages.HINDI -> Locale("hi")
            Constants.Languages.ARABIC -> Locale("ar")
            else -> Locale.ENGLISH
        }
    }

    /**
     * Get display name for language
     */
    fun getLanguageDisplayName(languageCode: String): String {
        return Constants.Languages.getLanguageName(languageCode)
    }

    /**
     * Get all supported languages
     */
    fun getSupportedLanguages(): List<LanguageOption> {
        return Constants.Languages.ALL.map { code ->
            LanguageOption(
                code = code,
                displayName = getLanguageDisplayName(code),
                nativeName = getLocaleFromCode(code).displayName
            )
        }
    }

    /**
     * Check if RTL (Right-to-Left) language
     */
    fun isRtl(languageCode: String): Boolean {
        return languageCode == Constants.Languages.ARABIC
    }
}

/**
 * Data class for language selection
 */
data class LanguageOption(
    val code: String,
    val displayName: String,
    val nativeName: String
)

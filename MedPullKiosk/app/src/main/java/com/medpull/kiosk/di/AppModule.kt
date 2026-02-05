package com.medpull.kiosk.di

import android.content.Context
import com.medpull.kiosk.security.HipaaAuditLogger
import com.medpull.kiosk.security.SecureStorageManager
import com.medpull.kiosk.utils.LocaleManager
import com.medpull.kiosk.utils.PdfUtils
import com.medpull.kiosk.utils.SessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for app-level dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSecureStorageManager(
        @ApplicationContext context: Context
    ): SecureStorageManager {
        return SecureStorageManager(context)
    }

    @Provides
    @Singleton
    fun provideSessionManager(): SessionManager {
        return SessionManager()
    }

    @Provides
    @Singleton
    fun provideLocaleManager(): LocaleManager {
        return LocaleManager()
    }

    @Provides
    @Singleton
    fun providePdfUtils(
        @ApplicationContext context: Context
    ): PdfUtils {
        return PdfUtils(context)
    }
}

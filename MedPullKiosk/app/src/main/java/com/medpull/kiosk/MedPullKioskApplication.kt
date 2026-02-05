package com.medpull.kiosk

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.medpull.kiosk.sync.SyncWorker
import com.medpull.kiosk.utils.LocaleManager
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Main application class for MedPull Kiosk
 * Initializes Hilt, WorkManager, and global configurations
 */
@HiltAndroidApp
class MedPullKioskApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var localeManager: LocaleManager

    override fun onCreate() {
        super.onCreate()

        // Initialize locale manager
        localeManager.initialize(this)

        // Initialize WorkManager for background sync
        initializeWorkManager()

        // Apply security settings
        applySecuritySettings()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    /**
     * Initialize WorkManager for periodic background sync
     */
    private fun initializeWorkManager() {
        val syncWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES // Sync every 15 minutes
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncWorkRequest
        )
    }

    /**
     * Apply HIPAA-compliant security settings
     */
    private fun applySecuritySettings() {
        // Prevent screenshots and screen recording (FLAG_SECURE)
        // This will be applied per-activity in MainActivity
    }
}

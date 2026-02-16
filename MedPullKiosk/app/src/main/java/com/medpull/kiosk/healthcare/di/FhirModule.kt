package com.medpull.kiosk.healthcare.di

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.medpull.kiosk.BuildConfig
import com.medpull.kiosk.data.local.AppDatabase
import com.medpull.kiosk.data.local.dao.FhirMappingDao
import com.medpull.kiosk.healthcare.auth.DynamicFhirBaseUrlInterceptor
import com.medpull.kiosk.healthcare.auth.SmartAuthInterceptor
import com.medpull.kiosk.healthcare.client.FhirApiService
import com.medpull.kiosk.utils.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FhirOkHttp

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FhirRetrofit

@Module
@InstallIn(SingletonComponent::class)
object FhirModule {

    @Provides
    @Singleton
    fun provideFhirContext(): FhirContext {
        return FhirContext.forCached(FhirVersionEnum.R4)
    }

    @Provides
    @Singleton
    fun provideFhirMappingDao(database: AppDatabase): FhirMappingDao {
        return database.fhirMappingDao()
    }

    @Provides
    @Singleton
    @FhirOkHttp
    fun provideFhirOkHttpClient(
        smartAuthInterceptor: SmartAuthInterceptor,
        dynamicBaseUrlInterceptor: DynamicFhirBaseUrlInterceptor
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(dynamicBaseUrlInterceptor)
            .addInterceptor(smartAuthInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(Constants.Network.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Constants.Network.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(Constants.Network.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @FhirRetrofit
    fun provideFhirRetrofit(
        @FhirOkHttp okHttpClient: OkHttpClient
    ): Retrofit {
        // Placeholder base URL — DynamicFhirBaseUrlInterceptor rewrites it at runtime
        return Retrofit.Builder()
            .baseUrl("https://${DynamicFhirBaseUrlInterceptor.PLACEHOLDER_HOST}/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideFhirApiService(
        @FhirRetrofit retrofit: Retrofit
    ): FhirApiService {
        return retrofit.create(FhirApiService::class.java)
    }
}

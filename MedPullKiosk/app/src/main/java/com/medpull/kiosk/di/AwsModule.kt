package com.medpull.kiosk.di

import android.content.Context
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserPool
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.textract.AmazonTextractClient
import com.amazonaws.services.translate.AmazonTranslateClient
import com.medpull.kiosk.utils.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for AWS SDK dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AwsModule {

    @Provides
    @Singleton
    fun provideAwsRegion(): Regions {
        return Regions.fromName(Constants.AWS.REGION)
    }

    @Provides
    @Singleton
    fun provideCognitoUserPool(
        @ApplicationContext context: Context
    ): CognitoUserPool {
        return CognitoUserPool(
            context,
            Constants.AWS.USER_POOL_ID,
            Constants.AWS.CLIENT_ID,
            null, // No client secret for public clients
            Regions.fromName(Constants.AWS.REGION)
        )
    }

    @Provides
    @Singleton
    fun provideCognitoCredentialsProvider(
        @ApplicationContext context: Context,
        awsRegion: Regions
    ): CognitoCachingCredentialsProvider {
        // This will be updated with Identity Pool ID when users authenticate
        // For now, create with a placeholder identity pool (will be configured per user)
        // Note: This should be replaced with actual identity pool ID in production
        return CognitoCachingCredentialsProvider(
            context,
            "us-east-1:00000000-0000-0000-0000-000000000000", // Placeholder identity pool ID
            awsRegion
        )
    }

    @Provides
    @Singleton
    fun provideS3Client(
        credentialsProvider: CognitoCachingCredentialsProvider,
        awsRegion: Regions
    ): AmazonS3Client {
        val s3Client = AmazonS3Client(
            credentialsProvider,
            Region.getRegion(awsRegion)
        )
        s3Client.setRegion(Region.getRegion(awsRegion))
        return s3Client
    }

    @Provides
    @Singleton
    fun provideTextractClient(
        credentialsProvider: CognitoCachingCredentialsProvider,
        awsRegion: Regions
    ): AmazonTextractClient {
        val textractClient = AmazonTextractClient(credentialsProvider)
        textractClient.setRegion(Region.getRegion(awsRegion))
        return textractClient
    }

    @Provides
    @Singleton
    fun provideTranslateClient(
        credentialsProvider: CognitoCachingCredentialsProvider,
        awsRegion: Regions
    ): AmazonTranslateClient {
        val translateClient = AmazonTranslateClient(credentialsProvider)
        translateClient.setRegion(Region.getRegion(awsRegion))
        return translateClient
    }

    // AWS Service Providers

    @Provides
    @Singleton
    fun provideCognitoAuthService(
        userPool: CognitoUserPool,
        cognitoApi: com.medpull.kiosk.data.remote.aws.CognitoApiService
    ): com.medpull.kiosk.data.remote.aws.CognitoAuthServiceV2 {
        return com.medpull.kiosk.data.remote.aws.CognitoAuthServiceV2(userPool, cognitoApi)
    }

    @Provides
    @Singleton
    fun provideS3Service(
        s3Client: AmazonS3Client
    ): com.medpull.kiosk.data.remote.aws.S3Service {
        return com.medpull.kiosk.data.remote.aws.S3Service(s3Client)
    }

    @Provides
    @Singleton
    fun provideTextractService(
        textractClient: AmazonTextractClient,
        s3Service: com.medpull.kiosk.data.remote.aws.S3Service
    ): com.medpull.kiosk.data.remote.aws.TextractService {
        return com.medpull.kiosk.data.remote.aws.TextractService(textractClient, s3Service)
    }

    @Provides
    @Singleton
    fun provideTranslationService(
        translateClient: AmazonTranslateClient
    ): com.medpull.kiosk.data.remote.aws.TranslationService {
        return com.medpull.kiosk.data.remote.aws.TranslationService(translateClient)
    }

    @Provides
    @Singleton
    fun provideApiGatewayService(
        okHttpClient: okhttp3.OkHttpClient,
        gson: com.google.gson.Gson
    ): com.medpull.kiosk.data.remote.aws.ApiGatewayService {
        return com.medpull.kiosk.data.remote.aws.ApiGatewayService(okHttpClient, gson)
    }
}

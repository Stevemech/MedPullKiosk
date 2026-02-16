package com.medpull.kiosk.healthcare.client

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for FHIR R4 REST API operations.
 */
interface FhirApiService {

    @GET("metadata")
    suspend fun getMetadata(): Response<ResponseBody>

    @GET("Patient/{id}")
    suspend fun readPatient(@Path("id") id: String): Response<ResponseBody>

    @GET("Patient")
    suspend fun searchPatients(
        @QueryMap params: Map<String, String>
    ): Response<ResponseBody>

    @POST("Patient")
    suspend fun createPatient(
        @Body body: RequestBody
    ): Response<ResponseBody>

    @PUT("Patient/{id}")
    suspend fun updatePatient(
        @Path("id") id: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    @GET("Questionnaire/{id}")
    suspend fun readQuestionnaire(@Path("id") id: String): Response<ResponseBody>

    @GET("Questionnaire")
    suspend fun searchQuestionnaires(
        @QueryMap params: Map<String, String>
    ): Response<ResponseBody>

    @POST("QuestionnaireResponse")
    suspend fun createQuestionnaireResponse(
        @Body body: RequestBody
    ): Response<ResponseBody>

    @GET("QuestionnaireResponse/{id}")
    suspend fun readQuestionnaireResponse(@Path("id") id: String): Response<ResponseBody>

    @POST("DocumentReference")
    suspend fun createDocumentReference(
        @Body body: RequestBody
    ): Response<ResponseBody>

    @GET("DocumentReference/{id}")
    suspend fun readDocumentReference(@Path("id") id: String): Response<ResponseBody>
}

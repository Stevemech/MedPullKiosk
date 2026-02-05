package com.medpull.kiosk.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.medpull.kiosk.data.models.Form
import com.medpull.kiosk.data.models.FormStatus

/**
 * Room entity for Form
 */
@Entity(tableName = "forms")
data class FormEntity(
    @PrimaryKey
    val id: String,
    val userId: String,
    val fileName: String,
    val originalFileUri: String,
    val s3Key: String? = null,
    val status: String = FormStatus.UPLOADED.name,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val uploadedAt: Long? = null,
    val processedAt: Long? = null,
    val exportedAt: Long? = null
) {
    fun toDomain(fields: List<FormFieldEntity> = emptyList()): Form = Form(
        id = id,
        userId = userId,
        fileName = fileName,
        originalFileUri = originalFileUri,
        s3Key = s3Key,
        status = FormStatus.valueOf(status),
        fields = fields.map { it.toDomain() },
        createdAt = createdAt,
        updatedAt = updatedAt,
        uploadedAt = uploadedAt,
        processedAt = processedAt,
        exportedAt = exportedAt
    )

    companion object {
        fun fromDomain(form: Form): FormEntity = FormEntity(
            id = form.id,
            userId = form.userId,
            fileName = form.fileName,
            originalFileUri = form.originalFileUri,
            s3Key = form.s3Key,
            status = form.status.name,
            createdAt = form.createdAt,
            updatedAt = form.updatedAt,
            uploadedAt = form.uploadedAt,
            processedAt = form.processedAt,
            exportedAt = form.exportedAt
        )
    }
}

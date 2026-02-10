package com.medpull.kiosk.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.medpull.kiosk.data.models.BoundingBox
import com.medpull.kiosk.data.models.FieldType
import com.medpull.kiosk.data.models.FormField

/**
 * Room entity for FormField
 */
@Entity(
    tableName = "form_fields",
    foreignKeys = [
        ForeignKey(
            entity = FormEntity::class,
            parentColumns = ["id"],
            childColumns = ["formId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("formId")]
)
data class FormFieldEntity(
    @PrimaryKey
    val id: String,
    val formId: String,
    val fieldName: String,
    val fieldType: String,
    val originalText: String? = null,
    val translatedText: String? = null,
    val value: String? = null,
    val boundingBoxLeft: Float? = null,
    val boundingBoxTop: Float? = null,
    val boundingBoxWidth: Float? = null,
    val boundingBoxHeight: Float? = null,
    val boundingBoxPage: Int? = null,
    val labelBBLeft: Float? = null,
    val labelBBTop: Float? = null,
    val labelBBWidth: Float? = null,
    val labelBBHeight: Float? = null,
    val labelBBPage: Int? = null,
    val confidence: Float = 0f,
    val required: Boolean = false,
    val page: Int = 1
) {
    fun toDomain(): FormField {
        val boundingBox = if (boundingBoxLeft != null && boundingBoxTop != null &&
            boundingBoxWidth != null && boundingBoxHeight != null) {
            BoundingBox(
                left = boundingBoxLeft,
                top = boundingBoxTop,
                width = boundingBoxWidth,
                height = boundingBoxHeight,
                page = boundingBoxPage ?: 1
            )
        } else null

        val labelBoundingBox = if (labelBBLeft != null && labelBBTop != null &&
            labelBBWidth != null && labelBBHeight != null) {
            BoundingBox(
                left = labelBBLeft,
                top = labelBBTop,
                width = labelBBWidth,
                height = labelBBHeight,
                page = labelBBPage ?: 1
            )
        } else null

        return FormField(
            id = id,
            formId = formId,
            fieldName = fieldName,
            fieldType = FieldType.valueOf(fieldType),
            originalText = originalText,
            translatedText = translatedText,
            value = value,
            boundingBox = boundingBox,
            labelBoundingBox = labelBoundingBox,
            confidence = confidence,
            required = required,
            page = page
        )
    }

    companion object {
        fun fromDomain(field: FormField): FormFieldEntity = FormFieldEntity(
            id = field.id,
            formId = field.formId,
            fieldName = field.fieldName,
            fieldType = field.fieldType.name,
            originalText = field.originalText,
            translatedText = field.translatedText,
            value = field.value,
            boundingBoxLeft = field.boundingBox?.left,
            boundingBoxTop = field.boundingBox?.top,
            boundingBoxWidth = field.boundingBox?.width,
            boundingBoxHeight = field.boundingBox?.height,
            boundingBoxPage = field.boundingBox?.page,
            labelBBLeft = field.labelBoundingBox?.left,
            labelBBTop = field.labelBoundingBox?.top,
            labelBBWidth = field.labelBoundingBox?.width,
            labelBBHeight = field.labelBoundingBox?.height,
            labelBBPage = field.labelBoundingBox?.page,
            confidence = field.confidence,
            required = field.required,
            page = field.page
        )
    }
}

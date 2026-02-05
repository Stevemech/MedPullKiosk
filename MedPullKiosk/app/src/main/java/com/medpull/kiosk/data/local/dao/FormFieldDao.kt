package com.medpull.kiosk.data.local.dao

import androidx.room.*
import com.medpull.kiosk.data.local.entities.FormFieldEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for FormField operations
 */
@Dao
interface FormFieldDao {

    @Query("SELECT * FROM form_fields WHERE id = :fieldId")
    suspend fun getFieldById(fieldId: String): FormFieldEntity?

    @Query("SELECT * FROM form_fields WHERE formId = :formId ORDER BY page, fieldName")
    suspend fun getFieldsByFormId(formId: String): List<FormFieldEntity>

    @Query("SELECT * FROM form_fields WHERE formId = :formId ORDER BY page, fieldName")
    fun getFieldsByFormIdFlow(formId: String): Flow<List<FormFieldEntity>>

    @Query("SELECT * FROM form_fields WHERE formId = :formId AND page = :page")
    suspend fun getFieldsByFormIdAndPage(formId: String, page: Int): List<FormFieldEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertField(field: FormFieldEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFields(fields: List<FormFieldEntity>)

    @Update
    suspend fun updateField(field: FormFieldEntity)

    @Delete
    suspend fun deleteField(field: FormFieldEntity)

    @Query("DELETE FROM form_fields WHERE id = :fieldId")
    suspend fun deleteFieldById(fieldId: String)

    @Query("DELETE FROM form_fields WHERE formId = :formId")
    suspend fun deleteFieldsByFormId(formId: String)

    @Query("UPDATE form_fields SET value = :value WHERE id = :fieldId")
    suspend fun updateFieldValue(fieldId: String, value: String?)

    @Query("UPDATE form_fields SET translatedText = :translatedText WHERE id = :fieldId")
    suspend fun updateFieldTranslation(fieldId: String, translatedText: String)
}

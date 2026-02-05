package com.medpull.kiosk.data.local.dao

import androidx.room.*
import com.medpull.kiosk.data.local.entities.FormEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Form operations
 */
@Dao
interface FormDao {

    @Query("SELECT * FROM forms WHERE id = :formId")
    suspend fun getFormById(formId: String): FormEntity?

    @Query("SELECT * FROM forms WHERE id = :formId")
    fun getFormByIdFlow(formId: String): Flow<FormEntity?>

    @Query("SELECT * FROM forms WHERE userId = :userId ORDER BY createdAt DESC")
    suspend fun getFormsByUserId(userId: String): List<FormEntity>

    @Query("SELECT * FROM forms WHERE userId = :userId ORDER BY createdAt DESC")
    fun getFormsByUserIdFlow(userId: String): Flow<List<FormEntity>>

    @Query("SELECT * FROM forms WHERE status = :status")
    suspend fun getFormsByStatus(status: String): List<FormEntity>

    @Query("SELECT * FROM forms ORDER BY createdAt DESC")
    suspend fun getAllForms(): List<FormEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForm(form: FormEntity)

    @Update
    suspend fun updateForm(form: FormEntity)

    @Delete
    suspend fun deleteForm(form: FormEntity)

    @Query("DELETE FROM forms WHERE id = :formId")
    suspend fun deleteFormById(formId: String)

    @Query("DELETE FROM forms WHERE userId = :userId")
    suspend fun deleteFormsByUserId(userId: String)

    @Query("UPDATE forms SET status = :status, updatedAt = :timestamp WHERE id = :formId")
    suspend fun updateFormStatus(formId: String, status: String, timestamp: Long)

    @Query("UPDATE forms SET s3Key = :s3Key, uploadedAt = :timestamp WHERE id = :formId")
    suspend fun updateFormS3Key(formId: String, s3Key: String, timestamp: Long)
}

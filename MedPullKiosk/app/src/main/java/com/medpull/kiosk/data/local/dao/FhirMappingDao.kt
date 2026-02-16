package com.medpull.kiosk.data.local.dao

import androidx.room.*
import com.medpull.kiosk.data.local.entities.FhirMappingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FhirMappingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mapping: FhirMappingEntity): Long

    @Update
    suspend fun update(mapping: FhirMappingEntity)

    @Delete
    suspend fun delete(mapping: FhirMappingEntity)

    @Query("SELECT * FROM fhir_mappings WHERE localId = :localId AND resourceType = :resourceType LIMIT 1")
    suspend fun getByLocalId(localId: String, resourceType: String): FhirMappingEntity?

    @Query("SELECT * FROM fhir_mappings WHERE fhirResourceId = :fhirId AND resourceType = :resourceType LIMIT 1")
    suspend fun getByFhirId(fhirId: String, resourceType: String): FhirMappingEntity?

    @Query("SELECT * FROM fhir_mappings WHERE resourceType = :resourceType")
    fun getAllByType(resourceType: String): Flow<List<FhirMappingEntity>>

    @Query("SELECT * FROM fhir_mappings")
    fun getAll(): Flow<List<FhirMappingEntity>>

    @Query("DELETE FROM fhir_mappings WHERE localId = :localId")
    suspend fun deleteByLocalId(localId: String)

    @Query("DELETE FROM fhir_mappings WHERE fhirServerUrl = :serverUrl")
    suspend fun deleteByServerUrl(serverUrl: String)
}

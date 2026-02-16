package com.medpull.kiosk.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks associations between local entity IDs and FHIR resource IDs.
 * Enables bidirectional lookup for import/export operations.
 */
@Entity(
    tableName = "fhir_mappings",
    indices = [
        Index("localId"),
        Index("fhirResourceId"),
        Index("resourceType", "localId", unique = true)
    ]
)
data class FhirMappingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val localId: String,

    val fhirResourceId: String,

    val resourceType: String,

    val fhirServerUrl: String,

    val createdAt: Long = System.currentTimeMillis(),

    val updatedAt: Long = System.currentTimeMillis()
)

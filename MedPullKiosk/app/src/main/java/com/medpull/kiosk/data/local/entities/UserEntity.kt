package com.medpull.kiosk.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.medpull.kiosk.data.models.User

/**
 * Room entity for User
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val email: String,
    val username: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val preferredLanguage: String = "en",
    val createdAt: Long = System.currentTimeMillis(),
    val lastLoginAt: Long? = null
) {
    fun toDomain(): User = User(
        id = id,
        email = email,
        username = username,
        firstName = firstName,
        lastName = lastName,
        preferredLanguage = preferredLanguage,
        createdAt = createdAt,
        lastLoginAt = lastLoginAt
    )

    companion object {
        fun fromDomain(user: User): UserEntity = UserEntity(
            id = user.id,
            email = user.email,
            username = user.username,
            firstName = user.firstName,
            lastName = user.lastName,
            preferredLanguage = user.preferredLanguage,
            createdAt = user.createdAt,
            lastLoginAt = user.lastLoginAt
        )
    }
}

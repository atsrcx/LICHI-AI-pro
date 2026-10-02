package com.lichiai.memory.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_memories")
data class UserMemoryEntity(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    val userId: String = "default_user",
    val key: String, // e.g., "name", "residence", "programming_language", "favorite_color"
    val value: String, // e.g., "Aarav", "Noida", "Kotlin", "orange"
    val category: String = "general", // "identity", "preference", "project", "work"
    val updatedAt: Long = System.currentTimeMillis()
)

package com.lichiai.memory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UserMemoryDao {
    @Query("SELECT * FROM user_memories WHERE userId = :userId ORDER BY updatedAt DESC")
    suspend fun getAllMemories(userId: String): List<UserMemoryEntity>

    @Query("SELECT * FROM user_memories WHERE userId = :userId AND `key` = :key LIMIT 1")
    suspend fun getMemoryByKey(userId: String, key: String): UserMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: UserMemoryEntity)

    @Query("DELETE FROM user_memories WHERE userId = :userId AND `key` = :key")
    suspend fun deleteMemoryByKey(userId: String, key: String): Int

    @Query("DELETE FROM user_memories WHERE userId = :userId")
    suspend fun clearUserMemories(userId: String)
}

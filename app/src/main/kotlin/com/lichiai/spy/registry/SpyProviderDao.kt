package com.lichiai.spy.registry

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SpyProviderDao {
    @Query("SELECT * FROM spy_providers ORDER BY title ASC")
    fun getAllProvidersFlow(): Flow<List<SpyProviderEntity>>

    @Query("SELECT * FROM spy_providers ORDER BY title ASC")
    suspend fun getAllProviders(): List<SpyProviderEntity>

    @Query("SELECT * FROM spy_providers WHERE enabled = 1 ORDER BY selectionPriority DESC, successCount DESC")
    suspend fun getEnabledProviders(): List<SpyProviderEntity>

    @Query("SELECT * FROM spy_providers WHERE providerId = :id OR canonicalActorId = :id LIMIT 1")
    suspend fun getProviderById(id: String): SpyProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(provider: SpyProviderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(providers: List<SpyProviderEntity>)

    @Query("UPDATE spy_providers SET enabled = :enabled WHERE providerId = :providerId")
    suspend fun setEnabled(providerId: String, enabled: Boolean)

    @Query("DELETE FROM spy_providers WHERE enabled = 0")
    suspend fun clearDisabledCache()

    @Query("DELETE FROM spy_providers")
    suspend fun clearAll()
}

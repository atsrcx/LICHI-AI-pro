package com.lichiai.spy.registry

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SpyProviderEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SpyProviderDatabase : RoomDatabase() {
    abstract fun providerDao(): SpyProviderDao

    companion object {
        private const val DB_NAME = "lichi_spy_providers.db"

        @Volatile
        private var INSTANCE: SpyProviderDatabase? = null

        fun getInstance(context: Context): SpyProviderDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SpyProviderDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

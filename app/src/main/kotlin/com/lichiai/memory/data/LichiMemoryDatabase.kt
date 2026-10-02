package com.lichiai.memory.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [UserMemoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LichiMemoryDatabase : RoomDatabase() {

    abstract fun userMemoryDao(): UserMemoryDao

    companion object {
        private const val DB_NAME = "lichi_memory.db"

        @Volatile
        private var instance: LichiMemoryDatabase? = null

        fun getInstance(context: Context): LichiMemoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LichiMemoryDatabase::class.java,
                    DB_NAME
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
            }
        }
    }
}

package com.lichiai.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lichiai.memory.db.dao.EntityRecordDao
import com.lichiai.memory.db.dao.EntityRelationDao
import com.lichiai.memory.db.dao.MemoryItemDao
import com.lichiai.memory.db.dao.RawLedgerDao
import com.lichiai.memory.db.dao.TombstoneDao
import com.lichiai.memory.db.entity.ConversationRawLedgerEntity
import com.lichiai.memory.db.entity.ConversationRawLedgerFts
import com.lichiai.memory.db.entity.EntityRecordEntity
import com.lichiai.memory.db.entity.EntityRelationEntity
import com.lichiai.memory.db.entity.MemoryItemEntity
import com.lichiai.memory.db.entity.TombstoneRecordEntity

@Database(
    entities = [
        ConversationRawLedgerEntity::class,
        ConversationRawLedgerFts::class,
        EntityRecordEntity::class,
        EntityRelationEntity::class,
        MemoryItemEntity::class,
        TombstoneRecordEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class LichiMemoryDatabase : RoomDatabase() {

    abstract fun rawLedgerDao(): RawLedgerDao
    abstract fun entityRecordDao(): EntityRecordDao
    abstract fun entityRelationDao(): EntityRelationDao
    abstract fun memoryItemDao(): MemoryItemDao
    abstract fun tombstoneDao(): TombstoneDao

    companion object {
        private const val DB_NAME = "lichi_memory.db"

        @Volatile
        private var INSTANCE: LichiMemoryDatabase? = null

        fun getInstance(context: Context): LichiMemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LichiMemoryDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

package com.lichiai.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lichiai.memory.db.dao.ConversationSemanticVectorDao
import com.lichiai.memory.db.dao.EntityRecordDao
import com.lichiai.memory.db.dao.EntityRelationDao
import com.lichiai.memory.db.dao.MemoryItemDao
import com.lichiai.memory.db.dao.MemorySemanticVectorDao
import com.lichiai.memory.db.dao.RawLedgerDao
import com.lichiai.memory.db.dao.TombstoneDao
import com.lichiai.memory.db.entity.ConversationRawLedgerEntity
import com.lichiai.memory.db.entity.ConversationRawLedgerFts
import com.lichiai.memory.db.entity.ConversationSemanticVectorEntity
import com.lichiai.memory.db.entity.EntityRecordEntity
import com.lichiai.memory.db.entity.EntityRelationEntity
import com.lichiai.memory.db.entity.MemoryItemEntity
import com.lichiai.memory.db.entity.MemoryItemFts
import com.lichiai.memory.db.entity.MemorySemanticVectorEntity
import com.lichiai.memory.db.entity.TombstoneRecordEntity

@Database(
    entities = [
        ConversationRawLedgerEntity::class,
        ConversationRawLedgerFts::class,
        ConversationSemanticVectorEntity::class,
        EntityRecordEntity::class,
        EntityRelationEntity::class,
        MemoryItemEntity::class,
        TombstoneRecordEntity::class,
        MemorySemanticVectorEntity::class,
        MemoryItemFts::class
    ],
    version = 5,
    exportSchema = false
)
abstract class LichiMemoryDatabase : RoomDatabase() {

    abstract fun rawLedgerDao(): RawLedgerDao
    abstract fun entityRecordDao(): EntityRecordDao
    abstract fun entityRelationDao(): EntityRelationDao
    abstract fun memoryItemDao(): MemoryItemDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun memorySemanticVectorDao(): MemorySemanticVectorDao
    abstract fun conversationSemanticVectorDao(): ConversationSemanticVectorDao

    companion object {
        private const val DB_NAME = "lichi_memory.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 1 to 2 migration if upgraded from initial prototype
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_items_category` ON `memory_items` (`category`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create memory_semantic_vectors table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `memory_semantic_vectors` (
                        `memoryId` TEXT NOT NULL,
                        `embeddingInt8` BLOB NOT NULL,
                        `dimension` INTEGER NOT NULL,
                        `modelVersion` TEXT NOT NULL,
                        `quantizationVersion` INTEGER NOT NULL,
                        `indexedAt` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        PRIMARY KEY(`memoryId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_semantic_vectors_status` ON `memory_semantic_vectors` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_semantic_vectors_modelVersion` ON `memory_semantic_vectors` (`modelVersion`)")

                // 2. Create memory_items_fts virtual table
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS `memory_items_fts` USING FTS4(
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `associativeKeysJson` TEXT NOT NULL,
                        content=`memory_items`
                    )
                """.trimIndent())

                // 3. Populate FTS index from existing memory items
                runCatching {
                    db.execSQL("INSERT INTO `memory_items_fts`(`rowid`, `key`, `value`, `associativeKeysJson`) SELECT `rowid`, `key`, `value`, `associativeKeysJson` FROM `memory_items`")
                }
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add userId column to raw_conversation_ledger
                db.execSQL("ALTER TABLE `raw_conversation_ledger` ADD COLUMN `userId` TEXT NOT NULL DEFAULT 'user_primary_default'")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_conversation_ledger_userId` ON `raw_conversation_ledger` (`userId`)")

                // 2. Add userId column to memory_relations
                db.execSQL("ALTER TABLE `memory_relations` ADD COLUMN `userId` TEXT NOT NULL DEFAULT 'user_primary_default'")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relations_userId` ON `memory_relations` (`userId`)")

                // 3. Add userId column to memory_tombstones
                db.execSQL("ALTER TABLE `memory_tombstones` ADD COLUMN `userId` TEXT NOT NULL DEFAULT 'user_primary_default'")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_tombstones_userId` ON `memory_tombstones` (`userId`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add dedupeKey column to memory_items
                db.execSQL("ALTER TABLE `memory_items` ADD COLUMN `dedupeKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_items_dedupeKey` ON `memory_items` (`dedupeKey`)")

                // 2. Create conversation_semantic_vectors table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `conversation_semantic_vectors` (
                        `turnId` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `embeddingInt8` BLOB NOT NULL,
                        `dimension` INTEGER NOT NULL,
                        `modelVersion` TEXT NOT NULL,
                        `quantizationVersion` INTEGER NOT NULL,
                        `indexedAt` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        PRIMARY KEY(`turnId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_semantic_vectors_userId` ON `conversation_semantic_vectors` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_semantic_vectors_conversationId` ON `conversation_semantic_vectors` (`conversationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_semantic_vectors_status` ON `conversation_semantic_vectors` (`status`)")
            }
        }

        @Volatile
        private var INSTANCE: LichiMemoryDatabase? = null

        fun getInstance(context: Context): LichiMemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LichiMemoryDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

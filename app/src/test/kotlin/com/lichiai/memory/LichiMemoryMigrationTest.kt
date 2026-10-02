package com.lichiai.memory

import androidx.sqlite.db.SupportSQLiteDatabase
import com.lichiai.memory.db.LichiMemoryDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class LichiMemoryMigrationTest {

    @Test
    fun testMigration2To3SqlExecution() {
        val executedStatements = mutableListOf<String>()

        val dbProxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                val sql = args[0] as? String
                if (sql != null) {
                    executedStatements.add(sql)
                }
            }
            null
        } as SupportSQLiteDatabase

        LichiMemoryDatabase.MIGRATION_2_3.migrate(dbProxy)

        // Verify creation of memory_semantic_vectors and memory_items_fts
        assertTrue(
            "Must create memory_semantic_vectors table",
            executedStatements.any { it.contains("memory_semantic_vectors") }
        )
        assertTrue(
            "Must create memory_items_fts virtual table",
            executedStatements.any { it.contains("memory_items_fts") }
        )
    }

    @Test
    fun testMigration3To4SqlExecution() {
        val executedStatements = mutableListOf<String>()

        val dbProxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                val sql = args[0] as? String
                if (sql != null) {
                    executedStatements.add(sql)
                }
            }
            null
        } as SupportSQLiteDatabase

        LichiMemoryDatabase.MIGRATION_3_4.migrate(dbProxy)

        assertTrue(
            "Must add userId column to raw_conversation_ledger",
            executedStatements.any { it.contains("raw_conversation_ledger") && it.contains("userId") }
        )
        assertTrue(
            "Must add userId column to memory_relations",
            executedStatements.any { it.contains("memory_relations") && it.contains("userId") }
        )
        assertTrue(
            "Must add userId column to memory_tombstones",
            executedStatements.any { it.contains("memory_tombstones") && it.contains("userId") }
        )
    }

    @Test
    fun testMigration4To5SqlExecution() {
        val executedStatements = mutableListOf<String>()

        val dbProxy = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                val sql = args[0] as? String
                if (sql != null) {
                    executedStatements.add(sql)
                }
            }
            null
        } as SupportSQLiteDatabase

        LichiMemoryDatabase.MIGRATION_4_5.migrate(dbProxy)

        assertTrue(
            "Must add dedupeKey column to memory_items",
            executedStatements.any { it.contains("memory_items") && it.contains("dedupeKey") }
        )
        assertTrue(
            "Must create conversation_semantic_vectors table",
            executedStatements.any { it.contains("conversation_semantic_vectors") }
        )
    }
}

package com.lichiai.memory.intelligence

import com.lichiai.memory.db.entity.MemoryItemEntity
import com.lichiai.memory.engine.AmnesiaTombstoneManager
import com.lichiai.memory.model.BiTemporalWindow
import com.lichiai.memory.model.MemoryCategory
import com.lichiai.memory.model.MemoryItem
import com.lichiai.memory.model.MemoryStatus
import com.lichiai.memory.model.TemporalIntent
import com.lichiai.memory.model.TrustLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class MemoryIntelligenceTest {

    // =========================================================================
    // 1. QUERY ROUTING & FAST-PATH TESTS (Section 21, 22, 51)
    // =========================================================================
    @Test
    fun testQueryRouterDirectNameLookup() {
        val decision = MemoryQueryRouter.routeQuery("mera naam kya hai?")
        assertEquals(MemoryQueryCategory.DIRECT_LOOKUP, decision.primaryCategory)
        assertEquals("user_name", decision.directLookupKey)
        assertTrue(decision.isTrivialDirectLookup)
        assertFalse(decision.requiresSemanticSearch)
    }

    @Test
    fun testQueryRouterDirectResidenceLookup() {
        val decision = MemoryQueryRouter.routeQuery("main abhi kahan rehta hoon?")
        assertEquals(MemoryQueryCategory.DIRECT_LOOKUP, decision.primaryCategory)
        assertEquals("user_residence", decision.directLookupKey)
        assertEquals(TemporalIntent.CURRENT, decision.temporalIntent)
        assertTrue(decision.isTrivialDirectLookup)
    }

    @Test
    fun testQueryRouterHistoricalResidenceLookup() {
        val decision = MemoryQueryRouter.routeQuery("main pehle kahan rehta tha?")
        assertEquals(MemoryQueryCategory.HISTORICAL_RECALL, decision.primaryCategory)
        assertEquals(TemporalIntent.HISTORICAL, decision.temporalIntent)
        assertEquals("user_residence", decision.directLookupKey)
        assertTrue(decision.requiresSemanticSearch)
    }

    @Test
    fun testQueryRouterTechnicalLexicalLookup() {
        val decision = MemoryQueryRouter.routeQuery("LICHI project ke browser mein generation ID fix kya tha")
        assertTrue(
            decision.primaryCategory == MemoryQueryCategory.LEXICAL_RECALL ||
                    decision.primaryCategory == MemoryQueryCategory.SEMANTIC_RECALL
        )
        assertTrue(decision.requiresSemanticSearch)
        assertTrue(decision.requiresLexicalSearch)
    }

    @Test
    fun testQueryRouterRawLedgerRecall() {
        val decision = MemoryQueryRouter.routeQuery("mujhe woh conversation yaad hai jahan humne test kiya tha")
        assertEquals(MemoryQueryCategory.RAW_LEDGER_RECALL, decision.primaryCategory)
        assertTrue(decision.requiresLedgerSearch)
    }

    // =========================================================================
    // 2. VECTOR QUANTIZATION & MATH (Section 9, 49)
    // =========================================================================
    @Test
    fun testVectorQuantizationAndDequantization() {
        val floats = FloatArray(256) { i ->
            // values within [-0.3, 0.3]
            (i % 10 - 5) * 0.05f
        }

        val quantized = MemoryModelConfig.quantizeVector(floats)
        assertEquals(256, quantized.size)

        val dequantized = MemoryModelConfig.dequantizeVector(quantized)
        assertEquals(256, dequantized.size)

        // Verify cosine similarity between original and reconstructed is very high
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until 256) {
            dot += floats[i] * dequantized[i]
            normA += floats[i] * floats[i]
            normB += dequantized[i] * dequantized[i]
        }
        val cos = dot / (Math.sqrt(normA) * Math.sqrt(normB))
        assertTrue("Reconstruction cosine similarity $cos must be > 0.98", cos > 0.98)
    }

    @Test
    fun testInt8CosineSimilarityIdenticalVectors() {
        val floats = FloatArray(256) { 0.15f }
        val vec1 = MemoryModelConfig.quantizeVector(floats)
        val vec2 = MemoryModelConfig.quantizeVector(floats)

        val sim = MemoryModelConfig.int8CosineSimilarity(vec1, vec2)
        assertTrue("Identical vectors similarity should be close to 1.0 (was $sim)", sim > 0.99f)
    }

    // =========================================================================
    // 3. SCORE FUSION & NORMALIZATION (Section 26, 27)
    // =========================================================================
    @Test
    fun testScoreEngineWeightsSumToOne() {
        val sum = MemoryScoreEngine.WEIGHT_SEMANTIC +
                MemoryScoreEngine.WEIGHT_LEXICAL +
                MemoryScoreEngine.WEIGHT_ENTITY +
                MemoryScoreEngine.WEIGHT_TEMPORAL +
                MemoryScoreEngine.WEIGHT_TRUST +
                MemoryScoreEngine.WEIGHT_SALIENCE

        assertEquals(1.00f, sum, 0.0001f)
    }

    @Test
    fun testTrustScoreHierarchy() {
        val explicit = MemoryScoreEngine.getTrustScore(TrustLevel.USER_EXPLICIT)
        val tool = MemoryScoreEngine.getTrustScore(TrustLevel.TOOL_VERIFIED)
        val inferred = MemoryScoreEngine.getTrustScore(TrustLevel.MODEL_INFERRED)
        val unknown = MemoryScoreEngine.getTrustScore(TrustLevel.UNKNOWN)

        assertTrue(explicit > tool)
        assertTrue(tool > inferred)
        assertTrue(inferred > unknown)
    }

    // =========================================================================
    // 4. IN-PROCESS HNSW VECTOR INDEX (Section 17, 18)
    // =========================================================================
    @Test
    fun testVectorIndexInsertSearchPersistenceReload() {
        val index = MemoryVectorIndex(dimension = 256)
        val vec1 = MemoryModelConfig.quantizeVector(FloatArray(256) { 0.2f })
        val vec2 = MemoryModelConfig.quantizeVector(FloatArray(256) { -0.2f })
        val vec3 = MemoryModelConfig.quantizeVector(FloatArray(256) { if (it % 2 == 0) 0.2f else -0.2f })

        index.insert("mem_1", vec1)
        index.insert("mem_2", vec2)
        index.insert("mem_3", vec3)

        assertEquals(3, index.size())

        // Search near vec1
        val searchResults = index.search(vec1, topK = 2)
        assertTrue(searchResults.isNotEmpty())
        assertEquals("mem_1", searchResults[0].first)

        // Test delete
        index.delete("mem_2")
        val searchAfterDelete = index.search(vec2, topK = 5)
        assertFalse(searchAfterDelete.any { it.first == "mem_2" })

        // Test Persistence & Reload
        val tempFile = File.createTempFile("hnsw_test", ".bin")
        try {
            val saved = index.persist(tempFile)
            assertTrue("Index persist must succeed", saved)

            val reloadedIndex = MemoryVectorIndex(dimension = 256)
            val loaded = reloadedIndex.load(tempFile)
            assertTrue("Index load must succeed", loaded)
            assertEquals(2, reloadedIndex.size())

            val reloadedSearch = reloadedIndex.search(vec1, topK = 1)
            assertEquals("mem_1", reloadedSearch[0].first)
        } finally {
            tempFile.delete()
        }
    }

    // =========================================================================
    // 5. TOMBSTONE & TEMPORAL BARRIER TESTS (Section 28, 29, 71, 72)
    // =========================================================================
    @Test
    fun testTombstoneBarrierRejection() {
        val dummyTombstoneDao = object : com.lichiai.memory.db.dao.TombstoneDao {
            val list = mutableListOf<com.lichiai.memory.db.entity.TombstoneRecordEntity>()
            override suspend fun insert(tombstone: com.lichiai.memory.db.entity.TombstoneRecordEntity) { list.add(tombstone) }
            override suspend fun getAllTombstones() = list
            override suspend fun getAllTombstonesForUser(userId: String) = list.filter { it.userId == userId }
            override fun getTombstonesFlow() = kotlinx.coroutines.flow.flowOf(list)
            override suspend fun countTombstone(identifier: String) = list.count { it.targetIdentifier.equals(identifier, true) }
            override suspend fun countTombstoneForUser(identifier: String, userId: String) = list.count { it.targetIdentifier.equals(identifier, true) && it.userId == userId }
            override suspend fun deleteTombstone(identifier: String) { list.removeAll { it.targetIdentifier.equals(identifier, true) } }
            override suspend fun deleteTombstoneForUser(identifier: String, userId: String) { list.removeAll { it.targetIdentifier.equals(identifier, true) && it.userId == userId } }
            override suspend fun deleteByScopeConversationId(conversationId: String) { list.removeAll { it.scopeConversationId == conversationId } }
        }

        val tombstoneManager = AmnesiaTombstoneManager(
            tombstoneDao = dummyTombstoneDao,
            memoryItemDao = object : com.lichiai.memory.db.dao.MemoryItemDao {
                override suspend fun upsert(item: MemoryItemEntity) {}
                override suspend fun upsertAll(items: List<MemoryItemEntity>) {}
                override suspend fun getById(id: String): MemoryItemEntity? = null
                override fun getActiveItemsFlow() = kotlinx.coroutines.flow.flowOf(emptyList<MemoryItemEntity>())
                override suspend fun getActiveItems() = emptyList<MemoryItemEntity>()
                override suspend fun getActiveByCategory(category: String) = emptyList<MemoryItemEntity>()
                override suspend fun searchItems(query: String, limit: Int) = emptyList<MemoryItemEntity>()
                override suspend fun getActiveByKey(key: String) = emptyList<MemoryItemEntity>()
                override suspend fun getHistoricalByKey(key: String) = emptyList<MemoryItemEntity>()
                override suspend fun getAllValidAndHistoricalItems() = emptyList<MemoryItemEntity>()
                override suspend fun getActiveItemsForUser(userId: String) = emptyList<MemoryItemEntity>()
                override suspend fun getAllItemsForUser(userId: String) = emptyList<MemoryItemEntity>()
                override suspend fun getAllHistoricalItems() = emptyList<MemoryItemEntity>()
                override suspend fun getHistoricalItemsForUser(userId: String) = emptyList<MemoryItemEntity>()
                override suspend fun supersedeActiveKey(key: String, supersededAt: Long) {}
                override suspend fun supersedeActiveKeyForUser(key: String, userId: String, supersededAt: Long) {}
                override suspend fun markTombstoneByKey(key: String) {}
                override suspend fun markTombstoneByKeyForUser(key: String, userId: String) {}
                override suspend fun getActiveByKeyForUser(key: String, userId: String, limit: Int) = emptyList<MemoryItemEntity>()
                override suspend fun getHistoricalByKeyForUser(key: String, userId: String, limit: Int) = emptyList<MemoryItemEntity>()
                override suspend fun getActiveByCategoryForUser(category: String, userId: String, limit: Int) = emptyList<MemoryItemEntity>()
                override suspend fun searchActiveFtsForUser(searchQuery: String, userId: String, limit: Int) = emptyList<MemoryItemEntity>()
                override suspend fun getActiveProfileItems(userId: String) = emptyList<MemoryItemEntity>()
                override suspend fun getByIds(ids: List<String>) = emptyList<MemoryItemEntity>()
                override suspend fun getByIdsForUser(ids: List<String>, userId: String) = emptyList<MemoryItemEntity>()
                override suspend fun getActiveByDedupeKeyForUser(dedupeKey: String, userId: String) = emptyList<MemoryItemEntity>()
                override suspend fun deleteByConversationId(conversationId: String) {}
                override suspend fun deleteBySourceMessageId(messageId: String) {}
                override suspend fun getBySourceMessageId(messageId: String): List<MemoryItemEntity> = emptyList()
                override suspend fun getByConversationId(conversationId: String): List<MemoryItemEntity> = emptyList()
            },
            entityRecordDao = object : com.lichiai.memory.db.dao.EntityRecordDao {
                override suspend fun upsert(entity: com.lichiai.memory.db.entity.EntityRecordEntity) {}
                override suspend fun upsertAll(entities: List<com.lichiai.memory.db.entity.EntityRecordEntity>) {}
                override suspend fun update(entity: com.lichiai.memory.db.entity.EntityRecordEntity) {}
                override suspend fun getById(entityId: String): com.lichiai.memory.db.entity.EntityRecordEntity? = null
                override fun getActiveEntitiesFlow() = kotlinx.coroutines.flow.flowOf(emptyList<com.lichiai.memory.db.entity.EntityRecordEntity>())
                override suspend fun getActiveEntities() = emptyList<com.lichiai.memory.db.entity.EntityRecordEntity>()
                override suspend fun getActiveEntitiesForUser(userId: String) = emptyList<com.lichiai.memory.db.entity.EntityRecordEntity>()
                override suspend fun searchEntities(query: String, limit: Int) = emptyList<com.lichiai.memory.db.entity.EntityRecordEntity>()
                override suspend fun findByCanonicalName(canonicalName: String): com.lichiai.memory.db.entity.EntityRecordEntity? = null
                override suspend fun findByCanonicalNameForUser(canonicalName: String, userId: String): com.lichiai.memory.db.entity.EntityRecordEntity? = null
                override suspend fun updateStatus(entityId: String, newStatus: String) {}
                override suspend fun markTombstoneByName(targetName: String) {}
                override suspend fun markTombstoneByNameForUser(targetName: String, userId: String) {}
                override suspend fun getByType(type: String) = emptyList<com.lichiai.memory.db.entity.EntityRecordEntity>()
                override suspend fun getByTypeForUser(type: String, userId: String) = emptyList<com.lichiai.memory.db.entity.EntityRecordEntity>()
                override suspend fun searchEntitiesForUser(query: String, userId: String, limit: Int) = emptyList<com.lichiai.memory.db.entity.EntityRecordEntity>()
                override suspend fun getByIds(ids: List<String>) = emptyList<com.lichiai.memory.db.entity.EntityRecordEntity>()
                override suspend fun deleteByConversationId(conversationId: String) {}
            },
            entityRelationDao = object : com.lichiai.memory.db.dao.EntityRelationDao {
                override suspend fun upsert(relation: com.lichiai.memory.db.entity.EntityRelationEntity) {}
                override suspend fun upsertAll(relations: List<com.lichiai.memory.db.entity.EntityRelationEntity>) {}
                override suspend fun getRelationsForEntity(entityId: String) = emptyList<com.lichiai.memory.db.entity.EntityRelationEntity>()
                override suspend fun getRelationsForEntity(entityId: String, userId: String) = emptyList<com.lichiai.memory.db.entity.EntityRelationEntity>()
                override suspend fun getAllActiveRelations() = emptyList<com.lichiai.memory.db.entity.EntityRelationEntity>()
                override suspend fun getAllActiveRelationsForUser(userId: String) = emptyList<com.lichiai.memory.db.entity.EntityRelationEntity>()
                override suspend fun updateStatus(relationId: String, newStatus: String) {}
                override suspend fun markTombstoneForEntity(entityId: String) {}
                override suspend fun markTombstoneForEntity(entityId: String, userId: String) {}
            }
        )

        kotlinx.coroutines.runBlocking {
            tombstoneManager.executeForget("Rahul")
        }

        val navigator = MemoryNavigator(tombstoneManager)
        val candidate = MemoryCandidate(
            entity = MemoryItemEntity(
                id = "mem_tomb_1",
                key = "user_name",
                value = "Rahul",
                category = "FACT",
                status = "ACTIVE"
            ),
            semanticScore = 0.99f
        )

        val ranked = navigator.navigateAndRank(
            candidates = listOf(candidate),
            decision = MemoryQueryRouter.routeQuery("mera naam kya hai")
        )

        assertTrue("Tombstoned memory 'Rahul' must NOT be returned in ranked results", ranked.isEmpty())
    }
}

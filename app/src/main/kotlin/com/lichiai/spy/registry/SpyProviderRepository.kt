package com.lichiai.spy.registry

import android.content.Context
import android.util.Log
import com.lichiai.spy.apify.ActorIdentifierResolver
import com.lichiai.spy.apify.ApifyClient
import com.lichiai.spy.apify.ApifyStoreItem
import com.lichiai.spy.apify.ApifyStorePage
import com.lichiai.spy.core.SpyError
import com.lichiai.spy.discovery.PlatformCapabilityInferencer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SpyProviderRepository(
    private val context: Context,
    private val apifyClient: ApifyClient
) {
    companion object {
        private const val TAG = "SpyProviderRepo"

        @Volatile
        private var INSTANCE: SpyProviderRepository? = null

        fun getInstance(context: Context, apifyClient: ApifyClient): SpyProviderRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpyProviderRepository(context.applicationContext, apifyClient).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val db = SpyProviderDatabase.getInstance(context)
    private val dao = db.providerDao()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    val providersFlow: Flow<List<SpyProviderEntity>> = dao.getAllProvidersFlow()

    suspend fun getAllProviders(): List<SpyProviderEntity> = withContext(Dispatchers.IO) {
        dao.getAllProviders()
    }

    suspend fun getEnabledProviders(): List<SpyProviderEntity> = withContext(Dispatchers.IO) {
        dao.getEnabledProviders()
    }

    suspend fun getProvider(id: String): SpyProviderEntity? = withContext(Dispatchers.IO) {
        val canonical = ActorIdentifierResolver.toCanonicalApiId(id)
        dao.getProviderById(canonical) ?: dao.getProviderById(id)
    }

    /**
     * Searches the Apify Store dynamically with pagination and merges results with local database.
     * Preserves local enabled states, cached schemas, and execution health metrics.
     */
    suspend fun searchStore(
        query: String,
        limit: Int = 20,
        offset: Int = 0,
        sortBy: String = "popularity"
    ): Result<ApifyStorePage> = withContext(Dispatchers.IO) {
        val storeResult = apifyClient.searchStorePage(
            query = query,
            limit = limit,
            offset = offset,
            sortBy = sortBy,
            includeUnrunnableActors = true
        )

        if (storeResult.isFailure) {
            return@withContext Result.failure(storeResult.exceptionOrNull() ?: Exception("Store search failed"))
        }

        val page = storeResult.getOrThrow()
        val localProviders = dao.getAllProviders().associateBy { it.canonicalActorId }

        val newEntities = mutableListOf<SpyProviderEntity>()
        val mergedItems = mutableListOf<ApifyStoreItem>()

        for (item in page.items) {
            val canonicalId = if (item.username.isNotBlank()) {
                ActorIdentifierResolver.toCanonicalApiId("${item.username}~${item.name}")
            } else {
                ActorIdentifierResolver.toCanonicalApiId(item.id)
            }

            mergedItems.add(item)

            val existing = localProviders[canonicalId]
            if (existing == null) {
                // Infer platforms & capabilities from initial metadata
                val platforms = PlatformCapabilityInferencer.inferPlatforms(
                    name = item.name,
                    title = item.title,
                    description = item.description,
                    readme = null,
                    categories = item.categories
                )
                val caps = PlatformCapabilityInferencer.inferCapabilities(
                    name = item.name,
                    title = item.title,
                    description = item.description,
                    readme = null,
                    categories = item.categories
                )

                val entity = SpyProviderEntity(
                    providerId = canonicalId,
                    canonicalActorId = canonicalId,
                    actorUsername = item.username,
                    actorName = item.name,
                    title = item.title.ifBlank { item.name },
                    description = item.description,
                    pictureUrl = item.pictureUrl,
                    storeUrl = "https://apify.com/${item.username.ifBlank { "apify" }}/${item.name}",
                    platformKeysJson = json.encodeToString(platforms.toList()),
                    capabilitiesJson = json.encodeToString(caps.map { it.name }),
                    enabled = false,
                    runnableState = if (item.isUnrunnable == true) "UNRUNNABLE" else "RUNNABLE",
                    pricingModel = item.pricingModel ?: item.currentPricing?.pricingModel,
                    priceUsd = item.currentPricing?.priceUsd,
                    trialMinutes = item.currentPricing?.trialMinutes,
                    lastDiscoveredAt = System.currentTimeMillis()
                )
                newEntities.add(entity)
            }
        }

        if (newEntities.isNotEmpty()) {
            dao.insertOrUpdateAll(newEntities)
        }

        Result.success(page.copy(items = mergedItems))
    }

    /**
     * Enables an Actor. Fetches and parses schema atomically if not already cached.
     * If schema retrieval fails, provider remains DISABLED and an error is returned.
     */
    suspend fun enableProvider(providerId: String, forceRefreshSchema: Boolean = false): Result<SpyProviderEntity> = withContext(Dispatchers.IO) {
        val canonicalId = ActorIdentifierResolver.toCanonicalApiId(providerId)
        val existing = dao.getProviderById(canonicalId) ?: dao.getProviderById(providerId)

        if (existing != null && existing.hasValidSchema() && !forceRefreshSchema) {
            val updated = existing.copy(enabled = true)
            dao.insertOrUpdate(updated)
            return@withContext Result.success(updated)
        }

        // Fetch schema
        val fetchResult = fetchAndApplySchema(existing ?: createStubEntity(canonicalId))
        if (fetchResult.isFailure) {
            val err = fetchResult.exceptionOrNull()?.message ?: "Schema unavailable"
            Log.w(TAG, "Failed enabling provider $canonicalId: $err")
            return@withContext Result.failure(SpyError.SchemaUnavailable(canonicalId, err))
        }

        val withSchema = fetchResult.getOrThrow()
        val enabledEntity = withSchema.copy(enabled = true)
        dao.insertOrUpdate(enabledEntity)
        Log.i(TAG, "Successfully enabled provider $canonicalId with valid schema")
        Result.success(enabledEntity)
    }

    suspend fun disableProvider(providerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val canonicalId = ActorIdentifierResolver.toCanonicalApiId(providerId)
        dao.setEnabled(canonicalId, false)
        dao.setEnabled(providerId, false)
        Log.i(TAG, "Disabled provider $canonicalId")
        Result.success(Unit)
    }

    suspend fun refreshSchema(providerId: String): Result<SpyProviderEntity> = withContext(Dispatchers.IO) {
        val canonicalId = ActorIdentifierResolver.toCanonicalApiId(providerId)
        val existing = dao.getProviderById(canonicalId) ?: dao.getProviderById(providerId)
            ?: return@withContext Result.failure(SpyError.ActorNotFound(canonicalId))

        val fetchResult = fetchAndApplySchema(existing)
        if (fetchResult.isSuccess) {
            val updated = fetchResult.getOrThrow()
            dao.insertOrUpdate(updated)
            Result.success(updated)
        } else {
            Result.failure(fetchResult.exceptionOrNull() ?: Exception("Schema refresh failed"))
        }
    }

    private suspend fun fetchAndApplySchema(target: SpyProviderEntity): Result<SpyProviderEntity> {
        val canonicalId = target.canonicalActorId
        var inputSchemaJson: String? = null
        var exampleInputJson: String? = null
        var outputSchemaJson: String? = null
        var readme: String? = null
        var buildId: String? = null
        var buildNumber: String? = null
        var title = target.title
        var description = target.description
        var pricingModel = target.pricingModel
        var priceUsd = target.priceUsd

        // 1. Try GET /v2/acts/:actorId/builds/default
        val buildResult = apifyClient.getActorDefaultBuild(canonicalId)
        if (buildResult.isSuccess) {
            val build = buildResult.getOrThrow()
            buildId = build.id
            buildNumber = build.buildNumber
            readme = build.readme

            val actorDef = build.actorDefinition
            if (actorDef != null) {
                actorDef["title"]?.let { title = it.toString().trim('"') }
                actorDef["description"]?.let { description = it.toString().trim('"') }
                actorDef["input"]?.let { inputSchemaJson = it.toString() }
            }

            if (inputSchemaJson.isNullOrBlank() && build.inputSchema != null) {
                inputSchemaJson = build.inputSchema.toString()
            }

            if (build.output != null) {
                outputSchemaJson = build.output.toString()
            }
        }

        // 2. Fallback to GET /v2/acts/:actorId
        val detailResult = apifyClient.getActorDetail(canonicalId)
        if (detailResult.isSuccess) {
            val detail = detailResult.getOrThrow()
            if (title.isBlank()) title = detail.title
            if (description.isBlank()) description = detail.description
            if (readme.isNullOrBlank()) readme = detail.readme
            pricingModel = detail.pricingModel ?: detail.currentPricing?.pricingModel ?: pricingModel
            priceUsd = detail.currentPricing?.priceUsd ?: priceUsd

            if (detail.exampleRunInput?.body != null) {
                exampleInputJson = detail.exampleRunInput.body
            }
        }

        if (inputSchemaJson.isNullOrBlank() && exampleInputJson.isNullOrBlank()) {
            return Result.failure(SpyError.SchemaUnavailable(canonicalId, "No input schema or example input found"))
        }

        // Infer platforms and capabilities with full schema awareness
        val parsedInputSchema = try {
            if (!inputSchemaJson.isNullOrBlank()) json.parseToJsonElement(inputSchemaJson!!).jsonObject else null
        } catch (_: Exception) { null }

        val platforms = PlatformCapabilityInferencer.inferPlatforms(
            name = target.actorName,
            title = title,
            description = description,
            readme = readme,
            inputSchemaJson = inputSchemaJson ?: exampleInputJson
        )

        val caps = PlatformCapabilityInferencer.inferCapabilities(
            name = target.actorName,
            title = title,
            description = description,
            readme = readme,
            inputSchema = parsedInputSchema
        )

        val fingerprint = PlatformCapabilityInferencer.calculateFingerprint(inputSchemaJson ?: exampleInputJson)

        val updated = target.copy(
            title = title.ifBlank { target.title },
            description = description.ifBlank { target.description },
            inputSchemaJson = inputSchemaJson ?: target.inputSchemaJson,
            exampleInputJson = exampleInputJson ?: target.exampleInputJson,
            outputSchemaJson = outputSchemaJson ?: target.outputSchemaJson,
            schemaFingerprint = fingerprint,
            schemaVersion = buildNumber ?: target.schemaVersion,
            buildId = buildId ?: target.buildId,
            buildNumber = buildNumber ?: target.buildNumber,
            platformKeysJson = json.encodeToString(platforms.toList()),
            capabilitiesJson = json.encodeToString(caps.map { it.name }),
            pricingModel = pricingModel,
            priceUsd = priceUsd,
            lastSchemaFetchedAt = System.currentTimeMillis()
        )

        return Result.success(updated)
    }

    suspend fun recordExecution(
        providerId: String,
        isSuccess: Boolean,
        latencyMs: Long
    ) = withContext(Dispatchers.IO) {
        val canonicalId = ActorIdentifierResolver.toCanonicalApiId(providerId)
        val entity = dao.getProviderById(canonicalId) ?: dao.getProviderById(providerId) ?: return@withContext

        val newSuccessCount = if (isSuccess) entity.successCount + 1 else entity.successCount
        val newFailureCount = if (!isSuccess) entity.failureCount + 1 else entity.failureCount
        val totalRuns = newSuccessCount + newFailureCount
        val newAvgLatency = if (totalRuns > 0) {
            (entity.averageLatencyMs * (totalRuns - 1) + latencyMs) / totalRuns
        } else latencyMs

        val updated = entity.copy(
            successCount = newSuccessCount,
            failureCount = newFailureCount,
            lastSuccessAt = if (isSuccess) System.currentTimeMillis() else entity.lastSuccessAt,
            lastFailureAt = if (!isSuccess) System.currentTimeMillis() else entity.lastFailureAt,
            lastLatencyMs = latencyMs,
            averageLatencyMs = newAvgLatency
        )
        dao.insertOrUpdate(updated)
    }

    suspend fun clearDisabledCache() = withContext(Dispatchers.IO) {
        dao.clearDisabledCache()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }

    private fun createStubEntity(canonicalId: String): SpyProviderEntity {
        val parts = canonicalId.split("~", "/")
        val username = if (parts.size > 1) parts[0] else ""
        val name = if (parts.size > 1) parts[1] else parts[0]
        return SpyProviderEntity(
            providerId = canonicalId,
            canonicalActorId = canonicalId,
            actorUsername = username,
            actorName = name,
            title = name,
            description = "",
            lastDiscoveredAt = System.currentTimeMillis()
        )
    }
}

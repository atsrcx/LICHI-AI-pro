package com.lichiai.spy.registry

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
@Entity(tableName = "spy_providers")
data class SpyProviderEntity(
    @PrimaryKey
    val providerId: String,
    val canonicalActorId: String,
    val actorUsername: String,
    val actorName: String,
    val title: String,
    val description: String,
    val pictureUrl: String? = null,
    val storeUrl: String? = null,
    val platformKeysJson: String = "[]",
    val capabilitiesJson: String = "[]",
    val enabled: Boolean = false,
    val runnableState: String = "RUNNABLE", // "RUNNABLE" | "UNRUNNABLE" | "UNKNOWN"
    val pricingModel: String? = null,
    val priceUsd: Double? = null,
    val trialMinutes: Int? = null,
    val inputSchemaJson: String? = null,
    val exampleInputJson: String? = null,
    val outputSchemaJson: String? = null,
    val datasetSchemaJson: String? = null,
    val schemaFingerprint: String? = null,
    val schemaVersion: String? = null,
    val buildId: String? = null,
    val buildNumber: String? = null,
    val lastDiscoveredAt: Long = System.currentTimeMillis(),
    val lastSchemaFetchedAt: Long? = null,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val successCount: Long = 0,
    val failureCount: Long = 0,
    val averageLatencyMs: Long = 0,
    val lastLatencyMs: Long = 0,
    val selectionPriority: Int = 0
) {
    val platformKeys: List<String>
        get() = try {
            if (platformKeysJson.isNotBlank()) Json.decodeFromString<List<String>>(platformKeysJson) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    val capabilities: List<String>
        get() = try {
            if (capabilitiesJson.isNotBlank()) Json.decodeFromString<List<String>>(capabilitiesJson) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    fun hasValidSchema(): Boolean {
        return !inputSchemaJson.isNullOrBlank() || !exampleInputJson.isNullOrBlank()
    }
}

package com.lichiai.spy.apify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ApifyErrorResponse(
    val error: ApifyErrorDetails? = null
)

@Serializable
data class ApifyErrorDetails(
    val type: String? = null,
    val message: String? = null
)

@Serializable
data class ApifyUserResponse(
    val data: ApifyUserData? = null
)

@Serializable
data class ApifyUserData(
    val id: String = "",
    val username: String = "",
    val email: String? = null,
    val plan: ApifyUserPlan? = null
)

@Serializable
data class ApifyUserPlan(
    val id: String = "",
    val name: String = "",
    val isMonthly: Boolean = true
)

@Serializable
data class ApifyStoreResponse(
    val data: ApifyStoreData? = null
)

@Serializable
data class ApifyStoreData(
    val total: Int = 0,
    val count: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val items: List<ApifyStoreItem> = emptyList()
)

@Serializable
data class ApifyStoreItem(
    val id: String = "",
    val name: String = "",
    val username: String = "",
    val title: String = "",
    val description: String = "",
    val pricingModel: String? = null,
    val stats: ApifyActorStats? = null,
    val isPublic: Boolean = true,
    val currentPricing: ApifyCurrentPricing? = null,
    val pictureUrl: String? = null
)

@Serializable
data class ApifyActorStats(
    val totalRuns: Long = 0,
    val totalUsers7Days: Long = 0,
    val totalUsers30Days: Long = 0,
    val totalUsers90Days: Long = 0,
    val bookmarkCount: Long = 0
)

@Serializable
data class ApifyCurrentPricing(
    val pricingModel: String? = null,
    val priceUsd: Double? = null,
    val trialMinutes: Int? = null
)

@Serializable
data class ApifyActorDetailResponse(
    val data: ApifyActorDetail? = null
)

@Serializable
data class ApifyActorDetail(
    val id: String = "",
    val name: String = "",
    val username: String = "",
    val title: String = "",
    val description: String = "",
    val readme: String? = null,
    val pricingModel: String? = null,
    val currentPricing: ApifyCurrentPricing? = null,
    val stats: ApifyActorStats? = null,
    val defaultRunOptions: ApifyDefaultRunOptions? = null,
    val exampleRunInput: ApifyExampleRunInput? = null
)

@Serializable
data class ApifyDefaultRunOptions(
    val build: String? = null,
    val timeoutSecs: Long? = null,
    val memoryMbytes: Long? = null
)

@Serializable
data class ApifyExampleRunInput(
    val body: String? = null,
    val contentType: String? = null
)

@Serializable
data class ApifyRunResponse(
    val data: ApifyRunData? = null
)

@Serializable
data class ApifyRunData(
    val id: String = "",
    val actId: String = "",
    val userId: String = "",
    val status: String = "",
    val startedAt: String = "",
    val finishedAt: String? = null,
    val defaultDatasetId: String? = null,
    val defaultKeyValueStoreId: String? = null,
    val defaultRequestQueueId: String? = null,
    val exitCode: Int? = null,
    val containerUrl: String? = null,
    val usage: JsonObject? = null
)

@Serializable
data class ApifyKeyValueStoreKeysResponse(
    val data: ApifyKeyValueStoreKeysData? = null
)

@Serializable
data class ApifyKeyValueStoreKeysData(
    val total: Int = 0,
    val count: Int = 0,
    val items: List<ApifyKeyItem> = emptyList()
)

@Serializable
data class ApifyKeyItem(
    val key: String = "",
    val size: Long = 0
)

package com.lichiai.spy.discovery

import kotlinx.serialization.Serializable

@Serializable
data class ActorMetadata(
    val actorId: String,
    val name: String,
    val username: String = "",
    val title: String = "",
    val description: String = "",
    val readme: String? = null,
    val isFree: Boolean = true,
    val pricingModel: String? = null,
    val priceUsd: Double? = null,
    val totalRuns: Long = 0,
    val bookmarkCount: Long = 0,
    val exampleInputJson: String? = null,
    val retrievedAt: Long = System.currentTimeMillis()
)

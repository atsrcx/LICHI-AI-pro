package com.lichiai.spy.model

import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyOperation
import kotlinx.serialization.Serializable

@Serializable
enum class PlatformCategory {
    SOCIAL,
    VIDEO,
    MUSIC,
    MESSAGING,
    DEVELOPER,
    FORUM,
    BLOG,
    BUSINESS,
    MEDIA,
    UNIVERSAL
}

@Serializable
enum class PlatformSupportStatus {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED
}

@Serializable
data class PlatformDefinition(
    val platformType: PlatformType,
    val displayName: String,
    val category: PlatformCategory,
    val supportStatus: PlatformSupportStatus,
    val aliases: List<String> = emptyList(),
    val websiteDomain: String = "",
    val preferredActorHints: List<String> = emptyList(),
    val supportedOperations: List<SpyOperation> = listOf(SpyOperation.PROFILE_LOOKUP),
    val profileFields: List<String> = emptyList(),
    val previewCapabilities: List<String> = emptyList(),
    val iconName: String = ""
)

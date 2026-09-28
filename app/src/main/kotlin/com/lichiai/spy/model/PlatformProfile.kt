package com.lichiai.spy.model

import com.lichiai.spy.core.PlatformType
import kotlinx.serialization.Serializable

@Serializable
data class PlatformMediaItem(
    val id: String = "",
    val thumbnailUrl: String = "",
    val mediaUrl: String = "",
    val caption: String = "",
    val type: String = "image", // "image" | "video" | "reel" | "post"
    val likesCount: String = "",
    val commentsCount: String = "",
    val timestamp: String = ""
)

@Serializable
data class PublicContactInfo(
    val email: String = "",
    val phone: String = "",
    val type: String = "Public Business Contact",
    val sourcePlatform: String = "",
    val confidence: String = "Source-Verified"
)

@Serializable
data class PlatformProfile(
    val platform: PlatformType = PlatformType.GENERIC_WEB,
    val platformId: String = "",
    val username: String = "",
    val displayName: String = "",
    val profileUrl: String = "",
    val avatarUrl: String = "",
    val coverImageUrl: String = "",
    val isVerified: Boolean? = null,
    val isPrivate: Boolean? = null,
    val category: String = "",
    val bio: String = "",
    val website: String = "",
    val location: String = "",
    val followers: String = "",
    val following: String = "",
    val postCount: String = "",
    val videoCount: String = "",
    val subscriberCount: String = "",
    val views: String = "",
    val joinedDate: String = "",
    val recentMedia: List<PlatformMediaItem> = emptyList(),
    val recentPosts: List<String> = emptyList(),
    val recentVideos: List<String> = emptyList(),
    val publicLinks: List<String> = emptyList(),
    val publicEmail: String = "",
    val publicPhone: String = "",
    val highlights: List<String> = emptyList(),
    val rawJsonSnippet: String = "",
    val sourceConfidence: String = "Verified Public Data",
    val scraperError: String? = null,
    val previewRequested: Boolean = true
) {
    /**
     * Checks if this entity contains verified, genuine extracted profile data.
     * Prevents empty or metadata-only stubs from being treated as scraped success.
     */
    fun hasGenuineData(): Boolean {
        if (!scraperError.isNullOrBlank()) return false
        val hasStats = followers.isNotBlank() || following.isNotBlank() || postCount.isNotBlank() || subscriberCount.isNotBlank() || views.isNotBlank()
        val hasBio = bio.isNotBlank()
        val hasTitle = displayName.isNotBlank() && displayName != username
        val hasWebsite = website.isNotBlank()
        val hasEmail = publicEmail.isNotBlank()
        val hasPhone = publicPhone.isNotBlank()
        val hasAvatar = avatarUrl.isNotBlank()
        val hasMedia = recentMedia.isNotEmpty()
        val hasHighlights = highlights.isNotEmpty()
        val hasVerifiedStatus = isVerified != null
        val hasPrivateStatus = isPrivate != null

        return hasStats || hasBio || hasTitle || hasWebsite || hasEmail || hasPhone || hasAvatar || hasMedia || hasHighlights || hasVerifiedStatus || hasPrivateStatus
    }
}

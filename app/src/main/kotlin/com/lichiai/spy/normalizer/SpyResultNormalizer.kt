package com.lichiai.spy.normalizer

import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.model.PlatformMediaItem
import com.lichiai.spy.model.PlatformProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class NormalizedEntity(
    val title: String = "",
    val identifier: String = "",
    val bioOrDescription: String = "",
    val statistics: Map<String, String> = emptyMap(),
    val directUrl: String = "",
    val avatarUrl: String = "",
    val coverImageUrl: String = "",
    val isVerified: Boolean? = null,
    val isPrivate: Boolean? = null,
    val category: String = "",
    val websiteUrl: String = "",
    val publicEmail: String = "",
    val publicPhone: String = "",
    val location: String = "",
    val recentMedia: List<PlatformMediaItem> = emptyList(),
    val highlights: List<String> = emptyList(),
    val rawJsonSnippet: String = "",
    val scraperError: String? = null
) {
    fun hasGenuineData(): Boolean {
        if (!scraperError.isNullOrBlank()) return false
        val hasStats = statistics.isNotEmpty()
        val hasBio = bioOrDescription.isNotBlank()
        val hasTitle = title.isNotBlank() && title != identifier
        val hasWebsite = websiteUrl.isNotBlank()
        val hasAvatar = avatarUrl.isNotBlank()
        val hasEmail = publicEmail.isNotBlank()
        val hasPhone = publicPhone.isNotBlank()
        val hasMedia = recentMedia.isNotEmpty()
        val hasHighlights = highlights.isNotEmpty()
        val hasVerifiedStatus = isVerified != null
        val hasPrivateStatus = isPrivate != null

        return hasStats || hasBio || hasTitle || hasWebsite || hasAvatar || hasEmail || hasPhone || hasMedia || hasHighlights || hasVerifiedStatus || hasPrivateStatus
    }

    fun toPlatformProfile(platform: PlatformType): PlatformProfile {
        return PlatformProfile(
            platform = platform,
            username = identifier,
            displayName = title.ifBlank { identifier },
            profileUrl = directUrl,
            avatarUrl = avatarUrl,
            coverImageUrl = coverImageUrl,
            isVerified = isVerified,
            isPrivate = isPrivate,
            category = category,
            bio = bioOrDescription,
            website = websiteUrl,
            location = location,
            followers = statistics["Followers"] ?: statistics["Subscribers"] ?: "",
            following = statistics["Following"] ?: "",
            postCount = statistics["Posts / Media"] ?: statistics["Videos"] ?: "",
            subscriberCount = statistics["Subscribers"] ?: "",
            views = statistics["Views"] ?: statistics["Engagement / Score"] ?: "",
            recentMedia = recentMedia,
            publicLinks = if (websiteUrl.isNotBlank()) listOf(websiteUrl) else emptyList(),
            publicEmail = publicEmail,
            publicPhone = publicPhone,
            highlights = highlights,
            rawJsonSnippet = rawJsonSnippet,
            sourceConfidence = "Verified Public Data",
            scraperError = scraperError
        )
    }
}

/**
 * Normalizes disparate raw Apify dataset records into structured, high-value intelligence facts and PlatformProfiles.
 */
object SpyResultNormalizer {

    fun normalize(items: JsonArray, task: SpyTask): List<NormalizedEntity> {
        val entities = mutableListOf<NormalizedEntity>()

        for (item in items) {
            if (item !is JsonObject) continue
            val entity = parseJsonObject(item, task.platform)
            if (entity != null) {
                entities.add(entity)
            }
        }

        return entities
    }

    fun normalizeToProfiles(items: JsonArray, task: SpyTask): List<PlatformProfile> {
        return normalize(items, task).map { it.toPlatformProfile(task.platform) }
    }

    private fun parseJsonObject(obj: JsonObject, platform: PlatformType): NormalizedEntity? {
        // Scraper error fields
        val errorMsg = obj["error"]?.jsonPrimitive?.content
            ?: obj["errorMessage"]?.jsonPrimitive?.content
            ?: obj["errorDescription"]?.jsonPrimitive?.content

        val title = obj["fullName"]?.jsonPrimitive?.content
            ?: obj["title"]?.jsonPrimitive?.content
            ?: obj["name"]?.jsonPrimitive?.content
            ?: obj["channelName"]?.jsonPrimitive?.content
            ?: obj["username"]?.jsonPrimitive?.content
            ?: obj["author"]?.jsonPrimitive?.content
            ?: ""

        val username = obj["username"]?.jsonPrimitive?.content
            ?: obj["handle"]?.jsonPrimitive?.content
            ?: obj["author"]?.jsonPrimitive?.content
            ?: obj["channelId"]?.jsonPrimitive?.content
            ?: ""

        val bio = obj["biography"]?.jsonPrimitive?.content
            ?: obj["description"]?.jsonPrimitive?.content
            ?: obj["bio"]?.jsonPrimitive?.content
            ?: obj["selftext"]?.jsonPrimitive?.content
            ?: obj["text"]?.jsonPrimitive?.content
            ?: ""

        val url = obj["url"]?.jsonPrimitive?.content
            ?: obj["profileUrl"]?.jsonPrimitive?.content
            ?: obj["channelUrl"]?.jsonPrimitive?.content
            ?: obj["link"]?.jsonPrimitive?.content
            ?: ""

        val avatar = obj["profilePicUrlHd"]?.jsonPrimitive?.content
            ?: obj["profilePicUrlHD"]?.jsonPrimitive?.content
            ?: obj["profilePicUrl"]?.jsonPrimitive?.content
            ?: obj["avatarUrl"]?.jsonPrimitive?.content
            ?: obj["avatarLarger"]?.jsonPrimitive?.content
            ?: obj["avatar"]?.jsonPrimitive?.content
            ?: obj["pictureUrl"]?.jsonPrimitive?.content
            ?: obj["iconImg"]?.jsonPrimitive?.content
            ?: ""

        val cover = obj["bannerUrl"]?.jsonPrimitive?.content
            ?: obj["coverUrl"]?.jsonPrimitive?.content
            ?: obj["headerImage"]?.jsonPrimitive?.content
            ?: ""

        val category = obj["businessCategoryName"]?.jsonPrimitive?.content
            ?: obj["categoryName"]?.jsonPrimitive?.content
            ?: obj["category"]?.jsonPrimitive?.content
            ?: ""

        val website = obj["externalUrl"]?.jsonPrimitive?.content
            ?: obj["external_url"]?.jsonPrimitive?.content
            ?: obj["website"]?.jsonPrimitive?.content
            ?: obj["blog"]?.jsonPrimitive?.content
            ?: ""

        val location = obj["location"]?.jsonPrimitive?.content
            ?: obj["address"]?.jsonPrimitive?.content
            ?: obj["country"]?.jsonPrimitive?.content
            ?: ""

        // Public business email & phone extraction
        val rawEmail = obj["businessEmail"]?.jsonPrimitive?.content
            ?: obj["email"]?.jsonPrimitive?.content
            ?: extractEmailFromText(bio)

        val rawPhone = obj["businessPhoneNumber"]?.jsonPrimitive?.content
            ?: obj["phoneNumber"]?.jsonPrimitive?.content
            ?: obj["phone"]?.jsonPrimitive?.content
            ?: extractPhoneFromText(bio)

        val verified = obj["verified"]?.jsonPrimitive?.booleanOrNull
            ?: obj["isVerified"]?.jsonPrimitive?.booleanOrNull
            ?: obj["is_verified"]?.jsonPrimitive?.booleanOrNull

        val isPrivate = obj["isPrivate"]?.jsonPrimitive?.booleanOrNull
            ?: obj["is_private"]?.jsonPrimitive?.booleanOrNull

        val stats = mutableMapOf<String, String>()

        // Followers / Subscribers
        val followers = obj["followersCount"]?.jsonPrimitive?.content
            ?: obj["subscribers"]?.jsonPrimitive?.content
            ?: obj["subscribersCount"]?.jsonPrimitive?.content
            ?: obj["follower_count"]?.jsonPrimitive?.content
            ?: obj["followers"]?.jsonPrimitive?.content
            ?: obj["subscriberCount"]?.jsonPrimitive?.content
        if (!followers.isNullOrBlank()) {
            if (platform == PlatformType.YOUTUBE) {
                stats["Subscribers"] = formatCount(followers)
            } else {
                stats["Followers"] = formatCount(followers)
            }
        }

        // Following
        val following = obj["followsCount"]?.jsonPrimitive?.content
            ?: obj["followingCount"]?.jsonPrimitive?.content
            ?: obj["following_count"]?.jsonPrimitive?.content
            ?: obj["following"]?.jsonPrimitive?.content
        if (!following.isNullOrBlank()) stats["Following"] = formatCount(following)

        // Posts count / Videos count / Repos count
        val posts = obj["postsCount"]?.jsonPrimitive?.content
            ?: obj["videosCount"]?.jsonPrimitive?.content
            ?: obj["mediaCount"]?.jsonPrimitive?.content
            ?: obj["videoCount"]?.jsonPrimitive?.content
            ?: obj["publicRepos"]?.jsonPrimitive?.content
            ?: obj["posts_count"]?.jsonPrimitive?.content
        if (!posts.isNullOrBlank() && !posts.startsWith("[")) {
            if (platform == PlatformType.YOUTUBE) {
                stats["Videos"] = formatCount(posts)
            } else if (platform == PlatformType.GITHUB) {
                stats["Repositories"] = formatCount(posts)
            } else {
                stats["Posts / Media"] = formatCount(posts)
            }
        }

        // Score / Likes / Views
        val score = obj["viewCount"]?.jsonPrimitive?.content
            ?: obj["totalScore"]?.jsonPrimitive?.content
            ?: obj["heartCount"]?.jsonPrimitive?.content
            ?: obj["score"]?.jsonPrimitive?.content
            ?: obj["upvotes"]?.jsonPrimitive?.content
            ?: obj["likes"]?.jsonPrimitive?.content
            ?: obj["views"]?.jsonPrimitive?.content
        if (!score.isNullOrBlank()) {
            if (platform == PlatformType.YOUTUBE) {
                stats["Total Views"] = formatCount(score)
            } else {
                stats["Engagement / Score"] = formatCount(score)
            }
        }

        // Extract Recent Media Items
        val recentMediaList = mutableListOf<PlatformMediaItem>()
        val latestPosts = obj["latestPosts"] ?: obj["posts"] ?: obj["items"] ?: obj["latestVideos"] ?: obj["recentPosts"]
        if (latestPosts is JsonArray) {
            for ((idx, p) in latestPosts.take(6).withIndex()) {
                if (p is JsonObject) {
                    val mediaId = p["id"]?.jsonPrimitive?.content ?: "m_$idx"
                    val mediaThumb = p["displayUrl"]?.jsonPrimitive?.content
                        ?: p["thumbnailUrl"]?.jsonPrimitive?.content
                        ?: p["imageUrl"]?.jsonPrimitive?.content
                        ?: p["thumbnail"]?.jsonPrimitive?.content
                        ?: ""
                    val mediaDirect = p["url"]?.jsonPrimitive?.content
                        ?: p["shortCode"]?.let { "https://www.instagram.com/p/${it.jsonPrimitive.content}/" }
                        ?: ""
                    val caption = p["caption"]?.jsonPrimitive?.content
                        ?: p["title"]?.jsonPrimitive?.content
                        ?: p["text"]?.jsonPrimitive?.content
                        ?: ""
                    val likes = p["likesCount"]?.jsonPrimitive?.content
                        ?: p["likes"]?.jsonPrimitive?.content
                        ?: ""
                    val comments = p["commentsCount"]?.jsonPrimitive?.content
                        ?: p["comments"]?.jsonPrimitive?.content
                        ?: ""
                    val type = p["type"]?.jsonPrimitive?.content ?: "image"

                    if (mediaThumb.isNotBlank() || caption.isNotBlank() || mediaDirect.isNotBlank()) {
                        recentMediaList.add(
                            PlatformMediaItem(
                                id = mediaId,
                                thumbnailUrl = mediaThumb,
                                mediaUrl = mediaDirect,
                                caption = caption.take(160),
                                type = type,
                                likesCount = if (likes.isNotBlank()) formatCount(likes) else "",
                                commentsCount = if (comments.isNotBlank()) formatCount(comments) else ""
                            )
                        )
                    }
                }
            }
        }

        // Extract Highlights
        val highlights = mutableListOf<String>()
        if (recentMediaList.isNotEmpty()) {
            recentMediaList.take(3).forEach { m ->
                if (m.caption.isNotBlank()) highlights.add(m.caption)
            }
        }

        if (title.isBlank() && username.isBlank() && bio.isBlank() && stats.isEmpty() && errorMsg.isNullOrBlank() && avatar.isBlank()) {
            return null
        }

        return NormalizedEntity(
            title = title,
            identifier = username,
            bioOrDescription = bio,
            statistics = stats,
            directUrl = url,
            avatarUrl = avatar,
            coverImageUrl = cover,
            isVerified = verified,
            isPrivate = isPrivate,
            category = category,
            websiteUrl = website,
            publicEmail = rawEmail.orEmpty(),
            publicPhone = rawPhone.orEmpty(),
            location = location,
            recentMedia = recentMediaList,
            highlights = highlights,
            rawJsonSnippet = obj.toString().take(500),
            scraperError = errorMsg
        )
    }

    private fun formatCount(raw: String): String {
        val num = raw.toDoubleOrNull() ?: return raw
        return when {
            num >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", num / 1_000_000.0)
            num >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", num / 1_000.0)
            else -> raw
        }
    }

    private fun extractEmailFromText(text: String): String? {
        val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        return emailRegex.find(text)?.value
    }

    private fun extractPhoneFromText(text: String): String? {
        val phoneRegex = Regex("(?:\\+?\\d{1,3}[- ]?)?\\(?\\d{3}\\)?[- ]?\\d{3}[- ]?\\d{4}")
        return phoneRegex.find(text)?.value
    }
}

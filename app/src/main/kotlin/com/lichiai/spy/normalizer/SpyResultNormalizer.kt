package com.lichiai.spy.normalizer

import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.model.FieldEvidence
import com.lichiai.spy.model.PlatformAccount
import com.lichiai.spy.model.PlatformMediaItem
import com.lichiai.spy.model.PlatformProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

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
    val followersList: List<PlatformAccount> = emptyList(),
    val followingList: List<PlatformAccount> = emptyList(),
    val recentMedia: List<PlatformMediaItem> = emptyList(),
    val highlights: List<String> = emptyList(),
    val rawJsonSnippet: String = "",
    val scraperError: String? = null,
    val providerId: String = ""
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
        val hasAccounts = followersList.isNotEmpty() || followingList.isNotEmpty()
        val hasVerifiedStatus = isVerified != null
        val hasPrivateStatus = isPrivate != null

        return hasStats || hasBio || hasTitle || hasWebsite || hasAvatar || hasEmail || hasPhone || hasMedia || hasHighlights || hasAccounts || hasVerifiedStatus || hasPrivateStatus
    }

    fun toPlatformProfile(platform: PlatformType, providerId: String = this.providerId): PlatformProfile {
        val providers = if (providerId.isNotBlank()) listOf(providerId) else emptyList()
        val evidenceMap = mutableMapOf<String, MutableList<FieldEvidence>>()

        fun addEv(field: String, value: String) {
            if (value.isNotBlank() && providerId.isNotBlank()) {
                evidenceMap.getOrPut(field) { mutableListOf() }.add(
                    FieldEvidence(providerId = providerId, fieldName = field, value = value)
                )
            }
        }

        addEv("username", identifier)
        addEv("displayName", title)
        addEv("bio", bioOrDescription)
        addEv("website", websiteUrl)
        addEv("publicEmail", publicEmail)
        addEv("publicPhone", publicPhone)
        statistics["Followers"]?.let { addEv("followers", it) }
        statistics["Following"]?.let { addEv("following", it) }
        statistics["Posts / Media"]?.let { addEv("postCount", it) }

        return PlatformProfile(
            platform = platform,
            platformKey = platform.id,
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
            followersList = followersList,
            followingList = followingList,
            recentMedia = recentMedia,
            publicLinks = if (websiteUrl.isNotBlank()) listOf(websiteUrl) else emptyList(),
            publicEmail = publicEmail,
            publicPhone = publicPhone,
            highlights = highlights,
            rawJsonSnippet = rawJsonSnippet,
            sourceConfidence = "Verified Public Data",
            scraperError = scraperError,
            sourceProviders = providers,
            fieldEvidence = evidenceMap.mapValues { it.value.toList() }
        )
    }
}

/**
 * Normalizes disparate raw Apify dataset records into structured, high-value intelligence facts and PlatformProfiles.
 */
object SpyResultNormalizer {

    fun normalize(items: JsonArray, task: SpyTask, providerId: String = ""): List<NormalizedEntity> {
        val entities = mutableListOf<NormalizedEntity>()

        for (item in items) {
            when (item) {
                is JsonObject -> {
                    // Check if it's a wrapper object like { data: [...], results: [...] }
                    val innerArray = item["data"] ?: item["results"] ?: item["items"] ?: item["profiles"] ?: item["users"]
                    if (innerArray is JsonArray && innerArray.isNotEmpty()) {
                        for (inner in innerArray) {
                            if (inner is JsonObject) {
                                val entity = parseJsonObject(inner, task.platform, providerId)
                                if (entity != null) entities.add(entity)
                            }
                        }
                    } else {
                        val entity = parseJsonObject(item, task.platform, providerId)
                        if (entity != null) entities.add(entity)
                    }
                }
                else -> {}
            }
        }

        return entities
    }

    fun normalizeToProfiles(items: JsonArray, task: SpyTask, providerId: String = ""): List<PlatformProfile> {
        return normalize(items, task, providerId).map { it.toPlatformProfile(task.platform, providerId) }
    }

    private fun parseJsonObject(obj: JsonObject, platform: PlatformType, providerId: String): NormalizedEntity? {
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
            ?: obj["id"]?.jsonPrimitive?.content
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
            for ((idx, p) in latestPosts.take(12).withIndex()) {
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
                                caption = caption.take(240),
                                type = type,
                                likesCount = if (likes.isNotBlank()) formatCount(likes) else "",
                                commentsCount = if (comments.isNotBlank()) formatCount(comments) else "",
                                sourceProviderIds = if (providerId.isNotBlank()) listOf(providerId) else emptyList()
                            )
                        )
                    }
                }
            }
        }

        // Extract Followers / Following list if present
        val followersList = mutableListOf<PlatformAccount>()
        val rawFollowers = obj["followersList"] ?: obj["followers_list"] ?: obj["followers"]
        if (rawFollowers is JsonArray) {
            for (f in rawFollowers.take(20)) {
                if (f is JsonObject) {
                    val fUser = f["username"]?.jsonPrimitive?.content ?: f["id"]?.jsonPrimitive?.content ?: ""
                    val fName = f["fullName"]?.jsonPrimitive?.content ?: f["name"]?.jsonPrimitive?.content ?: fUser
                    val fAvatar = f["profilePicUrl"]?.jsonPrimitive?.content ?: f["avatarUrl"]?.jsonPrimitive?.content ?: ""
                    val fUrl = f["url"]?.jsonPrimitive?.content ?: ""
                    if (fUser.isNotBlank()) {
                        followersList.add(
                            PlatformAccount(
                                username = fUser,
                                displayName = fName,
                                avatarUrl = fAvatar,
                                profileUrl = fUrl,
                                sourceProviderIds = if (providerId.isNotBlank()) listOf(providerId) else emptyList()
                            )
                        )
                    }
                }
            }
        }

        val followingList = mutableListOf<PlatformAccount>()
        val rawFollowing = obj["followingList"] ?: obj["following_list"] ?: obj["following"]
        if (rawFollowing is JsonArray) {
            for (f in rawFollowing.take(20)) {
                if (f is JsonObject) {
                    val fUser = f["username"]?.jsonPrimitive?.content ?: f["id"]?.jsonPrimitive?.content ?: ""
                    val fName = f["fullName"]?.jsonPrimitive?.content ?: f["name"]?.jsonPrimitive?.content ?: fUser
                    val fAvatar = f["profilePicUrl"]?.jsonPrimitive?.content ?: f["avatarUrl"]?.jsonPrimitive?.content ?: ""
                    val fUrl = f["url"]?.jsonPrimitive?.content ?: ""
                    if (fUser.isNotBlank()) {
                        followingList.add(
                            PlatformAccount(
                                username = fUser,
                                displayName = fName,
                                avatarUrl = fAvatar,
                                profileUrl = fUrl,
                                sourceProviderIds = if (providerId.isNotBlank()) listOf(providerId) else emptyList()
                            )
                        )
                    }
                }
            }
        }

        // Extract Highlights
        val highlights = mutableListOf<String>()
        if (recentMediaList.isNotEmpty()) {
            recentMediaList.take(4).forEach { m ->
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
            followersList = followersList,
            followingList = followingList,
            recentMedia = recentMediaList,
            highlights = highlights,
            rawJsonSnippet = obj.toString().take(600),
            scraperError = errorMsg,
            providerId = providerId
        )
    }

    private fun formatCount(raw: String): String {
        val num = raw.toDoubleOrNull() ?: return raw
        return when {
            num >= 1_000_000 -> String.format(Locale.US, "%.1fM", num / 1_000_000.0)
            num >= 1_000 -> String.format(Locale.US, "%.1fK", num / 1_000.0)
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

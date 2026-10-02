package com.lichiai.spy.discovery

import android.util.Log
import com.lichiai.spy.apify.ActorIdentifierResolver
import com.lichiai.spy.apify.ApifyClient
import com.lichiai.spy.apify.ApifyStoreItem
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyError
import com.lichiai.spy.core.SpyOperation
import java.util.Locale

/**
 * Service responsible for discovering, evaluating, and selecting suitable Apify Actors.
 *
 * Implements:
 * 1. Curated high-reputation fallback registry for common platforms (Instagram, YouTube, Reddit, TikTok, Twitter/X, GitHub)
 * 2. Operation-compatibility evaluation (matching PROFILE_LOOKUP vs POST_SEARCH vs CHANNEL_DATA)
 * 3. Dynamic Apify Store search and validation
 * 4. Free-First ranking policy (prioritizes free and pay-per-event/result over monthly subscriptions)
 */
class ActorDiscoveryService(
    private val apifyClient: ApifyClient
) {
    companion object {
        private const val TAG = "ActorDiscovery"

        // Verified curated public Apify Actors with high reputation and proven stability
        private val CURATED_ACTORS = mapOf(
            PlatformType.INSTAGRAM to listOf(
                ActorMetadata(
                    actorId = "apify~instagram-profile-scraper",
                    name = "instagram-profile-scraper",
                    username = "apify",
                    title = "Instagram Profile Scraper",
                    description = "Scrape public Instagram profiles, follower counts, biographies, and recent posts without login.",
                    isFree = true,
                    pricingModel = "FREE"
                ),
                ActorMetadata(
                    actorId = "apify~instagram-scraper",
                    name = "instagram-scraper",
                    username = "apify",
                    title = "Instagram Scraper",
                    description = "Extract public posts, comments, hashtags, and reels.",
                    isFree = true,
                    pricingModel = "FREE"
                )
            ),
            PlatformType.YOUTUBE to listOf(
                ActorMetadata(
                    actorId = "streamers~youtube-channel-scraper",
                    name = "youtube-channel-scraper",
                    username = "streamers",
                    title = "YouTube Channel Scraper",
                    description = "Extract channel metadata, statistics, playlists, and latest videos.",
                    isFree = true,
                    pricingModel = "FREE"
                ),
                ActorMetadata(
                    actorId = "streamers~youtube-scraper",
                    name = "youtube-scraper",
                    username = "streamers",
                    title = "YouTube Scraper",
                    description = "Scrape YouTube channels, video details, view counts, subscriber counts, and comments.",
                    isFree = true,
                    pricingModel = "FREE"
                )
            ),
            PlatformType.REDDIT to listOf(
                ActorMetadata(
                    actorId = "trudax~reddit-scraper-lite",
                    name = "reddit-scraper-lite",
                    username = "trudax",
                    title = "Reddit Scraper Lite",
                    description = "Scrape subreddits, posts, comments, karma, and user profiles.",
                    isFree = true,
                    pricingModel = "FREE"
                )
            ),
            PlatformType.TIKTOK to listOf(
                ActorMetadata(
                    actorId = "clockworks~tiktok-profile-scraper",
                    name = "tiktok-profile-scraper",
                    username = "clockworks",
                    title = "TikTok Profile Scraper",
                    description = "Scrape public TikTok profiles, bio, follower count, and recent videos.",
                    isFree = true,
                    pricingModel = "FREE"
                )
            ),
            PlatformType.TWITTER_X to listOf(
                ActorMetadata(
                    actorId = "apidojo~twitter-user-scraper",
                    name = "twitter-user-scraper",
                    username = "apidojo",
                    title = "Twitter / X User Scraper",
                    description = "Scrape public Twitter / X profiles, bio, followers, tweets.",
                    isFree = true,
                    pricingModel = "FREE"
                )
            ),
            PlatformType.GITHUB to listOf(
                ActorMetadata(
                    actorId = "apify~github-user-scraper",
                    name = "github-user-scraper",
                    username = "apify",
                    title = "GitHub User & Repo Scraper",
                    description = "Scrape public GitHub user profiles, repositories, stars, and contributions.",
                    isFree = true,
                    pricingModel = "FREE"
                )
            )
        )
    }

    /**
     * Finds the best Actor candidate for the given platform and operation.
     * Evaluates semantic operation compatibility rather than just popularity metrics.
     */
    suspend fun discoverBestActor(
        platform: PlatformType,
        operation: SpyOperation,
        targetQuery: String,
        freeFirstOnly: Boolean = true
    ): Result<ActorMetadata> {
        if (platform == PlatformType.UNKNOWN) {
            return Result.failure(SpyError.NoCompatibleActor("Unknown platform", operation.name))
        }

        // 1. Check curated list first for instantaneous & reliable matching
        val curatedCandidates = CURATED_ACTORS[platform] ?: emptyList()
        val matchingCurated = selectCuratedActor(curatedCandidates, operation)

        // 2. Query dynamic Apify Store
        val searchQuery = "${platform.displayName} ${getOperationSearchKeyword(operation)} $targetQuery".trim()
        val storeResult = apifyClient.searchStore(query = searchQuery, limit = 8)

        if (storeResult.isSuccess) {
            val storeItems = storeResult.getOrNull() ?: emptyList()
            val filtered = if (freeFirstOnly) {
                storeItems.filter { isItemFree(it) }
            } else storeItems

            val candidates = if (filtered.isNotEmpty()) filtered else storeItems

            // Rank with operation compatibility weight
            val scored = candidates.map { item ->
                val compatibilityScore = calculateOperationCompatibility(item, operation)
                val popularityScore = ((item.stats?.totalRuns ?: 0L).coerceAtMost(100_000) / 1000.0) + (item.stats?.bookmarkCount ?: 0L)
                val totalScore = compatibilityScore * 100 + popularityScore
                item to totalScore
            }.sortedByDescending { it.second }

            val topStoreItem = scored.firstOrNull()?.first
            if (topStoreItem != null && calculateOperationCompatibility(topStoreItem, operation) > 0) {
                val fullActorId = if (topStoreItem.username.isNotBlank()) {
                    ActorIdentifierResolver.toCanonicalApiId("${topStoreItem.username}~${topStoreItem.name}")
                } else {
                    ActorIdentifierResolver.toCanonicalApiId(topStoreItem.id)
                }
                val metadata = ActorMetadata(
                    actorId = fullActorId,
                    name = topStoreItem.name,
                    username = topStoreItem.username,
                    title = topStoreItem.title,
                    description = topStoreItem.description,
                    isFree = isItemFree(topStoreItem),
                    pricingModel = topStoreItem.pricingModel ?: topStoreItem.currentPricing?.pricingModel,
                    priceUsd = topStoreItem.currentPricing?.priceUsd,
                    totalRuns = topStoreItem.stats?.totalRuns ?: 0L,
                    bookmarkCount = topStoreItem.stats?.bookmarkCount ?: 0L
                )
                return Result.success(metadata)
            }
        }

        // 3. Fallback to curated Actor if store search had no compatible items
        if (matchingCurated != null) {
            return Result.success(matchingCurated)
        }

        return Result.failure(SpyError.NoCompatibleActor(platform.displayName, operation.name))
    }

    private fun selectCuratedActor(candidates: List<ActorMetadata>, operation: SpyOperation): ActorMetadata? {
        if (candidates.isEmpty()) return null
        return when (operation) {
            SpyOperation.PROFILE_LOOKUP, SpyOperation.PROFILE_PREVIEW, SpyOperation.PROFILE_FOLLOWERS_SUMMARY, SpyOperation.PROFILE_PUBLIC_CONTACTS -> {
                candidates.firstOrNull { it.name.contains("profile") || it.name.contains("user") } ?: candidates.first()
            }
            SpyOperation.CHANNEL_DATA -> {
                candidates.firstOrNull { it.name.contains("channel") } ?: candidates.firstOrNull { it.name.contains("youtube") } ?: candidates.first()
            }
            SpyOperation.POST_SEARCH, SpyOperation.COMMUNITY_POSTS, SpyOperation.CONTENT_SEARCH -> {
                candidates.firstOrNull { !it.name.contains("profile") } ?: candidates.first()
            }
            SpyOperation.PROFILE_MEDIA, SpyOperation.PROFILE_VIDEOS, SpyOperation.PROFILE_POSTS -> {
                candidates.firstOrNull { it.name.contains("scraper") } ?: candidates.first()
            }
            else -> candidates.first()
        }
    }

    private fun getOperationSearchKeyword(operation: SpyOperation): String {
        return when (operation) {
            SpyOperation.PROFILE_LOOKUP, SpyOperation.PROFILE_PREVIEW -> "profile"
            SpyOperation.CHANNEL_DATA -> "channel"
            SpyOperation.POST_SEARCH -> "posts"
            SpyOperation.COMMUNITY_POSTS -> "subreddit"
            SpyOperation.PROFILE_MEDIA, SpyOperation.PROFILE_VIDEOS -> "media"
            else -> "scraper"
        }
    }

    private fun calculateOperationCompatibility(item: ApifyStoreItem, operation: SpyOperation): Int {
        val name = item.name.lowercase(Locale.ROOT)
        val title = item.title.lowercase(Locale.ROOT)
        val desc = item.description.lowercase(Locale.ROOT)
        val text = "$name $title $desc"

        return when (operation) {
            SpyOperation.PROFILE_LOOKUP, SpyOperation.PROFILE_PREVIEW, SpyOperation.PROFILE_FOLLOWERS_SUMMARY, SpyOperation.PROFILE_PUBLIC_CONTACTS -> {
                var s = 0
                if (name.contains("profile") || name.contains("user")) s += 50
                if (title.contains("profile") || title.contains("user")) s += 30
                if (desc.contains("profile") || desc.contains("follower") || desc.contains("bio")) s += 20
                s
            }
            SpyOperation.CHANNEL_DATA -> {
                var s = 0
                if (name.contains("channel")) s += 50
                if (title.contains("channel")) s += 30
                if (desc.contains("channel") || desc.contains("subscriber")) s += 20
                s
            }
            SpyOperation.POST_SEARCH, SpyOperation.COMMUNITY_POSTS -> {
                var s = 0
                if (name.contains("post") || name.contains("reddit") || name.contains("tweet")) s += 50
                if (title.contains("post") || title.contains("search")) s += 30
                s
            }
            else -> 10
        }
    }

    private fun isItemFree(item: ApifyStoreItem): Boolean {
        val model = item.pricingModel ?: item.currentPricing?.pricingModel ?: ""
        return model.isBlank() || model.equals("FREE", ignoreCase = true) || item.currentPricing?.priceUsd == 0.0
    }
}

package com.lichiai.spy.discovery

import android.util.Log
import com.lichiai.spy.apify.ActorIdentifierResolver
import com.lichiai.spy.apify.ApifyClient
import com.lichiai.spy.apify.ApifyStoreItem
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyError
import com.lichiai.spy.core.SpyOperation

/**
 * Service responsible for discovering, evaluating, and selecting suitable Apify Actors.
 *
 * Implements:
 * 1. Curated high-reputation fallback registry for common platforms (Instagram, YouTube, Reddit, TikTok, Twitter/X, GitHub)
 * 2. Dynamic Apify Store search via ApifyClient
 * 3. Free-First ranking policy (prioritizes free and pay-per-event/result over monthly subscriptions)
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
                    actorId = "streamers~youtube-scraper",
                    name = "youtube-scraper",
                    username = "streamers",
                    title = "YouTube Scraper",
                    description = "Scrape YouTube channels, video details, view counts, subscriber counts, and comments.",
                    isFree = true,
                    pricingModel = "FREE"
                ),
                ActorMetadata(
                    actorId = "streamers~youtube-channel-scraper",
                    name = "youtube-channel-scraper",
                    username = "streamers",
                    title = "YouTube Channel Scraper",
                    description = "Extract channel metadata, statistics, playlists, and latest videos.",
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
     */
    suspend fun discoverBestActor(
        platform: PlatformType,
        operation: SpyOperation,
        targetQuery: String,
        freeFirstOnly: Boolean = true
    ): Result<ActorMetadata> {
        // 1. Check curated list first for instantaneous & reliable matching
        val curatedCandidates = CURATED_ACTORS[platform] ?: emptyList()
        val matchingCurated = when (operation) {
            SpyOperation.PROFILE_LOOKUP -> curatedCandidates.firstOrNull { it.name.contains("profile") }
            SpyOperation.CHANNEL_DATA -> curatedCandidates.firstOrNull { it.name.contains("channel") || it.name.contains("youtube") }
            SpyOperation.POST_SEARCH, SpyOperation.COMMUNITY_POSTS -> curatedCandidates.firstOrNull { !it.name.contains("profile") }
            else -> curatedCandidates.firstOrNull()
        } ?: curatedCandidates.firstOrNull()

        // 2. Query dynamic Apify Store
        val searchQuery = "${platform.displayName} ${operation.description.take(20)} $targetQuery".trim()
        val storeResult = apifyClient.searchStore(query = searchQuery, limit = 8)

        if (storeResult.isSuccess) {
            val storeItems = storeResult.getOrNull() ?: emptyList()
            val filtered = if (freeFirstOnly) {
                storeItems.filter { isItemFree(it) }
            } else storeItems

            val sorted = (if (filtered.isNotEmpty()) filtered else storeItems).sortedByDescending {
                (it.stats?.totalRuns ?: 0L) + (it.stats?.bookmarkCount ?: 0L) * 10L
            }

            val topStoreItem = sorted.firstOrNull()
            if (topStoreItem != null) {
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

        // Fallback to curated Actor if store search had no valid items or network issue
        if (matchingCurated != null) {
            return Result.success(matchingCurated)
        }

        return Result.failure(SpyError.NoCompatibleActor(platform.displayName, operation.name))
    }

    private fun isItemFree(item: ApifyStoreItem): Boolean {
        val model = item.pricingModel ?: item.currentPricing?.pricingModel ?: ""
        return model.isBlank() || model.equals("FREE", ignoreCase = true) || item.currentPricing?.priceUsd == 0.0
    }
}

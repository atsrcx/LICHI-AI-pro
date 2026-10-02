package com.lichiai.spy.orchestrator

import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.model.FieldEvidence
import com.lichiai.spy.model.PlatformAccount
import com.lichiai.spy.model.PlatformMediaItem
import com.lichiai.spy.model.PlatformProfile
import com.lichiai.spy.model.ProfileConflict
import com.lichiai.spy.normalizer.NormalizedEntity
import java.util.Locale

object SpyEvidenceMerger {

    fun mergeEntities(
        verifiedEntities: List<NormalizedEntity>,
        task: SpyTask,
        executedProviderIds: List<String>
    ): PlatformProfile {
        if (verifiedEntities.isEmpty()) {
            return PlatformProfile(
                platform = task.platform,
                platformKey = task.platform.id,
                username = task.target,
                sourceProviders = executedProviderIds,
                sourceConfidence = "No Verified Data"
            )
        }

        val allProviders = executedProviderIds.distinct()
        val allEvidence = mutableMapOf<String, MutableList<FieldEvidence>>()
        val conflicts = mutableListOf<ProfileConflict>()

        // 1. Collect field-level evidence across all verified entities
        for (entity in verifiedEntities) {
            val pid = entity.providerId.ifBlank { "anonymous_provider" }

            fun record(field: String, value: String) {
                if (value.isNotBlank()) {
                    allEvidence.getOrPut(field) { mutableListOf() }.add(
                        FieldEvidence(providerId = pid, fieldName = field, value = value)
                    )
                }
            }

            record("username", entity.identifier)
            record("displayName", entity.title)
            record("bio", entity.bioOrDescription)
            record("website", entity.websiteUrl)
            record("avatarUrl", entity.avatarUrl)
            record("coverImageUrl", entity.coverImageUrl)
            record("publicEmail", entity.publicEmail)
            record("publicPhone", entity.publicPhone)
            record("location", entity.location)
            record("category", entity.category)
            record("directUrl", entity.directUrl)

            entity.statistics["Followers"]?.let { record("followers", it) }
            entity.statistics["Following"]?.let { record("following", it) }
            entity.statistics["Posts / Media"]?.let { record("postCount", it) }
            entity.statistics["Videos"]?.let { record("videoCount", it) }
            entity.statistics["Subscribers"]?.let { record("subscriberCount", it) }
            entity.statistics["Views"]?.let { record("views", it) }
        }

        // 2. Select consensus/freshest values and detect conflicts
        fun resolveField(fieldName: String, default: String = ""): String {
            val evidences = allEvidence[fieldName] ?: return default
            if (evidences.isEmpty()) return default

            val distinctValues = evidences.map { it.value.trim() }.filter { it.isNotBlank() }.distinct()
            if (distinctValues.size > 1) {
                // Record conflict
                val chosen = distinctValues.maxByOrNull { it.length } ?: distinctValues.first()
                conflicts.add(
                    ProfileConflict(
                        fieldName = fieldName,
                        chosenValue = chosen,
                        evidences = evidences,
                        description = "Differing values reported: ${distinctValues.joinToString(", ")}"
                    )
                )
                return chosen
            }
            return distinctValues.firstOrNull() ?: default
        }

        val finalUsername = resolveField("username", task.target)
        val finalDisplayName = resolveField("displayName", finalUsername)
        val finalBio = resolveField("bio")
        val finalWebsite = resolveField("website")
        val finalAvatar = resolveField("avatarUrl")
        val finalCover = resolveField("coverImageUrl")
        val finalEmail = resolveField("publicEmail")
        val finalPhone = resolveField("publicPhone")
        val finalLocation = resolveField("location")
        val finalCategory = resolveField("category")
        val finalUrl = resolveField("directUrl")

        val finalFollowers = resolveField("followers")
        val finalFollowing = resolveField("following")
        val finalPostCount = resolveField("postCount")
        val finalVideoCount = resolveField("videoCount")
        val finalSubscribers = resolveField("subscriberCount")
        val finalViews = resolveField("views")

        val isVerified = verifiedEntities.firstNotNullOfOrNull { it.isVerified }
        val isPrivate = verifiedEntities.firstNotNullOfOrNull { it.isPrivate }

        // 3. Deduplicate Followers and Following
        val mergedFollowers = mutableListOf<PlatformAccount>()
        val seenFollowerUsernames = mutableSetOf<String>()
        for (f in verifiedEntities.flatMap { it.followersList }) {
            val key = f.username.lowercase(Locale.ROOT)
            if (key.isNotBlank() && seenFollowerUsernames.add(key)) {
                mergedFollowers.add(f)
            }
        }

        val mergedFollowing = mutableListOf<PlatformAccount>()
        val seenFollowingUsernames = mutableSetOf<String>()
        for (f in verifiedEntities.flatMap { it.followingList }) {
            val key = f.username.lowercase(Locale.ROOT)
            if (key.isNotBlank() && seenFollowingUsernames.add(key)) {
                mergedFollowing.add(f)
            }
        }

        // 4. Deduplicate Recent Media Items
        val mergedMedia = mutableListOf<PlatformMediaItem>()
        val seenMediaIds = mutableSetOf<String>()
        for (m in verifiedEntities.flatMap { it.recentMedia }) {
            val key = if (m.id.isNotBlank()) m.id else if (m.mediaUrl.isNotBlank()) m.mediaUrl else m.thumbnailUrl
            if (key.isNotBlank() && seenMediaIds.add(key)) {
                mergedMedia.add(m)
            }
        }

        // 5. Deduplicate Highlights
        val mergedHighlights = verifiedEntities.flatMap { it.highlights }.distinct()

        return PlatformProfile(
            platform = task.platform,
            platformKey = task.platform.id,
            username = finalUsername,
            displayName = finalDisplayName,
            profileUrl = finalUrl,
            avatarUrl = finalAvatar,
            coverImageUrl = finalCover,
            isVerified = isVerified,
            isPrivate = isPrivate,
            category = finalCategory,
            bio = finalBio,
            website = finalWebsite,
            location = finalLocation,
            followers = finalFollowers,
            following = finalFollowing,
            postCount = finalPostCount,
            videoCount = finalVideoCount,
            subscriberCount = finalSubscribers,
            views = finalViews,
            followersList = mergedFollowers,
            followingList = mergedFollowing,
            recentMedia = mergedMedia,
            publicLinks = if (finalWebsite.isNotBlank()) listOf(finalWebsite) else emptyList(),
            publicEmail = finalEmail,
            publicPhone = finalPhone,
            highlights = mergedHighlights,
            sourceConfidence = "Multi-Source Consensus (${verifiedEntities.size} verified providers)",
            sourceProviders = allProviders,
            fieldEvidence = allEvidence.mapValues { it.value.toList() },
            conflicts = conflicts,
            previewRequested = task.previewRequested
        )
    }
}

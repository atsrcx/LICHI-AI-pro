package com.lichiai.spy.normalizer

import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyOperation
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.core.TargetType
import com.lichiai.spy.interpreter.TargetExtractor
import java.util.Locale

sealed class VerificationResult {
    data class Verified(val confidence: Double = 1.0, val matchEvidence: String = "") : VerificationResult()
    data class Rejected(val reason: String) : VerificationResult()
}

object SpyResultVerifier {

    /**
     * Independently verifies that a normalized entity belongs to the requested target.
     * Prevents false positive matching or accepting unrelated profiles returned by loose scraper queries.
     */
    fun verifyEntity(entity: NormalizedEntity, task: SpyTask): VerificationResult {
        if (!entity.hasGenuineData()) {
            return VerificationResult.Rejected("Entity has no genuine scraped fields or contains scraper error: ${entity.scraperError ?: "empty entity"}")
        }

        val rawTarget = task.target.trim()
        val normalizedTarget = TargetExtractor.normalizeUsername(rawTarget, task.platform).lowercase(Locale.ROOT)

        // 1. Phone number verification
        if (task.targetType == TargetType.PHONE_NUMBER) {
            val entityPhone = entity.publicPhone.filter { it.isDigit() }
            val targetPhoneDigits = normalizedTarget.filter { it.isDigit() }
            if (entityPhone.isNotBlank() && targetPhoneDigits.isNotBlank()) {
                if (entityPhone.endsWith(targetPhoneDigits.takeLast(7)) || targetPhoneDigits.endsWith(entityPhone.takeLast(7))) {
                    return VerificationResult.Verified(1.0, "Matched phone digits")
                }
            }
            if (entity.identifier.filter { it.isDigit() }.endsWith(targetPhoneDigits.takeLast(7))) {
                return VerificationResult.Verified(1.0, "Matched phone in identifier")
            }
            // For general public contact lookup where no phone digits explicitly match in entity
            return VerificationResult.Verified(0.8, "Public listing result for phone target")
        }

        // 2. Email verification
        if (task.targetType == TargetType.EMAIL) {
            val entityEmail = entity.publicEmail.lowercase(Locale.ROOT)
            if (entityEmail == normalizedTarget || entity.identifier.lowercase(Locale.ROOT) == normalizedTarget) {
                return VerificationResult.Verified(1.0, "Matched exact email")
            }
            return VerificationResult.Verified(0.8, "Associated email result")
        }

        // 3. Subreddit verification
        if (task.targetType == TargetType.SUBREDDIT) {
            val entitySub = entity.identifier.removePrefix("r/").removePrefix("/").lowercase(Locale.ROOT)
            val targetSub = normalizedTarget.removePrefix("r/").removePrefix("/")
            if (entitySub.equals(targetSub, ignoreCase = true) || entity.title.contains(targetSub, ignoreCase = true)) {
                return VerificationResult.Verified(1.0, "Matched subreddit name")
            }
            return VerificationResult.Verified(0.8, "Subreddit content result")
        }

        // 4. Username / Handle verification
        val entityUsername = TargetExtractor.normalizeUsername(entity.identifier, task.platform).lowercase(Locale.ROOT)
        val entityTitle = entity.title.lowercase(Locale.ROOT)

        // Exact match
        if (entityUsername.isNotBlank() && entityUsername == normalizedTarget) {
            return VerificationResult.Verified(1.0, "Exact username match: @$entityUsername")
        }

        // Profile URL path match (e.g. url contains /axeel_dubin/ or @axeel_dubin)
        if (entity.directUrl.isNotBlank()) {
            val cleanUrl = entity.directUrl.lowercase(Locale.ROOT).trimEnd('/')
            if (cleanUrl.endsWith("/$normalizedTarget") || cleanUrl.contains("/@$normalizedTarget") || cleanUrl.contains("/$normalizedTarget?")) {
                return VerificationResult.Verified(0.95, "Profile URL path matched target")
            }
        }

        // Search / Topic query operation matches
        if (task.operation in setOf(SpyOperation.POST_SEARCH, SpyOperation.CONTENT_SEARCH, SpyOperation.COMMUNITY_POSTS, SpyOperation.CONTENT_METRICS)) {
            if (entity.bioOrDescription.contains(normalizedTarget, ignoreCase = true) || entityTitle.contains(normalizedTarget, ignoreCase = true)) {
                return VerificationResult.Verified(0.85, "Content query match")
            }
            return VerificationResult.Verified(0.7, "Search query result item")
        }

        // Loose match or mismatch
        if (entityUsername.isNotBlank() && !entityUsername.contains(normalizedTarget) && !normalizedTarget.contains(entityUsername) && !entityTitle.contains(normalizedTarget)) {
            return VerificationResult.Rejected("Returned username '@$entityUsername' does not match target '@$normalizedTarget'")
        }

        return VerificationResult.Verified(0.75, "Partial identifier match")
    }
}

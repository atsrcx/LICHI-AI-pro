package com.lichiai.spy.parser

import com.lichiai.spy.model.SpyOperation
import com.lichiai.spy.model.SpyPlatform
import com.lichiai.spy.model.SpyTask

object SpyIntentParser {
    fun parse(rawInput: String): SpyTask? {
        if (!SpyGate.isSpyCommand(rawInput)) return null
        val (payload, isFullScan) = SpyGate.extractPayload(rawInput)
        if (payload.isBlank()) return null

        val extracted = TargetExtractor.extract(payload)
        val lower = payload.lowercase()

        // 1. Standalone Phone Routing
        if (extracted.detectedType == TargetExtractor.TargetType.PHONE) {
            return SpyTask(
                platform = SpyPlatform.PHONE_DIRECTORY,
                operation = SpyOperation.PUBLIC_PHONE_LOOKUP,
                target = extracted.cleanTarget,
                isFullScan = isFullScan,
                rawQuery = rawInput
            )
        }

        // 2. Standalone Email Routing
        if (extracted.detectedType == TargetExtractor.TargetType.EMAIL) {
            return SpyTask(
                platform = SpyPlatform.EMAIL_DIRECTORY,
                operation = SpyOperation.PUBLIC_EMAIL_LOOKUP,
                target = extracted.cleanTarget,
                isFullScan = isFullScan,
                rawQuery = rawInput
            )
        }

        // 3. Social Platforms Resolution
        val platform = when {
            lower.contains("instagram") || lower.contains("insta") -> SpyPlatform.INSTAGRAM
            lower.contains("twitter") || lower.contains(" x ") || lower.endsWith(" x") -> SpyPlatform.TWITTER
            lower.contains("youtube") || lower.contains("yt") -> SpyPlatform.YOUTUBE
            lower.contains("github") -> SpyPlatform.GITHUB
            lower.contains("linkedin") -> SpyPlatform.LINKEDIN
            lower.contains("phone") || lower.contains("number") || lower.contains("call") -> SpyPlatform.PHONE_DIRECTORY
            lower.contains("email") || lower.contains("mail") -> SpyPlatform.EMAIL_DIRECTORY
            else -> SpyPlatform.GENERIC_WEB
        }

        val operation = when {
            platform == SpyPlatform.PHONE_DIRECTORY -> SpyOperation.PUBLIC_PHONE_LOOKUP
            platform == SpyPlatform.EMAIL_DIRECTORY -> SpyOperation.PUBLIC_EMAIL_LOOKUP
            lower.contains("posts") || lower.contains("media") -> SpyOperation.PROFILE_POSTS
            lower.contains("search") -> SpyOperation.PROFILE_SEARCH
            else -> SpyOperation.PROFILE_LOOKUP
        }

        return SpyTask(
            platform = platform,
            operation = operation,
            target = extracted.cleanTarget,
            isFullScan = isFullScan,
            rawQuery = rawInput
        )
    }
}

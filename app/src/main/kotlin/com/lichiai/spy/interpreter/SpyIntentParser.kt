package com.lichiai.spy.interpreter

import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyOperation
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.model.PlatformCatalog
import java.util.Locale

/**
 * Parses user input queries following the `#Spy` trigger into structured [SpyTask] objects.
 * Supports English, Hindi, Hinglish, Roman Hindi patterns.
 */
object SpyIntentParser {

    fun parse(cleanQuery: String, requestId: String = "", messageId: String = ""): SpyTask {
        val lower = cleanQuery.lowercase(Locale.ROOT)

        // 1. Detect platform
        val platform = detectPlatform(lower)

        // 2. Detect operation
        val operation = detectOperation(lower)

        // 3. Extract target (username, handle, query, or channel) using language-aware extractor
        val target = TargetExtractor.extract(cleanQuery, platform)

        // 4. Extract requested fields or specific intents
        val fields = extractRequestedFields(lower)

        // 5. Detect preview requested
        val previewRequested = lower.contains("preview") || lower.contains("overview") || lower.contains("summary") || lower.contains("details") || true

        return SpyTask(
            requestId = requestId,
            messageId = messageId,
            platform = platform,
            operation = operation,
            target = target,
            requestedFields = fields,
            rawQuery = cleanQuery,
            maxResults = 5,
            previewRequested = previewRequested
        )
    }

    private fun detectPlatform(lower: String): PlatformType {
        // Direct matching via PlatformCatalog aliases
        val catalogMatch = PlatformCatalog.findByAlias(lower)
        if (catalogMatch != null) {
            return catalogMatch.platformType
        }

        return when {
            lower.contains("instagram") || lower.contains("insta ") || lower.contains("insta:") -> PlatformType.INSTAGRAM
            lower.contains("youtube shorts") || lower.contains("yt shorts") -> PlatformType.YOUTUBE_SHORTS
            lower.contains("youtube") || lower.contains("yt ") || lower.contains("yt:") -> PlatformType.YOUTUBE
            lower.contains("reddit") || lower.contains("subreddit") || lower.contains("r/") -> PlatformType.REDDIT
            lower.contains("tiktok") -> PlatformType.TIKTOK
            lower.contains("threads") -> PlatformType.THREADS
            lower.contains("snapchat") || lower.contains("snap ") -> PlatformType.SNAPCHAT
            lower.contains("pinterest") -> PlatformType.PINTEREST
            lower.contains("twitter") || lower.contains("x.com") || lower.contains(" x ") -> PlatformType.TWITTER_X
            lower.contains("linkedin") -> PlatformType.LINKEDIN
            lower.contains("facebook") || lower.contains("fb ") -> PlatformType.FACEBOOK
            lower.contains("spotify") -> PlatformType.SPOTIFY
            lower.contains("soundcloud") -> PlatformType.SOUNDCLOUD
            lower.contains("telegram") || lower.contains("t.me") -> PlatformType.TELEGRAM
            lower.contains("discord") -> PlatformType.DISCORD
            lower.contains("github") || lower.contains("gh ") -> PlatformType.GITHUB
            lower.contains("gitlab") -> PlatformType.GITLAB
            lower.contains("quora") -> PlatformType.QUORA
            lower.contains("medium") -> PlatformType.MEDIUM
            lower.contains("maps") || lower.contains("google maps") || lower.contains("gmaps") -> PlatformType.GOOGLE_MAPS
            lower.contains("imdb") -> PlatformType.IMDB
            else -> PlatformType.GENERIC_WEB
        }
    }

    private fun detectOperation(lower: String): SpyOperation {
        return when {
            lower.contains("email") || lower.contains("business email") || lower.contains("mail") -> SpyOperation.PUBLIC_EMAIL_LOOKUP
            lower.contains("phone") || lower.contains("mobile") || lower.contains("number") || lower.contains("contact number") || lower.contains("call") -> SpyOperation.PUBLIC_PHONE_LOOKUP
            lower.contains("channel") || lower.contains("subscriber") || lower.contains("subscribers") -> SpyOperation.CHANNEL_DATA
            lower.contains("post") || lower.contains("posts") || lower.contains("tweet") || lower.contains("tweets") || lower.contains("feed") -> SpyOperation.POST_SEARCH
            lower.contains("video") || lower.contains("videos") || lower.contains("reel") || lower.contains("reels") || lower.contains("shorts") -> SpyOperation.PROFILE_VIDEOS
            lower.contains("photo") || lower.contains("photos") || lower.contains("media") || lower.contains("pictures") -> SpyOperation.PROFILE_MEDIA
            lower.contains("community") || lower.contains("subreddit") || lower.contains("group") -> SpyOperation.COMMUNITY_POSTS
            lower.contains("metric") || lower.contains("analytics") || lower.contains("stats") || lower.contains("views") || lower.contains("likes") -> SpyOperation.CONTENT_METRICS
            lower.contains("profile") || lower.contains("user") || lower.contains("account") || lower.contains("bio") || lower.contains("followers") || lower.contains("detail") -> SpyOperation.PROFILE_LOOKUP
            else -> SpyOperation.PROFILE_LOOKUP
        }
    }

    private fun extractRequestedFields(lower: String): List<String> {
        val fields = mutableListOf<String>()
        if (lower.contains("follower") || lower.contains("followers")) fields.add("followers")
        if (lower.contains("following")) fields.add("following")
        if (lower.contains("bio") || lower.contains("biography") || lower.contains("about")) fields.add("bio")
        if (lower.contains("email") || lower.contains("business email") || lower.contains("mail")) fields.add("email")
        if (lower.contains("phone") || lower.contains("mobile") || lower.contains("number") || lower.contains("contact")) fields.add("phone")
        if (lower.contains("posts") || lower.contains("photos") || lower.contains("videos") || lower.contains("media") || lower.contains("reels")) fields.add("media")
        if (lower.contains("subscriber") || lower.contains("subscribers")) fields.add("subscribers")
        if (lower.contains("views") || lower.contains("likes")) fields.add("metrics")
        if (lower.contains("website") || lower.contains("link") || lower.contains("links")) fields.add("website")
        if (lower.contains("detail") || lower.contains("details") || lower.contains("info")) fields.add("details")
        return fields
    }
}

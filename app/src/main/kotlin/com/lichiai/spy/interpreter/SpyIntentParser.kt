package com.lichiai.spy.interpreter

import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyLookupMode
import com.lichiai.spy.core.SpyOperation
import com.lichiai.spy.core.SpyPlatformRef
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.core.TargetType
import com.lichiai.spy.discovery.PlatformCapabilityInferencer
import com.lichiai.spy.model.PlatformCatalog
import java.util.Locale

/**
 * Parses user input queries following the `#Spy` trigger into structured [SpyTask] objects.
 * Supports flags:
 *  - `-U <username>` or `-u <username>`
 *  - `-full` or `-FULL` (activates FULL lookup mode)
 * Supports dynamic unknown platforms and multi-lingual query structures.
 */
object SpyIntentParser {

    private val FULL_FLAG_REGEX = Regex("(?:^|\\s)(?i)(?:--full|-full|—full|\\bfull\\b)")

    fun parse(
        cleanQuery: String,
        requestId: String = "",
        messageId: String = "",
        isFullScan: Boolean = false
    ): SpyTask {
        val hasFullFlag = isFullScan || FULL_FLAG_REGEX.containsMatchIn(cleanQuery)
        val lookupMode = if (hasFullFlag) SpyLookupMode.FULL else SpyLookupMode.SINGLE

        // Strip any leading #Spy / #spy / #SPY prefix and --full/-full flags so they don't pollute target extraction
        var clean = cleanQuery.trim()
        for (prefix in listOf("#spy", "#Spy", "#SPY")) {
            if (clean.startsWith(prefix, ignoreCase = true)) {
                clean = clean.substring(prefix.length).trim()
                break
            }
        }
        val queryWithoutFull = clean.replace(FULL_FLAG_REGEX, " ").trim()
        val lower = queryWithoutFull.lowercase(Locale.ROOT)

        val phoneTarget = TargetExtractor.extractPhoneNumber(queryWithoutFull)
        val emailTarget = TargetExtractor.extractEmail(queryWithoutFull)

        val explicitPlatform = detectPlatform(lower)

        val targetType = when {
            emailTarget != null -> TargetType.EMAIL
            phoneTarget != null && (explicitPlatform == null || isPhoneFocused(lower)) -> TargetType.PHONE_NUMBER
            queryWithoutFull.contains("http://") || queryWithoutFull.contains("https://") -> TargetType.URL
            queryWithoutFull.contains("r/") -> TargetType.SUBREDDIT
            else -> TargetType.HANDLE_OR_USERNAME
        }

        // 1. Resolve Platform & Dynamic Reference
        val (platform, dynamicRef) = if (explicitPlatform != null) {
            explicitPlatform to PlatformCapabilityInferencer.toPlatformRef(explicitPlatform.id)
        } else if (targetType == TargetType.PHONE_NUMBER || targetType == TargetType.EMAIL) {
            PlatformType.UNKNOWN to null
        } else {
            // Check dynamic platform name before target (e.g. "Bluesky axeel_dubin", "Facebook -U user")
            val dynamicName = detectDynamicPlatform(lower)
            if (dynamicName != null) {
                PlatformType.GENERIC_WEB to PlatformCapabilityInferencer.toPlatformRef(dynamicName)
            } else {
                PlatformType.GENERIC_WEB to null
            }
        }

        // 2. Resolve Operation
        val operation = if (lower.contains("preview") || lower.contains("profile preview")) {
            SpyOperation.PROFILE_PREVIEW
        } else if (targetType == TargetType.EMAIL) {
            SpyOperation.PUBLIC_EMAIL_LOOKUP
        } else if (targetType == TargetType.PHONE_NUMBER) {
            SpyOperation.PUBLIC_PHONE_LOOKUP
        } else {
            detectOperation(lower)
        }

        // 3. Extract Target using deterministic extractor
        val target = when (targetType) {
            TargetType.EMAIL -> emailTarget ?: ""
            TargetType.PHONE_NUMBER -> phoneTarget ?: ""
            else -> TargetExtractor.extract(queryWithoutFull, platform)
        }

        // 4. Extract requested fields
        val fields = extractRequestedFields(lower)

        // 5. Detect preview requested
        val previewRequested = lower.contains("preview") || lower.contains("overview") || lower.contains("summary") || lower.contains("details")

        return SpyTask(
            requestId = requestId,
            messageId = messageId,
            platform = platform,
            dynamicPlatformRef = dynamicRef,
            lookupMode = lookupMode,
            operation = operation,
            target = target,
            targetType = targetType,
            requestedFields = fields,
            rawQuery = cleanQuery,
            maxResults = 5,
            previewRequested = previewRequested
        )
    }

    private fun isPhoneFocused(lower: String): Boolean {
        return lower.contains("phone") || lower.contains("mobile") || lower.contains("number") ||
               lower.contains("contact") || lower.contains("sampark") || lower.contains("call")
    }

    private fun detectPlatform(lower: String): PlatformType? {
        val catalogMatch = PlatformCatalog.findByAlias(lower)
        if (catalogMatch != null) {
            return catalogMatch.platformType
        }

        return when {
            lower.contains("instagram") || lower.contains("insta ") || lower.contains("insta:") || lower.contains("insta.") -> PlatformType.INSTAGRAM
            lower.contains("youtube shorts") || lower.contains("yt shorts") -> PlatformType.YOUTUBE_SHORTS
            lower.contains("youtube") || lower.contains("yt ") || lower.contains("yt:") || lower.contains("yt.") -> PlatformType.YOUTUBE
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
            lower.contains("website") || lower.contains("site") || lower.contains("web ") || lower.contains("online") -> PlatformType.GENERIC_WEB
            else -> null
        }
    }

    private fun detectDynamicPlatform(lower: String): String? {
        val tokens = lower.split(Regex("[\\s,;:]+")).filter { it.isNotBlank() }
        for (token in tokens) {
            val clean = token.trim('-', '_')
            if (clean in setOf("bluesky", "bsky", "mastodon", "patreon", "twitch", "vimeo", "tumblr", "weibo", "line")) {
                return clean
            }
        }
        return null
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

package com.lichiai.spy.discovery

import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyCapability
import com.lichiai.spy.core.SpyPlatformRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.Locale

object PlatformCapabilityInferencer {

    fun inferPlatforms(
        name: String,
        title: String,
        description: String,
        readme: String?,
        categories: List<String> = emptyList(),
        inputSchemaJson: String? = null
    ): Set<String> {
        val text = "$name $title $description ${readme ?: ""} ${categories.joinToString(" ")} ${inputSchemaJson ?: ""}".lowercase(Locale.ROOT)
        val platforms = mutableSetOf<String>()

        val knownKeywords = mapOf(
            "instagram" to listOf("instagram", "instagr.am", "ig-", "insta "),
            "youtube" to listOf("youtube", "youtu.be", "yt-", "yt scraper"),
            "tiktok" to listOf("tiktok", "tik-tok", "douyin"),
            "twitter" to listOf("twitter", "x.com", "tweets", "tweet"),
            "facebook" to listOf("facebook", "fb.com", "meta graph"),
            "reddit" to listOf("reddit", "subreddit", "redditor"),
            "linkedin" to listOf("linkedin"),
            "github" to listOf("github", "git repo"),
            "gitlab" to listOf("gitlab"),
            "threads" to listOf("threads.net", "threads "),
            "pinterest" to listOf("pinterest"),
            "snapchat" to listOf("snapchat", "snap "),
            "telegram" to listOf("telegram", "t.me"),
            "discord" to listOf("discord"),
            "bluesky" to listOf("bluesky", "bsky"),
            "spotify" to listOf("spotify"),
            "soundcloud" to listOf("soundcloud"),
            "quora" to listOf("quora"),
            "medium" to listOf("medium.com"),
            "google_maps" to listOf("google maps", "gmaps", "google-places"),
            "imdb" to listOf("imdb")
        )

        for ((key, kwList) in knownKeywords) {
            if (kwList.any { text.contains(it) }) {
                platforms.add(key)
            }
        }

        if (platforms.isEmpty()) {
            // Check dynamic platform detection from name (e.g. "bluesky-profile-scraper" -> "bluesky")
            val nameParts = name.lowercase(Locale.ROOT).split("-", "_", "~", "/")
            if (nameParts.isNotEmpty() && nameParts.first().length in 3..20 && nameParts.first() !in setOf("actor", "apify", "scraper", "free", "api", "web")) {
                platforms.add(nameParts.first())
            } else {
                platforms.add("web")
            }
        }

        return platforms
    }

    fun inferCapabilities(
        name: String,
        title: String,
        description: String,
        readme: String?,
        categories: List<String> = emptyList(),
        inputSchema: JsonObject? = null,
        outputSchema: JsonObject? = null
    ): Set<SpyCapability> {
        val text = "$name $title $description ${readme ?: ""} ${categories.joinToString(" ")}".lowercase(Locale.ROOT)
        val caps = mutableSetOf<SpyCapability>()

        // 1. Profile / Overview
        if (text.contains("profile") || text.contains("user") || text.contains("account") || text.contains("bio") || text.contains("overview")) {
            caps.add(SpyCapability.PROFILE)
            caps.add(SpyCapability.DISPLAY_NAME)
            caps.add(SpyCapability.BIO)
            caps.add(SpyCapability.PROFILE_IMAGE)
        }

        // 2. Followers / Following
        if (text.contains("follower") || text.contains("following") || text.contains("subscriber") || text.contains("subscribers")) {
            caps.add(SpyCapability.FOLLOWER_COUNT)
            caps.add(SpyCapability.FOLLOWING_COUNT)
            if (text.contains("followers list") || text.contains("follower list") || text.contains("following list") || text.contains("scrape followers")) {
                caps.add(SpyCapability.FOLLOWERS_LIST)
                caps.add(SpyCapability.FOLLOWING_LIST)
            }
            if (text.contains("subscriber")) {
                caps.add(SpyCapability.SUBSCRIBER_COUNT)
            }
        }

        // 3. Posts / Videos / Media
        if (text.contains("post") || text.contains("feed") || text.contains("tweet") || text.contains("comment") || text.contains("media") || text.contains("photo") || text.contains("video") || text.contains("reel") || text.contains("short")) {
            caps.add(SpyCapability.POST_COUNT)
            caps.add(SpyCapability.POSTS)
            caps.add(SpyCapability.PUBLIC_MEDIA)
            if (text.contains("video") || text.contains("reel") || text.contains("shorts") || text.contains("youtube") || text.contains("tiktok")) {
                caps.add(SpyCapability.VIDEOS)
            }
        }

        // 4. Contact / Business Info
        if (text.contains("email") || text.contains("contact") || text.contains("phone") || text.contains("lead") || text.contains("business") || text.contains("website")) {
            caps.add(SpyCapability.PUBLIC_CONTACT)
            caps.add(SpyCapability.PUBLIC_EMAIL)
            caps.add(SpyCapability.PUBLIC_PHONE)
            caps.add(SpyCapability.PUBLIC_LINKS)
            caps.add(SpyCapability.WEBSITE)
            caps.add(SpyCapability.BUSINESS_DATA)
            caps.add(SpyCapability.PUBLIC_BUSINESS_EMAIL)
            caps.add(SpyCapability.PUBLIC_BUSINESS_PHONE)
        }

        // 5. Channel / Subreddit / Search
        if (text.contains("channel") || text.contains("youtube")) {
            caps.add(SpyCapability.CHANNEL_DATA)
            caps.add(SpyCapability.VIEW_COUNT)
        }
        if (text.contains("subreddit") || text.contains("reddit") || text.contains("community")) {
            caps.add(SpyCapability.COMMUNITY_DATA)
        }
        if (text.contains("search") || text.contains("query") || text.contains("dork")) {
            caps.add(SpyCapability.CONTENT_SEARCH)
        }

        // 6. Schema property inspection
        inputSchema?.let { schema ->
            val properties = schema["properties"]?.let { if (it is JsonObject) it else null } ?: schema
            val keys = properties.keys.map { it.lowercase(Locale.ROOT) }
            if (keys.any { it.contains("user") || it.contains("handle") || it.contains("profile") }) {
                caps.add(SpyCapability.PROFILE)
                caps.add(SpyCapability.DISPLAY_NAME)
            }
            if (keys.any { it.contains("follower") }) {
                caps.add(SpyCapability.FOLLOWER_COUNT)
                caps.add(SpyCapability.FOLLOWERS_LIST)
            }
            if (keys.any { it.contains("post") || it.contains("search") || it.contains("query") }) {
                caps.add(SpyCapability.POSTS)
                caps.add(SpyCapability.CONTENT_SEARCH)
            }
            if (keys.any { it.contains("email") || it.contains("phone") || it.contains("contact") }) {
                caps.add(SpyCapability.PUBLIC_CONTACT)
            }
        }

        if (caps.isEmpty()) {
            caps.add(SpyCapability.PROFILE)
            caps.add(SpyCapability.PUBLIC_METADATA)
        }

        return caps
    }

    fun calculateFingerprint(rawSchema: String?): String {
        if (rawSchema.isNullOrBlank()) return ""
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(rawSchema.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Exception) {
            rawSchema.hashCode().toString()
        }
    }

    fun toPlatformRef(platformKey: String): SpyPlatformRef {
        val cleanKey = platformKey.trim().lowercase(Locale.ROOT)
        val platformType = PlatformType.values().firstOrNull { it.id.equals(cleanKey, ignoreCase = true) }
        return if (platformType != null && platformType != PlatformType.UNKNOWN && platformType != PlatformType.GENERIC_WEB) {
            SpyPlatformRef(
                key = platformType.id,
                displayName = platformType.displayName,
                canonicalDomain = "${platformType.id}.com"
            )
        } else {
            SpyPlatformRef(
                key = cleanKey,
                displayName = cleanKey.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
                canonicalDomain = "$cleanKey.com"
            )
        }
    }
}

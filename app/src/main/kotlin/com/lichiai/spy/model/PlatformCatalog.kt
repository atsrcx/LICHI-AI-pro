package com.lichiai.spy.model

import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyOperation

/**
 * Predefined Platform Catalog providing structural capabilities, platform hints,
 * supported operations, and support statuses across major web and social platforms.
 */
object PlatformCatalog {

    val PLATFORMS: Map<PlatformType, PlatformDefinition> = listOf(
        // SOCIAL
        PlatformDefinition(
            platformType = PlatformType.INSTAGRAM,
            displayName = "Instagram",
            category = PlatformCategory.SOCIAL,
            supportStatus = PlatformSupportStatus.SUPPORTED,
            aliases = listOf("instagram", "insta", "ig"),
            websiteDomain = "instagram.com",
            preferredActorHints = listOf("apify~instagram-profile-scraper", "apify~instagram-scraper"),
            supportedOperations = listOf(
                SpyOperation.PROFILE_LOOKUP,
                SpyOperation.PROFILE_MEDIA,
                SpyOperation.PROFILE_POSTS,
                SpyOperation.PROFILE_FOLLOWERS_SUMMARY,
                SpyOperation.PUBLIC_EMAIL_LOOKUP,
                SpyOperation.PUBLIC_PHONE_LOOKUP
            ),
            profileFields = listOf("username", "fullName", "biography", "followersCount", "followsCount", "postsCount", "isVerified", "externalUrl", "latestPosts"),
            previewCapabilities = listOf("avatar", "bio", "stats", "media_grid", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.FACEBOOK,
            displayName = "Facebook",
            category = PlatformCategory.SOCIAL,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("facebook", "fb"),
            websiteDomain = "facebook.com",
            preferredActorHints = listOf("apify~facebook-pages-scraper", "apify~facebook-posts-scraper"),
            supportedOperations = listOf(
                SpyOperation.PROFILE_LOOKUP,
                SpyOperation.CONTENT_SEARCH,
                SpyOperation.PUBLIC_EMAIL_LOOKUP
            ),
            profileFields = listOf("name", "category", "likes", "followers", "about", "website"),
            previewCapabilities = listOf("avatar", "bio", "stats", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.TIKTOK,
            displayName = "TikTok",
            category = PlatformCategory.SOCIAL,
            supportStatus = PlatformSupportStatus.SUPPORTED,
            aliases = listOf("tiktok", "tt"),
            websiteDomain = "tiktok.com",
            preferredActorHints = listOf("clockworks~tiktok-profile-scraper", "clockworks~tiktok-scraper"),
            supportedOperations = listOf(
                SpyOperation.PROFILE_LOOKUP,
                SpyOperation.PROFILE_VIDEOS,
                SpyOperation.PROFILE_FOLLOWERS_SUMMARY
            ),
            profileFields = listOf("username", "nickname", "signature", "followerCount", "followingCount", "heartCount", "videoCount", "verified"),
            previewCapabilities = listOf("avatar", "bio", "stats", "video_thumbnails", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.TWITTER_X,
            displayName = "Twitter / X",
            category = PlatformCategory.SOCIAL,
            supportStatus = PlatformSupportStatus.SUPPORTED,
            aliases = listOf("twitter", "x.com", "tweet", "tweets"),
            websiteDomain = "x.com",
            preferredActorHints = listOf("apidojo~twitter-user-scraper", "apidojo~tweet-scraper"),
            supportedOperations = listOf(
                SpyOperation.PROFILE_LOOKUP,
                SpyOperation.PROFILE_POSTS,
                SpyOperation.CONTENT_SEARCH
            ),
            profileFields = listOf("name", "userName", "description", "followers", "following", "statusesCount", "isBlueVerified"),
            previewCapabilities = listOf("avatar", "bio", "stats", "recent_posts", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.THREADS,
            displayName = "Threads",
            category = PlatformCategory.SOCIAL,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("threads", "threads.net"),
            websiteDomain = "threads.net",
            preferredActorHints = listOf("apify~threads-scraper"),
            supportedOperations = listOf(
                SpyOperation.PROFILE_LOOKUP,
                SpyOperation.PROFILE_POSTS
            ),
            profileFields = listOf("username", "name", "bio", "followersCount"),
            previewCapabilities = listOf("avatar", "bio", "stats", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.SNAPCHAT,
            displayName = "Snapchat",
            category = PlatformCategory.SOCIAL,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("snapchat", "snap"),
            websiteDomain = "snapchat.com",
            preferredActorHints = listOf("apify~snapchat-scraper"),
            supportedOperations = listOf(SpyOperation.PROFILE_LOOKUP),
            profileFields = listOf("username", "displayName", "bio", "subscriberCount"),
            previewCapabilities = listOf("avatar", "bio", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.PINTEREST,
            displayName = "Pinterest",
            category = PlatformCategory.SOCIAL,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("pinterest", "pin"),
            websiteDomain = "pinterest.com",
            preferredActorHints = listOf("apify~pinterest-scraper"),
            supportedOperations = listOf(SpyOperation.PROFILE_LOOKUP),
            profileFields = listOf("username", "fullName", "about", "followersCount", "followingCount", "pinsCount"),
            previewCapabilities = listOf("avatar", "bio", "stats", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.LINKEDIN,
            displayName = "LinkedIn",
            category = PlatformCategory.SOCIAL,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("linkedin", "linkedin.com"),
            websiteDomain = "linkedin.com",
            preferredActorHints = listOf("apify~linkedin-profile-scraper"),
            supportedOperations = listOf(
                SpyOperation.PROFILE_LOOKUP,
                SpyOperation.PUBLIC_EMAIL_LOOKUP
            ),
            profileFields = listOf("name", "headline", "summary", "location", "connections", "followersCount"),
            previewCapabilities = listOf("avatar", "bio", "stats", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.REDDIT,
            displayName = "Reddit",
            category = PlatformCategory.FORUM,
            supportStatus = PlatformSupportStatus.SUPPORTED,
            aliases = listOf("reddit", "r/", "subreddit"),
            websiteDomain = "reddit.com",
            preferredActorHints = listOf("trudax~reddit-scraper-lite", "apify~reddit-scraper"),
            supportedOperations = listOf(
                SpyOperation.PROFILE_LOOKUP,
                SpyOperation.COMMUNITY_POSTS,
                SpyOperation.POST_SEARCH,
                SpyOperation.CONTENT_SEARCH
            ),
            profileFields = listOf("name", "title", "description", "karma", "subscribers", "activeUsers", "latestPosts"),
            previewCapabilities = listOf("avatar", "bio", "stats", "recent_posts", "direct_link")
        ),

        // VIDEO
        PlatformDefinition(
            platformType = PlatformType.YOUTUBE,
            displayName = "YouTube",
            category = PlatformCategory.VIDEO,
            supportStatus = PlatformSupportStatus.SUPPORTED,
            aliases = listOf("youtube", "yt"),
            websiteDomain = "youtube.com",
            preferredActorHints = listOf("streamers~youtube-scraper", "streamers~youtube-channel-scraper"),
            supportedOperations = listOf(
                SpyOperation.CHANNEL_DATA,
                SpyOperation.PROFILE_LOOKUP,
                SpyOperation.PROFILE_VIDEOS,
                SpyOperation.CONTENT_METRICS,
                SpyOperation.PUBLIC_EMAIL_LOOKUP
            ),
            profileFields = listOf("title", "channelName", "description", "subscriberCount", "videoCount", "viewCount", "joinedDate", "latestVideos"),
            previewCapabilities = listOf("avatar", "bio", "stats", "video_thumbnails", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.YOUTUBE_SHORTS,
            displayName = "YouTube Shorts",
            category = PlatformCategory.VIDEO,
            supportStatus = PlatformSupportStatus.SUPPORTED,
            aliases = listOf("shorts", "yt shorts"),
            websiteDomain = "youtube.com/shorts",
            preferredActorHints = listOf("streamers~youtube-scraper"),
            supportedOperations = listOf(SpyOperation.PROFILE_VIDEOS, SpyOperation.CONTENT_SEARCH),
            profileFields = listOf("title", "description", "views", "likes"),
            previewCapabilities = listOf("video_thumbnails", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.VIMEO,
            displayName = "Vimeo",
            category = PlatformCategory.VIDEO,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("vimeo"),
            websiteDomain = "vimeo.com",
            supportedOperations = listOf(SpyOperation.PROFILE_LOOKUP, SpyOperation.PROFILE_VIDEOS),
            profileFields = listOf("name", "bio", "videoCount", "followers"),
            previewCapabilities = listOf("avatar", "stats", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.TWITCH,
            displayName = "Twitch",
            category = PlatformCategory.VIDEO,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("twitch", "twitch.tv"),
            websiteDomain = "twitch.tv",
            preferredActorHints = listOf("apify~twitch-scraper"),
            supportedOperations = listOf(SpyOperation.PROFILE_LOOKUP, SpyOperation.CHANNEL_DATA),
            profileFields = listOf("displayName", "description", "followersCount", "gameName", "viewersCount"),
            previewCapabilities = listOf("avatar", "bio", "stats", "direct_link")
        ),

        // MUSIC
        PlatformDefinition(
            platformType = PlatformType.SPOTIFY,
            displayName = "Spotify",
            category = PlatformCategory.MUSIC,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("spotify"),
            websiteDomain = "spotify.com",
            preferredActorHints = listOf("apify~spotify-scraper"),
            supportedOperations = listOf(SpyOperation.PROFILE_LOOKUP, SpyOperation.CONTENT_SEARCH),
            profileFields = listOf("name", "bio", "monthlyListeners", "followers", "topTracks"),
            previewCapabilities = listOf("avatar", "stats", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.SOUNDCLOUD,
            displayName = "SoundCloud",
            category = PlatformCategory.MUSIC,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("soundcloud"),
            websiteDomain = "soundcloud.com",
            supportedOperations = listOf(SpyOperation.PROFILE_LOOKUP),
            profileFields = listOf("username", "fullName", "followersCount", "trackCount"),
            previewCapabilities = listOf("avatar", "stats", "direct_link")
        ),

        // MESSAGING & COMMUNITY
        PlatformDefinition(
            platformType = PlatformType.TELEGRAM,
            displayName = "Telegram",
            category = PlatformCategory.MESSAGING,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("telegram", "tg", "t.me"),
            websiteDomain = "t.me",
            preferredActorHints = listOf("apify~telegram-scraper"),
            supportedOperations = listOf(SpyOperation.PROFILE_LOOKUP, SpyOperation.COMMUNITY_POSTS),
            profileFields = listOf("title", "username", "description", "membersCount"),
            previewCapabilities = listOf("avatar", "bio", "stats", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.DISCORD,
            displayName = "Discord",
            category = PlatformCategory.MESSAGING,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("discord", "discord.gg"),
            websiteDomain = "discord.com",
            supportedOperations = listOf(SpyOperation.COMMUNITY_POSTS),
            profileFields = listOf("serverName", "description", "memberCount", "presenceCount"),
            previewCapabilities = listOf("avatar", "stats", "direct_link")
        ),

        // DEVELOPER
        PlatformDefinition(
            platformType = PlatformType.GITHUB,
            displayName = "GitHub",
            category = PlatformCategory.DEVELOPER,
            supportStatus = PlatformSupportStatus.SUPPORTED,
            aliases = listOf("github", "gh"),
            websiteDomain = "github.com",
            preferredActorHints = listOf("apify~github-user-scraper", "apify~github-repo-scraper"),
            supportedOperations = listOf(
                SpyOperation.PROFILE_LOOKUP,
                SpyOperation.CONTENT_SEARCH,
                SpyOperation.PUBLIC_EMAIL_LOOKUP
            ),
            profileFields = listOf("username", "name", "bio", "company", "location", "publicRepos", "followers", "following", "website", "email"),
            previewCapabilities = listOf("avatar", "bio", "stats", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.GITLAB,
            displayName = "GitLab",
            category = PlatformCategory.DEVELOPER,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("gitlab"),
            websiteDomain = "gitlab.com",
            supportedOperations = listOf(SpyOperation.PROFILE_LOOKUP),
            profileFields = listOf("username", "name", "bio", "publicProjects"),
            previewCapabilities = listOf("avatar", "stats", "direct_link")
        ),

        // FORUM & BLOG
        PlatformDefinition(
            platformType = PlatformType.QUORA,
            displayName = "Quora",
            category = PlatformCategory.FORUM,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("quora"),
            websiteDomain = "quora.com",
            supportedOperations = listOf(SpyOperation.PROFILE_LOOKUP, SpyOperation.CONTENT_SEARCH),
            profileFields = listOf("name", "bio", "followers", "answersCount"),
            previewCapabilities = listOf("avatar", "stats", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.MEDIUM,
            displayName = "Medium",
            category = PlatformCategory.BLOG,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("medium"),
            websiteDomain = "medium.com",
            preferredActorHints = listOf("apify~medium-scraper"),
            supportedOperations = listOf(SpyOperation.PROFILE_LOOKUP, SpyOperation.PROFILE_POSTS),
            profileFields = listOf("name", "username", "bio", "followersCount", "articles"),
            previewCapabilities = listOf("avatar", "bio", "stats", "direct_link")
        ),

        // BUSINESS & MEDIA
        PlatformDefinition(
            platformType = PlatformType.GOOGLE_MAPS,
            displayName = "Google Maps",
            category = PlatformCategory.BUSINESS,
            supportStatus = PlatformSupportStatus.SUPPORTED,
            aliases = listOf("google maps", "gmaps", "maps"),
            websiteDomain = "maps.google.com",
            preferredActorHints = listOf("compass~crawler-google-places"),
            supportedOperations = listOf(
                SpyOperation.PROFILE_LOOKUP,
                SpyOperation.PUBLIC_PHONE_LOOKUP,
                SpyOperation.PUBLIC_EMAIL_LOOKUP
            ),
            profileFields = listOf("title", "category", "address", "phone", "website", "totalScore", "reviewsCount", "openingHours"),
            previewCapabilities = listOf("avatar", "bio", "stats", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.IMDB,
            displayName = "IMDb",
            category = PlatformCategory.MEDIA,
            supportStatus = PlatformSupportStatus.PARTIAL,
            aliases = listOf("imdb"),
            websiteDomain = "imdb.com",
            preferredActorHints = listOf("apify~imdb-scraper"),
            supportedOperations = listOf(SpyOperation.PROFILE_LOOKUP, SpyOperation.CONTENT_SEARCH),
            profileFields = listOf("name", "bio", "rating", "credits"),
            previewCapabilities = listOf("avatar", "bio", "stats", "direct_link")
        ),

        // UNIVERSAL GENERIC WEB
        PlatformDefinition(
            platformType = PlatformType.GENERIC_WEB,
            displayName = "Universal Platform",
            category = PlatformCategory.UNIVERSAL,
            supportStatus = PlatformSupportStatus.SUPPORTED,
            aliases = listOf("web", "website", "online", "internet"),
            websiteDomain = "",
            supportedOperations = listOf(
                SpyOperation.PROFILE_LOOKUP,
                SpyOperation.CONTENT_SEARCH,
                SpyOperation.PUBLIC_EMAIL_LOOKUP,
                SpyOperation.PUBLIC_PHONE_LOOKUP
            ),
            profileFields = listOf("title", "description", "url", "contact"),
            previewCapabilities = listOf("avatar", "bio", "direct_link")
        ),
        PlatformDefinition(
            platformType = PlatformType.UNKNOWN,
            displayName = "Unknown / Multi-Source",
            category = PlatformCategory.UNIVERSAL,
            supportStatus = PlatformSupportStatus.SUPPORTED,
            aliases = emptyList(),
            websiteDomain = "",
            supportedOperations = listOf(
                SpyOperation.PUBLIC_PHONE_LOOKUP,
                SpyOperation.PUBLIC_CONTACT_LOOKUP,
                SpyOperation.PUBLIC_EMAIL_LOOKUP,
                SpyOperation.PROFILE_LOOKUP
            ),
            profileFields = listOf("title", "description", "contact"),
            previewCapabilities = listOf("avatar", "bio", "direct_link")
        )
    ).associateBy { it.platformType }

    fun findDefinition(platform: PlatformType): PlatformDefinition {
        return PLATFORMS[platform] ?: PLATFORMS[PlatformType.GENERIC_WEB] ?: PLATFORMS.values.first()
    }

    fun getDefinition(platform: PlatformType): PlatformDefinition = findDefinition(platform)

    fun findByAlias(query: String): PlatformDefinition? {
        val lower = query.lowercase().trim()
        if (lower.isBlank()) return null
        return PLATFORMS.values.firstOrNull { def ->
            def.aliases.any { alias ->
                if (alias.length <= 2) {
                    Regex("\\b${Regex.escape(alias)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
                } else {
                    lower.contains(alias)
                }
            }
        }
    }
}

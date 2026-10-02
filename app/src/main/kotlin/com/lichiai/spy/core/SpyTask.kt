package com.lichiai.spy.core

import kotlinx.serialization.Serializable

@Serializable
enum class PlatformType(val id: String, val displayName: String) {
    INSTAGRAM("instagram", "Instagram"),
    YOUTUBE("youtube", "YouTube"),
    YOUTUBE_SHORTS("youtube_shorts", "YouTube Shorts"),
    VIMEO("vimeo", "Vimeo"),
    TWITCH("twitch", "Twitch"),
    TIKTOK("tiktok", "TikTok"),
    TWITTER_X("twitter", "Twitter/X"),
    THREADS("threads", "Threads"),
    FACEBOOK("facebook", "Facebook"),
    LINKEDIN("linkedin", "LinkedIn"),
    REDDIT("reddit", "Reddit"),
    PINTEREST("pinterest", "Pinterest"),
    SNAPCHAT("snapchat", "Snapchat"),
    SPOTIFY("spotify", "Spotify"),
    SOUNDCLOUD("soundcloud", "SoundCloud"),
    TELEGRAM("telegram", "Telegram"),
    DISCORD("discord", "Discord"),
    GITHUB("github", "GitHub"),
    GITLAB("gitlab", "GitLab"),
    QUORA("quora", "Quora"),
    MEDIUM("medium", "Medium"),
    GOOGLE_MAPS("google_maps", "Google Maps"),
    IMDB("imdb", "IMDb"),
    GENERIC_WEB("web", "Universal Platform"),
    UNKNOWN("unknown", "Unknown")
}

@Serializable
enum class SpyOperation(val id: String, val description: String) {
    PROFILE_LOOKUP("profile_lookup", "Search and fetch public profile details"),
    POST_SEARCH("post_search", "Search public posts, comments, or threads"),
    PROFILE_SEARCH("profile_search", "Search public accounts and profiles"),
    PROFILE_POSTS("profile_posts", "Fetch recent public posts and updates"),
    PROFILE_VIDEOS("profile_videos", "Fetch recent public videos or reels"),
    PROFILE_MEDIA("profile_media", "Fetch recent public media thumbnails and links"),
    PROFILE_FOLLOWERS_SUMMARY("profile_followers", "Fetch follower count and metrics"),
    PROFILE_FOLLOWING_SUMMARY("profile_following", "Fetch following count and metrics"),
    PROFILE_ACTIVITY("profile_activity", "Fetch public activity and highlights"),
    PROFILE_LINKS("profile_links", "Fetch public websites and connected links"),
    PROFILE_PUBLIC_CONTACTS("profile_contacts", "Fetch publicly published business contact methods"),
    PUBLIC_CONTACT_LOOKUP("contact_lookup", "Look up publicly exposed business contact"),
    PUBLIC_EMAIL_LOOKUP("email_lookup", "Look up publicly exposed business contact email"),
    PUBLIC_PHONE_LOOKUP("phone_lookup", "Look up publicly exposed business contact phone number"),
    PROFILE_PREVIEW("profile_preview", "Fetch and render visual preview of profile"),
    CONTENT_SEARCH("content_search", "Search public topics, posts, and articles"),
    CONTENT_LOOKUP("content_lookup", "Fetch specific post or thread details"),
    CHANNEL_DATA("channel_data", "Fetch channel details, statistics, and videos"),
    CONTENT_METRICS("content_metrics", "Fetch public likes, views, followers, or metrics"),
    COMMUNITY_POSTS("community_posts", "Fetch recent community or subreddit posts")
}

@Serializable
enum class TargetType {
    HANDLE_OR_USERNAME,
    PHONE_NUMBER,
    EMAIL,
    URL,
    SUBREDDIT,
    SEARCH_QUERY
}

@Serializable
data class SpyPlatformRef(
    val key: String,
    val displayName: String,
    val canonicalDomain: String? = null
)

@Serializable
enum class SpyLookupMode {
    SINGLE,
    FULL
}

@Serializable
enum class SpyCapability(val id: String, val label: String) {
    PROFILE("profile", "Profile Overview"),
    PROFILE_IMAGE("profile_image", "Avatar & Image"),
    DISPLAY_NAME("display_name", "Display Name"),
    BIO("bio", "Biography / Description"),
    LOCATION("location", "Location"),
    WEBSITE("website", "Website & Links"),
    FOLLOWER_COUNT("follower_count", "Follower Count"),
    FOLLOWING_COUNT("following_count", "Following Count"),
    FOLLOWERS_LIST("followers_list", "Followers List"),
    FOLLOWING_LIST("following_list", "Following List"),
    POST_COUNT("post_count", "Post Count"),
    POSTS("posts", "Posts / Content"),
    VIDEOS("videos", "Videos / Reels / Shorts"),
    PUBLIC_MEDIA("public_media", "Public Media"),
    PUBLIC_METADATA("public_metadata", "Public Metadata"),
    PUBLIC_LINKS("public_links", "Public Links"),
    PUBLIC_EMAIL("public_email", "Public Business Email"),
    PUBLIC_PHONE("public_phone", "Public Business Phone"),
    SUBSCRIBER_COUNT("subscriber_count", "Subscriber Count"),
    VIEW_COUNT("view_count", "View Count / Metrics"),
    CONTENT_SEARCH("content_search", "Content Search"),
    CHANNEL_DATA("channel_data", "Channel Analytics"),
    COMMUNITY_DATA("community_data", "Community / Subreddit"),
    BUSINESS_DATA("business_data", "Business Registry"),
    PUBLIC_BUSINESS_PHONE("business_phone", "Public Business Phone"),
    PUBLIC_BUSINESS_EMAIL("business_email", "Public Business Email"),
    PUBLIC_CONTACT("public_contact", "Public Contacts")
}

@Serializable
data class SpyTask(
    val taskId: String = java.util.UUID.randomUUID().toString(),
    val requestId: String = "",
    val messageId: String = "",
    val platform: PlatformType = PlatformType.GENERIC_WEB,
    val dynamicPlatformRef: SpyPlatformRef? = null,
    val lookupMode: SpyLookupMode = SpyLookupMode.SINGLE,
    val operation: SpyOperation = SpyOperation.PROFILE_LOOKUP,
    val target: String = "",
    val targetType: TargetType = TargetType.HANDLE_OR_USERNAME,
    val requestedFields: List<String> = emptyList(),
    val rawQuery: String = "",
    val maxResults: Int = 5,
    val requiresPaidExecution: Boolean = false,
    val previewRequested: Boolean = true
)

@Serializable
enum class SpyTaskState {
    IDLE,
    PARSING_INTENT,
    DISCOVERING_ACTORS,
    ACTOR_SELECTED,
    INTERPRETING_SCHEMA,
    STARTING_RUN,
    RUNNING_ACTOR,
    FETCHING_DATASET,
    FETCHING_KEY_VALUE,
    NORMALIZING_RESULTS,
    VERIFYING_RESULTS,
    GENERATING_RESPONSE,
    COMPLETED,
    FAILED,
    CANCELLED
}

package com.lichiai.spy.model

enum class SpyPlatform {
    INSTAGRAM, TWITTER, YOUTUBE, GITHUB, LINKEDIN, PHONE_DIRECTORY, EMAIL_DIRECTORY, GENERIC_WEB
}

enum class SpyOperation {
    PROFILE_LOOKUP, PROFILE_SEARCH, PROFILE_POSTS, PUBLIC_PHONE_LOOKUP, PUBLIC_EMAIL_LOOKUP, GENERAL_SEARCH
}

data class SpyTask(
    val platform: SpyPlatform,
    val operation: SpyOperation,
    val target: String,
    val isFullScan: Boolean = false,
    val rawQuery: String = ""
)

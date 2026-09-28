package com.lichiai.ui.spy

import com.lichiai.spy.model.PlatformProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SpyProfileSerializer {

    private const val TAG_PREFIX = "<!--LICHI_SPY_PROFILE:"
    private const val TAG_SUFFIX = "-->"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun embedProfile(profile: PlatformProfile, markdownText: String): String {
        return try {
            val encoded = json.encodeToString(profile)
            "$TAG_PREFIX$encoded$TAG_SUFFIX\n\n$markdownText"
        } catch (_: Exception) {
            markdownText
        }
    }

    fun extractProfile(content: String): PlatformProfile? {
        if (!content.contains(TAG_PREFIX)) return null
        return try {
            val start = content.indexOf(TAG_PREFIX) + TAG_PREFIX.length
            val end = content.indexOf(TAG_SUFFIX, start)
            if (start in 0..<end) {
                val jsonStr = content.substring(start, end)
                json.decodeFromString<PlatformProfile>(jsonStr)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun stripEmbeddedProfile(content: String): String {
        if (!content.contains(TAG_PREFIX)) return content
        val start = content.indexOf(TAG_PREFIX)
        val end = content.indexOf(TAG_SUFFIX, start)
        return if (start != -1 && end != -1) {
            val after = content.substring(end + TAG_SUFFIX.length).trimStart()
            after
        } else content
    }
}

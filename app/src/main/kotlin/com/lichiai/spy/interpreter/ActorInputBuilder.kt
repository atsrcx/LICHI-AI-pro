package com.lichiai.spy.interpreter

import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyOperation
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.discovery.ActorMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Constructs valid JSON input payloads for Apify Actors.
 * Dynamically analyzes Actor schema/example inputs and maps standard platform/operation semantics.
 */
object ActorInputBuilder {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun buildInput(task: SpyTask, actor: ActorMetadata): JsonObject {
        val cleanTarget = TargetExtractor.normalizeUsername(task.target, task.platform)
        val actorName = actor.name.lowercase()

        // 1. Check if Actor metadata contains example input structure
        val exampleObj = try {
            if (!actor.exampleInputJson.isNullOrBlank()) {
                json.parseToJsonElement(actor.exampleInputJson).jsonObject
            } else null
        } catch (_: Exception) {
            null
        }

        if (exampleObj != null) {
            val dynamicPayload = buildFromExampleSchema(cleanTarget, task, exampleObj)
            if (dynamicPayload != null) {
                return dynamicPayload
            }
        }

        // 2. Semantic Mapping per platform & operation
        return when {
            // Instagram Scrapers
            actorName.contains("instagram") -> {
                buildJsonObject {
                    putJsonArray("usernames") {
                        add(JsonPrimitive(cleanTarget))
                    }
                    put("resultsLimit", task.maxResults)
                    put("resultsType", "details")
                }
            }

            // YouTube Scrapers
            actorName.contains("youtube") -> {
                val isChannel = task.operation == SpyOperation.CHANNEL_DATA || cleanTarget.contains("channel") || cleanTarget.contains("@")
                buildJsonObject {
                    putJsonArray("searchKeywords") {
                        add(JsonPrimitive(cleanTarget))
                    }
                    if (isChannel) {
                        putJsonArray("startUrls") {
                            val channelUrl = if (cleanTarget.startsWith("http")) cleanTarget else "https://www.youtube.com/@$cleanTarget"
                            add(buildJsonObject { put("url", channelUrl) })
                        }
                    }
                    put("maxResults", task.maxResults)
                    put("maxResultsShorts", 0)
                }
            }

            // Reddit Scrapers
            actorName.contains("reddit") -> {
                buildJsonObject {
                    val subName = cleanTarget.removePrefix("r/").removePrefix("/")
                    putJsonArray("subreddits") {
                        add(JsonPrimitive(subName))
                    }
                    putJsonArray("searches") {
                        add(JsonPrimitive(task.rawQuery))
                    }
                    put("maxItems", task.maxResults)
                    put("sort", "top")
                    put("time", "all")
                }
            }

            // TikTok Scrapers
            actorName.contains("tiktok") -> {
                buildJsonObject {
                    putJsonArray("profiles") {
                        add(JsonPrimitive(cleanTarget))
                    }
                    put("resultsPerPage", task.maxResults)
                }
            }

            // Twitter / X Scrapers
            actorName.contains("twitter") || actorName.contains("tweet") -> {
                buildJsonObject {
                    putJsonArray("handles") {
                        add(JsonPrimitive(cleanTarget))
                    }
                    putJsonArray("queries") {
                        add(JsonPrimitive(task.rawQuery))
                    }
                    put("maxItems", task.maxResults)
                }
            }

            // GitHub Scrapers
            actorName.contains("github") -> {
                buildJsonObject {
                    putJsonArray("usernames") {
                        add(JsonPrimitive(cleanTarget))
                    }
                    put("maxItems", task.maxResults)
                }
            }

            // Universal fallback
            else -> {
                buildJsonObject {
                    put("query", cleanTarget)
                    put("search", cleanTarget)
                    putJsonArray("queries") {
                        add(JsonPrimitive(cleanTarget))
                    }
                    put("maxItems", task.maxResults)
                    put("limit", task.maxResults)
                }
            }
        }
    }

    private fun buildFromExampleSchema(cleanTarget: String, task: SpyTask, example: JsonObject): JsonObject? {
        val keys = example.keys
        var mapped = false

        val builder = buildJsonObject {
            for (key in keys) {
                when {
                    key.equals("usernames", ignoreCase = true) -> {
                        putJsonArray(key) { add(JsonPrimitive(cleanTarget)) }
                        mapped = true
                    }
                    key.equals("username", ignoreCase = true) -> {
                        put(key, cleanTarget)
                        mapped = true
                    }
                    key.equals("handles", ignoreCase = true) -> {
                        putJsonArray(key) { add(JsonPrimitive(cleanTarget)) }
                        mapped = true
                    }
                    key.equals("profiles", ignoreCase = true) -> {
                        putJsonArray(key) { add(JsonPrimitive(cleanTarget)) }
                        mapped = true
                    }
                    key.equals("directUrls", ignoreCase = true) || key.equals("startUrls", ignoreCase = true) -> {
                        val canonicalUrl = when (task.platform) {
                            PlatformType.INSTAGRAM -> "https://www.instagram.com/$cleanTarget/"
                            PlatformType.YOUTUBE -> "https://www.youtube.com/@$cleanTarget"
                            PlatformType.REDDIT -> "https://www.reddit.com/r/$cleanTarget"
                            PlatformType.TIKTOK -> "https://www.tiktok.com/@$cleanTarget"
                            PlatformType.TWITTER_X -> "https://twitter.com/$cleanTarget"
                            PlatformType.GITHUB -> "https://github.com/$cleanTarget"
                            else -> cleanTarget
                        }
                        putJsonArray(key) {
                            add(buildJsonObject { put("url", canonicalUrl) })
                        }
                        mapped = true
                    }
                    key.equals("resultsLimit", ignoreCase = true) || key.equals("maxResults", ignoreCase = true) || key.equals("maxItems", ignoreCase = true) -> {
                        put(key, task.maxResults)
                    }
                    key.equals("resultsType", ignoreCase = true) -> {
                        put(key, "details")
                    }
                }
            }
        }

        return if (mapped) builder else null
    }
}

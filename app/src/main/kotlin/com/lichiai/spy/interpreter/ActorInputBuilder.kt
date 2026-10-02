package com.lichiai.spy.interpreter

import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyError
import com.lichiai.spy.core.SpyOperation
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.core.TargetType
import com.lichiai.spy.discovery.ActorMetadata
import com.lichiai.spy.registry.SpyProviderEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/**
 * Robust Schema-Driven Input Builder for Apify Actors.
 * Analyzes formal JSON Schemas and Example Inputs to deterministically
 * generate valid, compliant execution payloads.
 */
object ActorInputBuilder {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun buildInput(
        task: SpyTask,
        schemaJson: String?,
        exampleInputJson: String?,
        fallbackActorName: String = ""
    ): JsonObject {
        val cleanTarget = TargetExtractor.normalizeUsername(task.target, task.platform)

        // 1. Primary: Formal JSON Schema mapping
        if (!schemaJson.isNullOrBlank()) {
            val fromSchema = buildFromJsonSchema(cleanTarget, task, schemaJson)
            if (fromSchema != null && fromSchema.isNotEmpty()) {
                return fromSchema
            }
        }

        // 2. Secondary: Example Input mapping
        if (!exampleInputJson.isNullOrBlank()) {
            val fromExample = buildFromExample(cleanTarget, task, exampleInputJson)
            if (fromExample != null && fromExample.isNotEmpty()) {
                return fromExample
            }
        }

        // 3. Fallback: Semantic heuristic mapping based on task platform & target
        return buildHeuristicFallback(cleanTarget, task, fallbackActorName)
    }

    fun buildInput(task: SpyTask, provider: SpyProviderEntity): JsonObject {
        return buildInput(
            task = task,
            schemaJson = provider.inputSchemaJson,
            exampleInputJson = provider.exampleInputJson,
            fallbackActorName = provider.actorName
        )
    }

    fun buildInput(task: SpyTask, actor: ActorMetadata): JsonObject {
        return buildInput(
            task = task,
            schemaJson = null,
            exampleInputJson = actor.exampleInputJson,
            fallbackActorName = actor.name
        )
    }

    private fun buildFromJsonSchema(cleanTarget: String, task: SpyTask, schemaRaw: String): JsonObject? {
        return try {
            val root = json.parseToJsonElement(schemaRaw).jsonObject
            val properties = when {
                root.containsKey("properties") && root["properties"] is JsonObject -> root["properties"]!!.jsonObject
                root.containsKey("input") && root["input"] is JsonObject -> {
                    val inputObj = root["input"]!!.jsonObject
                    if (inputObj.containsKey("properties") && inputObj["properties"] is JsonObject) {
                        inputObj["properties"]!!.jsonObject
                    } else inputObj
                }
                else -> root
            }

            var hasMappedTarget = false
            val canonicalUrl = buildCanonicalProfileUrl(cleanTarget, task.platform)

            val builder = buildJsonObject {
                for ((propKey, propDef) in properties) {
                    val propObj = if (propDef is JsonObject) propDef else JsonObject(emptyMap())
                    val type = propObj["type"]?.jsonPrimitive?.content?.lowercase(Locale.ROOT) ?: ""
                    val lowerKey = propKey.lowercase(Locale.ROOT)

                    when {
                        // Usernames / Handles / Profiles Array
                        lowerKey in setOf("usernames", "username_list", "users", "handles", "profiles", "accounts", "targets") && (type == "array" || type.isEmpty()) -> {
                            putJsonArray(propKey) { add(JsonPrimitive(cleanTarget)) }
                            hasMappedTarget = true
                        }

                        // Username / Handle / Profile Scalar String
                        lowerKey in setOf("username", "username_or_url", "user", "handle", "profile", "account", "target") && (type == "string" || type.isEmpty()) -> {
                            put(propKey, cleanTarget)
                            hasMappedTarget = true
                        }

                        // URLs / DirectUrls / StartUrls Array or Object
                        lowerKey in setOf("starturls", "start_urls", "directurls", "direct_urls", "urls", "profileurls", "links") -> {
                            val itemsType = propObj["items"]?.let { if (it is JsonObject) it.jsonObject else null }
                            val itemType = itemsType?.get("type")?.jsonPrimitive?.content ?: ""

                            if (itemType == "object" || itemsType?.containsKey("properties") == true) {
                                putJsonArray(propKey) {
                                    add(buildJsonObject { put("url", canonicalUrl) })
                                }
                            } else {
                                putJsonArray(propKey) {
                                    add(JsonPrimitive(canonicalUrl))
                                }
                            }
                            hasMappedTarget = true
                        }

                        // Single URL String
                        lowerKey in setOf("url", "profileurl", "profile_url", "targeturl", "link") && (type == "string" || type.isEmpty()) -> {
                            put(propKey, canonicalUrl)
                            hasMappedTarget = true
                        }

                        // Search Query / Queries
                        lowerKey in setOf("searchqueries", "queries", "searchstrings", "searchkeywords", "keywords", "searches") -> {
                            val queryValue = if (task.rawQuery.isNotBlank() && task.rawQuery != cleanTarget) task.rawQuery else cleanTarget
                            putJsonArray(propKey) { add(JsonPrimitive(queryValue)) }
                            hasMappedTarget = true
                        }

                        lowerKey in setOf("query", "searchquery", "search", "search_query", "keyword", "searchstring", "q") && (type == "string" || type.isEmpty()) -> {
                            val queryValue = if (task.rawQuery.isNotBlank() && task.rawQuery != cleanTarget) task.rawQuery else cleanTarget
                            put(propKey, queryValue)
                            hasMappedTarget = true
                        }

                        // Subreddits
                        lowerKey in setOf("subreddits", "subs") -> {
                            val subName = cleanTarget.removePrefix("r/").removePrefix("/")
                            putJsonArray(propKey) { add(JsonPrimitive(subName)) }
                            hasMappedTarget = true
                        }

                        lowerKey in setOf("subreddit", "sub") && (type == "string" || type.isEmpty()) -> {
                            val subName = cleanTarget.removePrefix("r/").removePrefix("/")
                            put(propKey, subName)
                            hasMappedTarget = true
                        }

                        // Channels
                        lowerKey in setOf("channels", "channelurls") -> {
                            putJsonArray(propKey) { add(JsonPrimitive(canonicalUrl)) }
                            hasMappedTarget = true
                        }

                        lowerKey in setOf("channel", "channelurl") && (type == "string" || type.isEmpty()) -> {
                            put(propKey, canonicalUrl)
                            hasMappedTarget = true
                        }

                        // Email & Phone
                        lowerKey in setOf("email", "emails") -> {
                            if (type == "array") {
                                putJsonArray(propKey) { add(JsonPrimitive(cleanTarget)) }
                            } else {
                                put(propKey, cleanTarget)
                            }
                            hasMappedTarget = true
                        }

                        lowerKey in setOf("phone", "phonenumber", "phone_number", "phones") -> {
                            if (type == "array") {
                                putJsonArray(propKey) { add(JsonPrimitive(cleanTarget)) }
                            } else {
                                put(propKey, cleanTarget)
                            }
                            hasMappedTarget = true
                        }

                        // Limits & Max Items
                        lowerKey in setOf("maxitems", "max_items", "maxresults", "max_results", "resultslimit", "limit", "count", "resultsperpage") -> {
                            put(propKey, task.maxResults)
                        }

                        // Schema defaults
                        propObj.containsKey("default") -> {
                            propObj["default"]?.let { put(propKey, it) }
                        }

                        propObj.containsKey("prefill") -> {
                            propObj["prefill"]?.let { put(propKey, it) }
                        }
                    }
                }
            }

            if (hasMappedTarget) builder else null
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFromExample(cleanTarget: String, task: SpyTask, exampleRaw: String): JsonObject? {
        return try {
            val example = json.parseToJsonElement(exampleRaw).jsonObject
            val canonicalUrl = buildCanonicalProfileUrl(cleanTarget, task.platform)
            var mapped = false

            val builder = buildJsonObject {
                for ((key, element) in example) {
                    val lowerKey = key.lowercase(Locale.ROOT)
                    when {
                        lowerKey in setOf("usernames", "users", "handles", "profiles", "accounts") -> {
                            putJsonArray(key) { add(JsonPrimitive(cleanTarget)) }
                            mapped = true
                        }
                        lowerKey in setOf("username", "user", "handle", "profile", "account") -> {
                            put(key, cleanTarget)
                            mapped = true
                        }
                        lowerKey in setOf("directurls", "starturls", "urls", "profileurls") -> {
                            if (element is JsonArray && element.firstOrNull() is JsonObject) {
                                putJsonArray(key) {
                                    add(buildJsonObject { put("url", canonicalUrl) })
                                }
                            } else {
                                putJsonArray(key) { add(JsonPrimitive(canonicalUrl)) }
                            }
                            mapped = true
                        }
                        lowerKey in setOf("url", "profileurl") -> {
                            put(key, canonicalUrl)
                            mapped = true
                        }
                        lowerKey in setOf("queries", "searches", "searchkeywords") -> {
                            putJsonArray(key) { add(JsonPrimitive(cleanTarget)) }
                            mapped = true
                        }
                        lowerKey in setOf("query", "search", "searchquery") -> {
                            put(key, cleanTarget)
                            mapped = true
                        }
                        lowerKey in setOf("subreddits") -> {
                            val subName = cleanTarget.removePrefix("r/").removePrefix("/")
                            putJsonArray(key) { add(JsonPrimitive(subName)) }
                            mapped = true
                        }
                        lowerKey in setOf("resultsperpage", "maxresults", "maxitems", "resultslimit", "limit") -> {
                            put(key, task.maxResults)
                        }
                        else -> {
                            put(key, element)
                        }
                    }
                }
            }

            if (mapped) builder else null
        } catch (_: Exception) {
            null
        }
    }

    private fun buildHeuristicFallback(cleanTarget: String, task: SpyTask, actorName: String): JsonObject {
        val name = actorName.lowercase(Locale.ROOT)
        val canonicalUrl = buildCanonicalProfileUrl(cleanTarget, task.platform)

        return when {
            name.contains("instagram") || task.platform == PlatformType.INSTAGRAM -> {
                buildJsonObject {
                    putJsonArray("usernames") { add(JsonPrimitive(cleanTarget)) }
                    put("resultsLimit", task.maxResults)
                    put("resultsType", "details")
                }
            }
            name.contains("youtube") || task.platform == PlatformType.YOUTUBE -> {
                buildJsonObject {
                    putJsonArray("startUrls") {
                        add(buildJsonObject { put("url", canonicalUrl) })
                    }
                    putJsonArray("searchKeywords") { add(JsonPrimitive(cleanTarget)) }
                    put("maxResults", task.maxResults)
                }
            }
            name.contains("reddit") || task.platform == PlatformType.REDDIT -> {
                val sub = cleanTarget.removePrefix("r/").removePrefix("/")
                buildJsonObject {
                    putJsonArray("subreddits") { add(JsonPrimitive(sub)) }
                    put("maxItems", task.maxResults)
                }
            }
            name.contains("tiktok") || task.platform == PlatformType.TIKTOK -> {
                buildJsonObject {
                    putJsonArray("profiles") { add(JsonPrimitive(cleanTarget)) }
                    put("resultsPerPage", task.maxResults)
                }
            }
            name.contains("twitter") || task.platform == PlatformType.TWITTER_X -> {
                buildJsonObject {
                    putJsonArray("handles") { add(JsonPrimitive(cleanTarget)) }
                    put("maxItems", task.maxResults)
                }
            }
            name.contains("github") || task.platform == PlatformType.GITHUB -> {
                buildJsonObject {
                    putJsonArray("usernames") { add(JsonPrimitive(cleanTarget)) }
                    put("maxItems", task.maxResults)
                }
            }
            task.operation == SpyOperation.PUBLIC_PHONE_LOOKUP || task.targetType == TargetType.PHONE_NUMBER || name.contains("phone") || name.contains("contact") -> {
                buildJsonObject {
                    putJsonArray("queries") { add(JsonPrimitive(cleanTarget)) }
                    put("query", cleanTarget)
                    put("searchTerm", cleanTarget)
                    put("phone", cleanTarget)
                    put("maxResults", task.maxResults)
                }
            }
            else -> {
                buildJsonObject {
                    put("username", cleanTarget)
                    put("query", cleanTarget)
                    putJsonArray("queries") { add(JsonPrimitive(cleanTarget)) }
                    put("maxItems", task.maxResults)
                }
            }
        }
    }

    private fun buildCanonicalProfileUrl(target: String, platform: PlatformType): String {
        if (target.startsWith("http://") || target.startsWith("https://")) return target
        return when (platform) {
            PlatformType.INSTAGRAM -> "https://www.instagram.com/$target/"
            PlatformType.YOUTUBE -> "https://www.youtube.com/@$target"
            PlatformType.REDDIT -> "https://www.reddit.com/r/$target"
            PlatformType.TIKTOK -> "https://www.tiktok.com/@$target"
            PlatformType.TWITTER_X -> "https://twitter.com/$target"
            PlatformType.GITHUB -> "https://github.com/$target"
            PlatformType.LINKEDIN -> "https://www.linkedin.com/in/$target"
            PlatformType.FACEBOOK -> "https://www.facebook.com/$target"
            PlatformType.SPOTIFY -> "https://open.spotify.com/user/$target"
            else -> "https://$target"
        }
    }
}

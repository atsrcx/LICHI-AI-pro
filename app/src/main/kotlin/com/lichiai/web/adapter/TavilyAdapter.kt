package com.lichiai.web.adapter

import com.lichiai.web.model.ImageResult
import com.lichiai.web.model.WebCapability
import com.lichiai.web.model.WebProviderType
import com.lichiai.web.model.WebResult
import com.lichiai.web.model.WebSearchQuery
import com.lichiai.web.model.WebSearchResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

@Serializable
private data class TavilySearchRequest(
    val query: String,
    @SerialName("search_depth") val searchDepth: String = "basic",
    @SerialName("include_answer") val includeAnswer: Boolean = true,
    @SerialName("include_images") val includeImages: Boolean = true,
    @SerialName("include_raw_content") val includeRawContent: Boolean = false,
    @SerialName("max_results") val maxResults: Int = 8,
    val topic: String = "general"
)

@Serializable
private data class TavilySearchResponse(
    val answer: String? = null,
    val query: String? = null,
    val results: List<TavilyResultItem> = emptyList(),
    val images: List<JsonElement> = emptyList()
)

@Serializable
private data class TavilyResultItem(
    val title: String? = null,
    val url: String,
    val content: String? = null,
    val score: Float? = null,
    @SerialName("published_date") val publishedDate: String? = null,
    @SerialName("raw_content") val rawContent: String? = null
)

@Serializable
private data class TavilyExtractRequest(
    val urls: List<String>
)

@Serializable
private data class TavilyExtractResponse(
    val results: List<TavilyExtractItem> = emptyList()
)

@Serializable
private data class TavilyExtractItem(
    val url: String,
    @SerialName("raw_content") val rawContent: String? = null
)

class TavilyAdapter(private val httpClient: HttpClient) : WebSearchProvider {

    override val providerType: WebProviderType = WebProviderType.TAVILY

    override val supportedCapabilities: Set<WebCapability> = setOf(
        WebCapability.WEB_SEARCH,
        WebCapability.NEWS_SEARCH,
        WebCapability.IMAGE_SEARCH,
        WebCapability.CONTENT_EXTRACTION,
        WebCapability.DEEP_RESEARCH,
        WebCapability.DIRECT_ANSWER
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    override suspend fun search(
        query: WebSearchQuery,
        apiKey: String,
        onProgress: suspend (statusMsg: String, domains: List<String>) -> Unit
    ): WebSearchResponse {
        val startTime = System.currentTimeMillis()
        if (apiKey.isBlank()) {
            throw IllegalArgumentException("Tavily API key is missing. Configure it in Settings > Web Search.")
        }

        onProgress("Connecting to Tavily search engine...", emptyList())

        val reqBody = TavilySearchRequest(
            query = query.query,
            searchDepth = if (query.isDeep) "advanced" else "basic",
            includeAnswer = true,
            includeImages = query.includeImages,
            includeRawContent = query.includeContent,
            maxResults = query.maxResults,
            topic = "general"
        )

        val response = httpClient.post("https://api.tavily.com/search") {
            contentType(ContentType.Application.Json)
            headers {
                append(HttpHeaders.Authorization, "Bearer ${apiKey.trim()}")
            }
            setBody(json.encodeToString(TavilySearchRequest.serializer(), reqBody))
        }

        if (!response.status.isSuccess()) {
            val err = response.bodyAsText().take(300)
            throw RuntimeException("Tavily API error (${response.status.value}): $err")
        }

        val rawText = response.bodyAsText()
        val parsed = json.decodeFromString(TavilySearchResponse.serializer(), rawText)

        val domains = parsed.results.mapNotNull {
            runCatching { URI(it.url).host }.getOrNull()
        }.distinct()

        onProgress("Gathered ${parsed.results.size} sources...", domains)

        val normalizedResults = parsed.results.mapIndexed { index, item ->
            val host = runCatching { URI(item.url).host?.removePrefix("www.") }.getOrNull() ?: "tavily.com"
            WebResult(
                title = item.title?.takeIf { it.isNotBlank() } ?: host,
                url = item.url,
                domain = host,
                snippet = item.content ?: "No description provided.",
                content = item.rawContent ?: item.content,
                publishedAt = item.publishedDate,
                sourceName = host,
                sourceProvider = "Tavily",
                relevance = item.score,
                citationId = index + 1
            )
        }

        val normalizedImages = mutableListOf<ImageResult>()
        for (imgEl in parsed.images) {
            when (imgEl) {
                is JsonPrimitive -> {
                    val urlStr = imgEl.content
                    if (urlStr.startsWith("http")) {
                        val host = runCatching { URI(urlStr).host?.removePrefix("www.") }.getOrNull() ?: "web"
                        normalizedImages.add(
                            ImageResult(
                                title = query.query,
                                imageUrl = urlStr,
                                thumbnailUrl = urlStr,
                                sourceUrl = urlStr,
                                sourceDomain = host,
                                provider = "Tavily"
                            )
                        )
                    }
                }
                is JsonObject -> {
                    val url = imgEl["url"]?.let { if (it is JsonPrimitive) it.content else null }
                    val desc = imgEl["description"]?.let { if (it is JsonPrimitive) it.content else null }
                    if (!url.isNullOrBlank() && url.startsWith("http")) {
                        val host = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: "web"
                        normalizedImages.add(
                            ImageResult(
                                title = desc ?: query.query,
                                imageUrl = url,
                                thumbnailUrl = url,
                                sourceUrl = url,
                                sourceDomain = host,
                                provider = "Tavily"
                            )
                        )
                    }
                }
                else -> Unit
            }
        }

        return WebSearchResponse(
            query = query.query,
            providerUsed = "Tavily",
            results = normalizedResults,
            images = normalizedImages,
            directAnswer = parsed.answer,
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }

    override suspend fun searchNews(query: String, count: Int, apiKey: String): List<WebResult> {
        val reqBody = TavilySearchRequest(
            query = query,
            searchDepth = "basic",
            includeAnswer = false,
            includeImages = false,
            maxResults = count,
            topic = "news"
        )
        val response = httpClient.post("https://api.tavily.com/search") {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer ${apiKey.trim()}") }
            setBody(json.encodeToString(TavilySearchRequest.serializer(), reqBody))
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("Tavily news error: ${response.status.value}")
        }
        val parsed = json.decodeFromString(TavilySearchResponse.serializer(), response.bodyAsText())
        return parsed.results.mapIndexed { index, item ->
            val host = runCatching { URI(item.url).host?.removePrefix("www.") }.getOrNull() ?: "news"
            WebResult(
                title = item.title ?: host,
                url = item.url,
                domain = host,
                snippet = item.content ?: "",
                publishedAt = item.publishedDate,
                sourceName = host,
                sourceProvider = "Tavily News",
                citationId = index + 1,
                resultType = "news"
            )
        }
    }

    override suspend fun searchImages(query: String, count: Int, apiKey: String): List<ImageResult> {
        val res = search(WebSearchQuery(query = query, maxResults = count, includeImages = true), apiKey)
        return res.images
    }

    override suspend fun extractContent(urls: List<String>, apiKey: String): Map<String, String> {
        if (urls.isEmpty()) return emptyMap()
        val reqBody = TavilyExtractRequest(urls = urls.take(5))
        val response = httpClient.post("https://api.tavily.com/extract") {
            contentType(ContentType.Application.Json)
            headers { append(HttpHeaders.Authorization, "Bearer ${apiKey.trim()}") }
            setBody(json.encodeToString(TavilyExtractRequest.serializer(), reqBody))
        }
        if (!response.status.isSuccess()) return emptyMap()
        val parsed = json.decodeFromString(TavilyExtractResponse.serializer(), response.bodyAsText())
        return parsed.results.associate { it.url to (it.rawContent ?: "") }
    }

    override suspend fun testConnection(apiKey: String): Result<String> {
        return runCatching {
            val reqBody = TavilySearchRequest(query = "test", maxResults = 1, includeImages = false)
            val response = httpClient.post("https://api.tavily.com/search") {
                contentType(ContentType.Application.Json)
                headers { append(HttpHeaders.Authorization, "Bearer ${apiKey.trim()}") }
                setBody(json.encodeToString(TavilySearchRequest.serializer(), reqBody))
            }
            if (response.status.isSuccess()) {
                "Connected to Tavily successfully."
            } else {
                throw RuntimeException("HTTP ${response.status.value}: ${response.bodyAsText().take(150)}")
            }
        }
    }
}

package com.lichiai.web.adapter

import com.lichiai.web.model.ImageResult
import com.lichiai.web.model.WebCapability
import com.lichiai.web.model.WebProviderType
import com.lichiai.web.model.WebResult
import com.lichiai.web.model.WebSearchQuery
import com.lichiai.web.model.WebSearchResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
private data class BraveWebSearchResponse(
    val query: BraveQuery? = null,
    val web: BraveWebResults? = null,
    val news: BraveNewsResults? = null
)

@Serializable
private data class BraveQuery(
    val original: String? = null
)

@Serializable
private data class BraveWebResults(
    val results: List<BraveWebItem> = emptyList()
)

@Serializable
private data class BraveWebItem(
    val title: String? = null,
    val url: String,
    val description: String? = null,
    val age: String? = null,
    @SerialName("page_age") val pageAge: String? = null,
    val profile: BraveProfile? = null
)

@Serializable
private data class BraveProfile(
    val name: String? = null
)

@Serializable
private data class BraveNewsResults(
    val results: List<BraveNewsItem> = emptyList()
)

@Serializable
private data class BraveNewsItem(
    val title: String? = null,
    val url: String,
    val description: String? = null,
    val age: String? = null,
    @SerialName("page_age") val pageAge: String? = null
)

@Serializable
private data class BraveImageSearchResponse(
    val results: List<BraveImageItem> = emptyList()
)

@Serializable
private data class BraveImageItem(
    val title: String? = null,
    val url: String? = null,
    val thumbnail: BraveThumbnail? = null,
    val properties: BraveImageProperties? = null
)

@Serializable
private data class BraveThumbnail(
    val src: String? = null
)

@Serializable
private data class BraveImageProperties(
    val url: String? = null
)

class BraveAdapter(private val httpClient: HttpClient) : WebSearchProvider {

    override val providerType: WebProviderType = WebProviderType.BRAVE

    override val supportedCapabilities: Set<WebCapability> = setOf(
        WebCapability.WEB_SEARCH,
        WebCapability.NEWS_SEARCH,
        WebCapability.IMAGE_SEARCH,
        WebCapability.VIDEO_SEARCH,
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
            throw IllegalArgumentException("Brave API key is missing. Configure it in Settings > Web Search.")
        }

        onProgress("Connecting to Brave Search API...", emptyList())

        val response = httpClient.get("https://api.search.brave.com/res/v1/web/search") {
            headers {
                append("X-Subscription-Token", apiKey.trim())
                append("Accept", "application/json")
            }
            parameter("q", query.query)
            parameter("count", query.maxResults)
            query.country?.let { parameter("country", it) }
            query.language?.let { parameter("search_lang", it) }
            query.freshness?.let { parameter("freshness", it) }
        }

        if (!response.status.isSuccess()) {
            val err = response.bodyAsText().take(300)
            throw RuntimeException("Brave Search API error (${response.status.value}): $err")
        }

        val parsed = json.decodeFromString(BraveWebSearchResponse.serializer(), response.bodyAsText())
        val rawResults = parsed.web?.results ?: emptyList()

        val domains = rawResults.mapNotNull {
            runCatching { URI(it.url).host }.getOrNull()
        }.distinct()

        onProgress("Analyzing ${rawResults.size} search results...", domains)

        val normalized = rawResults.mapIndexed { index, item ->
            val host = runCatching { URI(item.url).host?.removePrefix("www.") }.getOrNull() ?: "brave.com"
            WebResult(
                title = item.title?.takeIf { it.isNotBlank() } ?: host,
                url = item.url,
                domain = host,
                snippet = item.description ?: "",
                publishedAt = item.age ?: item.pageAge,
                sourceName = item.profile?.name ?: host,
                sourceProvider = "Brave Search",
                citationId = index + 1
            )
        }

        var imageResults: List<ImageResult> = emptyList()
        if (query.includeImages) {
            imageResults = runCatching {
                searchImages(query.query, 6, apiKey)
            }.getOrDefault(emptyList())
        }

        return WebSearchResponse(
            query = query.query,
            providerUsed = "Brave Search",
            results = normalized,
            images = imageResults,
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }

    override suspend fun searchNews(query: String, count: Int, apiKey: String): List<WebResult> {
        val response = httpClient.get("https://api.search.brave.com/res/v1/news/search") {
            headers {
                append("X-Subscription-Token", apiKey.trim())
                append("Accept", "application/json")
            }
            parameter("q", query)
            parameter("count", count)
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("Brave news error: ${response.status.value}")
        }
        val parsed = json.decodeFromString(BraveNewsResults.serializer(), response.bodyAsText())
        return parsed.results.mapIndexed { index, item ->
            val host = runCatching { URI(item.url).host?.removePrefix("www.") }.getOrNull() ?: "news"
            WebResult(
                title = item.title ?: host,
                url = item.url,
                domain = host,
                snippet = item.description ?: "",
                publishedAt = item.age ?: item.pageAge,
                sourceName = host,
                sourceProvider = "Brave News",
                citationId = index + 1,
                resultType = "news"
            )
        }
    }

    override suspend fun searchImages(query: String, count: Int, apiKey: String): List<ImageResult> {
        val response = httpClient.get("https://api.search.brave.com/res/v1/images/search") {
            headers {
                append("X-Subscription-Token", apiKey.trim())
                append("Accept", "application/json")
            }
            parameter("q", query)
            parameter("count", count)
        }
        if (!response.status.isSuccess()) return emptyList()
        val parsed = json.decodeFromString(BraveImageSearchResponse.serializer(), response.bodyAsText())
        return parsed.results.mapNotNull { item ->
            val imgUrl = item.properties?.url ?: item.thumbnail?.src
            if (imgUrl.isNullOrBlank()) null
            else {
                val host = runCatching { URI(item.url ?: imgUrl).host?.removePrefix("www.") }.getOrNull() ?: "web"
                ImageResult(
                    title = item.title ?: query,
                    imageUrl = imgUrl,
                    thumbnailUrl = item.thumbnail?.src ?: imgUrl,
                    sourceUrl = item.url ?: imgUrl,
                    sourceDomain = host,
                    provider = "Brave Images"
                )
            }
        }
    }

    override suspend fun testConnection(apiKey: String): Result<String> {
        return runCatching {
            val response = httpClient.get("https://api.search.brave.com/res/v1/web/search") {
                headers {
                    append("X-Subscription-Token", apiKey.trim())
                    append("Accept", "application/json")
                }
                parameter("q", "test")
                parameter("count", 1)
            }
            if (response.status.isSuccess()) {
                "Connected to Brave Search successfully."
            } else {
                throw RuntimeException("HTTP ${response.status.value}: ${response.bodyAsText().take(150)}")
            }
        }
    }
}

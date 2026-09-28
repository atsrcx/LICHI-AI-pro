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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
private data class SerperSearchRequest(
    val q: String,
    val num: Int = 8,
    val gl: String? = null,
    val hl: String? = null
)

@Serializable
private data class SerperSearchResponse(
    val searchParameters: SerperParams? = null,
    val organic: List<SerperOrganicItem> = emptyList(),
    val answerBox: SerperAnswerBox? = null,
    val knowledgeGraph: SerperKnowledgeGraph? = null,
    val images: List<SerperImageItem> = emptyList(),
    val news: List<SerperNewsItem> = emptyList()
)

@Serializable
private data class SerperParams(
    val q: String? = null
)

@Serializable
private data class SerperOrganicItem(
    val title: String? = null,
    val link: String,
    val snippet: String? = null,
    val position: Int? = null,
    val date: String? = null,
    val attributes: Map<String, String>? = null
)

@Serializable
private data class SerperAnswerBox(
    val snippet: String? = null,
    val title: String? = null,
    val answer: String? = null
)

@Serializable
private data class SerperKnowledgeGraph(
    val title: String? = null,
    val type: String? = null,
    val description: String? = null
)

@Serializable
private data class SerperImageItem(
    val title: String? = null,
    val imageUrl: String,
    val link: String? = null,
    val source: String? = null
)

@Serializable
private data class SerperNewsItem(
    val title: String? = null,
    val link: String,
    val snippet: String? = null,
    val date: String? = null,
    val source: String? = null
)

class SerperAdapter(private val httpClient: HttpClient) : WebSearchProvider {

    override val providerType: WebProviderType = WebProviderType.SERPER

    override val supportedCapabilities: Set<WebCapability> = setOf(
        WebCapability.WEB_SEARCH,
        WebCapability.NEWS_SEARCH,
        WebCapability.IMAGE_SEARCH,
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
            throw IllegalArgumentException("Serper API key is missing. Configure it in Settings > Web Search.")
        }

        onProgress("Querying Google SERP via Serper...", emptyList())

        val req = SerperSearchRequest(
            q = query.query,
            num = query.maxResults,
            gl = query.country,
            hl = query.language
        )

        val response = httpClient.post("https://google.serper.dev/search") {
            contentType(ContentType.Application.Json)
            headers {
                append("X-API-KEY", apiKey.trim())
            }
            setBody(json.encodeToString(SerperSearchRequest.serializer(), req))
        }

        if (!response.status.isSuccess()) {
            val err = response.bodyAsText().take(300)
            throw RuntimeException("Serper API error (${response.status.value}): $err")
        }

        val parsed = json.decodeFromString(SerperSearchResponse.serializer(), response.bodyAsText())

        val domains = parsed.organic.mapNotNull {
            runCatching { URI(it.link).host }.getOrNull()
        }.distinct()

        onProgress("Parsed ${parsed.organic.size} Google organic results...", domains)

        val normalized = parsed.organic.mapIndexed { index, item ->
            val host = runCatching { URI(item.link).host?.removePrefix("www.") }.getOrNull() ?: "google.com"
            WebResult(
                title = item.title ?: host,
                url = item.link,
                domain = host,
                snippet = item.snippet ?: "",
                publishedAt = item.date,
                sourceName = host,
                sourceProvider = "Serper (Google)",
                citationId = index + 1
            )
        }

        val answer = parsed.answerBox?.answer ?: parsed.answerBox?.snippet ?: parsed.knowledgeGraph?.description

        var imageResults: List<ImageResult> = emptyList()
        if (query.includeImages) {
            imageResults = runCatching {
                searchImages(query.query, 6, apiKey)
            }.getOrDefault(emptyList())
        }

        return WebSearchResponse(
            query = query.query,
            providerUsed = "Serper (Google)",
            results = normalized,
            images = imageResults,
            directAnswer = answer,
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }

    override suspend fun searchNews(query: String, count: Int, apiKey: String): List<WebResult> {
        val req = SerperSearchRequest(q = query, num = count)
        val response = httpClient.post("https://google.serper.dev/news") {
            contentType(ContentType.Application.Json)
            headers { append("X-API-KEY", apiKey.trim()) }
            setBody(json.encodeToString(SerperSearchRequest.serializer(), req))
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("Serper news error: ${response.status.value}")
        }
        val parsed = json.decodeFromString(SerperSearchResponse.serializer(), response.bodyAsText())
        return parsed.news.mapIndexed { index, item ->
            val host = runCatching { URI(item.link).host?.removePrefix("www.") }.getOrNull() ?: "news"
            WebResult(
                title = item.title ?: host,
                url = item.link,
                domain = host,
                snippet = item.snippet ?: "",
                publishedAt = item.date,
                sourceName = item.source ?: host,
                sourceProvider = "Serper News",
                citationId = index + 1,
                resultType = "news"
            )
        }
    }

    override suspend fun searchImages(query: String, count: Int, apiKey: String): List<ImageResult> {
        val req = SerperSearchRequest(q = query, num = count)
        val response = httpClient.post("https://google.serper.dev/images") {
            contentType(ContentType.Application.Json)
            headers { append("X-API-KEY", apiKey.trim()) }
            setBody(json.encodeToString(SerperSearchRequest.serializer(), req))
        }
        if (!response.status.isSuccess()) return emptyList()
        val parsed = json.decodeFromString(SerperSearchResponse.serializer(), response.bodyAsText())
        return parsed.images.mapNotNull { item ->
            if (item.imageUrl.isBlank()) null
            else {
                val host = runCatching { URI(item.link ?: item.imageUrl).host?.removePrefix("www.") }.getOrNull() ?: "google"
                ImageResult(
                    title = item.title ?: query,
                    imageUrl = item.imageUrl,
                    thumbnailUrl = item.imageUrl,
                    sourceUrl = item.link ?: item.imageUrl,
                    sourceDomain = host,
                    sourceName = item.source ?: host,
                    provider = "Serper Images"
                )
            }
        }
    }

    override suspend fun testConnection(apiKey: String): Result<String> {
        return runCatching {
            val req = SerperSearchRequest(q = "test", num = 1)
            val response = httpClient.post("https://google.serper.dev/search") {
                contentType(ContentType.Application.Json)
                headers { append("X-API-KEY", apiKey.trim()) }
                setBody(json.encodeToString(SerperSearchRequest.serializer(), req))
            }
            if (response.status.isSuccess()) {
                "Connected to Serper (Google) successfully."
            } else {
                throw RuntimeException("HTTP ${response.status.value}: ${response.bodyAsText().take(150)}")
            }
        }
    }
}

package com.lichiai.web.adapter

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
private data class ExaSearchRequest(
    val query: String,
    val type: String = "auto",
    val numResults: Int = 8,
    val contents: ExaContents? = ExaContents(text = true, highlights = true)
)

@Serializable
private data class ExaContents(
    val text: Boolean = true,
    val highlights: Boolean = true
)

@Serializable
private data class ExaSearchResponse(
    val results: List<ExaResultItem> = emptyList(),
    val answer: String? = null
)

@Serializable
private data class ExaResultItem(
    val id: String? = null,
    val title: String? = null,
    val url: String,
    val publishedDate: String? = null,
    val author: String? = null,
    val text: String? = null,
    val highlights: List<String>? = null
)

class ExaAdapter(private val httpClient: HttpClient) : WebSearchProvider {

    override val providerType: WebProviderType = WebProviderType.EXA

    override val supportedCapabilities: Set<WebCapability> = setOf(
        WebCapability.WEB_SEARCH,
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
            throw IllegalArgumentException("Exa API key is missing. Configure it in Settings > Web Search.")
        }

        onProgress("Querying Exa index...", emptyList())

        val reqBody = ExaSearchRequest(
            query = query.query,
            type = if (query.isDeep) "deep" else "auto",
            numResults = query.maxResults,
            contents = if (query.includeContent) ExaContents(text = true, highlights = true) else null
        )

        val response = httpClient.post("https://api.exa.ai/search") {
            contentType(ContentType.Application.Json)
            headers {
                append("x-api-key", apiKey.trim())
            }
            setBody(json.encodeToString(ExaSearchRequest.serializer(), reqBody))
        }

        if (!response.status.isSuccess()) {
            val err = response.bodyAsText().take(300)
            throw RuntimeException("Exa API error (${response.status.value}): $err")
        }

        val rawText = response.bodyAsText()
        val parsed = json.decodeFromString(ExaSearchResponse.serializer(), rawText)

        val domains = parsed.results.mapNotNull {
            runCatching { URI(it.url).host }.getOrNull()
        }.distinct()

        onProgress("Processing ${parsed.results.size} sources...", domains)

        val normalizedResults = parsed.results.mapIndexed { index, item ->
            val host = runCatching { URI(item.url).host?.removePrefix("www.") }.getOrNull() ?: "exa.ai"
            val snippet = item.highlights?.firstOrNull()
                ?: item.text?.take(280)?.replace("\n", " ")
                ?: "No preview text available."
            WebResult(
                title = item.title?.takeIf { it.isNotBlank() } ?: host,
                url = item.url,
                domain = host,
                snippet = snippet,
                content = item.text,
                publishedAt = item.publishedDate,
                sourceName = item.author ?: host,
                sourceProvider = "Exa",
                citationId = index + 1
            )
        }

        return WebSearchResponse(
            query = query.query,
            providerUsed = "Exa AI",
            results = normalizedResults,
            directAnswer = parsed.answer,
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }

    override suspend fun extractContent(urls: List<String>, apiKey: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (u in urls.take(5)) {
            val searchRes = search(
                WebSearchQuery(query = u, maxResults = 1, includeContent = true),
                apiKey
            )
            searchRes.results.firstOrNull()?.content?.let {
                result[u] = it
            }
        }
        return result
    }

    override suspend fun testConnection(apiKey: String): Result<String> {
        return runCatching {
            val reqBody = ExaSearchRequest(query = "Android test", numResults = 1)
            val response = httpClient.post("https://api.exa.ai/search") {
                contentType(ContentType.Application.Json)
                headers { append("x-api-key", apiKey.trim()) }
                setBody(json.encodeToString(ExaSearchRequest.serializer(), reqBody))
            }
            if (response.status.isSuccess()) {
                "Connected to Exa AI successfully."
            } else {
                throw RuntimeException("HTTP ${response.status.value}: ${response.bodyAsText().take(150)}")
            }
        }
    }
}

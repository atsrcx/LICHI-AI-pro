package com.lichiai.browser.llm

import com.lichiai.browser.context.BrowserTaskContext
import com.lichiai.browser.provider.BrowserProviderConfig
import com.lichiai.browser.provider.BrowserProviderManager
import com.lichiai.browser.provider.BrowserProviderType
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject

@Serializable
data class BrowserAgentDecision(
    val action: String = "STOP",
    val goal: String? = null,
    val query: String? = null,
    val engine: String? = null,
    val index: Int? = null,
    val text: String? = null,
    val selector: String? = null,
    val submit: Boolean = false,
    val url: String? = null,
    val direction: String? = "DOWN",
    val tabId: String? = null,
    val answer: String? = null,
    val reason: String? = null,
    val summary: String = ""
)

/**
 * Isolated HTTP client for Browser Agent LLM interactions.
 * Completely separate from Lichi device agent or chat client, but utilizes
 * the canonical configured Browser Provider / Model for all reasoning steps.
 */
class BrowserLLMClient(
    private val providerManager: BrowserProviderManager
) {

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    suspend fun decideNextAction(
        context: BrowserTaskContext,
        pageSnippet: String
    ): BrowserAgentDecision = withContext(Dispatchers.IO) {
        val primaryConfig = providerManager.getActiveConfig()
            ?: return@withContext BrowserAgentDecision(action = "STOP", summary = "No browser provider configured")

        val systemPrompt = BrowserPromptBuilder.buildSystemPrompt()
        val userPrompt = BrowserPromptBuilder.buildUserPrompt(context, pageSnippet)

        // Try primary provider, then try fallback on error
        try {
            callProvider(primaryConfig, systemPrompt, userPrompt)
        } catch (e: Exception) {
            val fallback = providerManager.getFallbackConfig()
            if (fallback != null && fallback.id != primaryConfig.id) {
                try {
                    callProvider(fallback, systemPrompt, userPrompt)
                } catch (fallbackEx: Exception) {
                    BrowserAgentDecision(action = "STOP", summary = "Provider error: ${e.message}")
                }
            } else {
                BrowserAgentDecision(action = "STOP", summary = "Provider error: ${e.message}")
            }
        }
    }

    private suspend fun callProvider(
        config: BrowserProviderConfig,
        systemPrompt: String,
        userPrompt: String
    ): BrowserAgentDecision {
        val rawResponse = when (config.type) {
            BrowserProviderType.GEMINI -> callGemini(config, systemPrompt, userPrompt)
            BrowserProviderType.ANTHROPIC -> callAnthropic(config, systemPrompt, userPrompt)
            else -> callOpenAiCompatible(config, systemPrompt, userPrompt)
        }
        return parseDecision(rawResponse)
    }

    private suspend fun callOpenAiCompatible(
        config: BrowserProviderConfig,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val endpoint = config.endpoint.trimEnd('/') + "/chat/completions"
        val body = """
            {
              "model": "${config.activeModel.ifBlank { "gpt-4o-mini" }}",
              "messages": [
                {"role": "system", "content": ${JSONObject.quote(systemPrompt)}},
                {"role": "user", "content": ${JSONObject.quote(userPrompt)}}
              ],
              "temperature": 0.1,
              "max_tokens": 500
            }
        """.trimIndent()

        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            if (config.apiKey.isNotBlank()) {
                header("Authorization", "Bearer ${config.apiKey}")
            }
            setBody(body)
        }
        val resText = response.bodyAsText()
        if (response.status.value == 429) {
            throw RuntimeException("Browser LLM provider rate limit exceeded (HTTP 429)")
        }
        if (response.status.value >= 400) {
            throw RuntimeException("Browser LLM provider request failed (HTTP ${response.status.value})")
        }
        val json = JSONObject(resText)
        val choices = json.getJSONArray("choices")
        val msg = choices.getJSONObject(0).getJSONObject("message")
        return msg.getString("content")
    }

    private suspend fun callGemini(
        config: BrowserProviderConfig,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val model = config.activeModel.ifBlank { "gemini-2.5-flash" }
        val apiKeyParam = if (config.apiKey.isNotBlank()) "?key=${config.apiKey}" else ""
        val url = "${config.endpoint.trimEnd('/')}/models/$model:generateContent$apiKeyParam"

        val body = """
            {
              "systemInstruction": {
                "parts": [{"text": ${JSONObject.quote(systemPrompt)}}]
              },
              "contents": [
                {
                  "role": "user",
                  "parts": [{"text": ${JSONObject.quote(userPrompt)}}]
                }
              ],
              "generationConfig": {
                "temperature": 0.1,
                "maxOutputTokens": 500,
                "responseMimeType": "application/json"
              }
            }
        """.trimIndent()

        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val resText = response.bodyAsText()
        if (response.status.value == 429) {
            throw RuntimeException("Browser LLM provider rate limit exceeded (HTTP 429)")
        }
        if (response.status.value >= 400) {
            throw RuntimeException("Browser LLM provider request failed (HTTP ${response.status.value})")
        }
        val json = JSONObject(resText)
        val candidates = json.getJSONArray("candidates")
        val content = candidates.getJSONObject(0).getJSONObject("content")
        val parts = content.getJSONArray("parts")
        return parts.getJSONObject(0).getString("text")
    }

    private suspend fun callAnthropic(
        config: BrowserProviderConfig,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val endpoint = config.endpoint.trimEnd('/') + "/messages"
        val body = """
            {
              "model": "${config.activeModel.ifBlank { "claude-3-5-sonnet-20241022" }}",
              "system": ${JSONObject.quote(systemPrompt)},
              "messages": [
                {"role": "user", "content": ${JSONObject.quote(userPrompt)}}
              ],
              "max_tokens": 500,
              "temperature": 0.1
            }
        """.trimIndent()

        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.Json)
            header("x-api-key", config.apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(body)
        }
        val resText = response.bodyAsText()
        if (response.status.value == 429) {
            throw RuntimeException("Browser LLM provider rate limit exceeded (HTTP 429)")
        }
        if (response.status.value >= 400) {
            throw RuntimeException("Browser LLM provider request failed (HTTP ${response.status.value})")
        }
        val json = JSONObject(resText)
        val contentArr = json.getJSONArray("content")
        return contentArr.getJSONObject(0).getString("text")
    }

    companion object {
        fun parseDecision(text: String): BrowserAgentDecision {
            return try {
                val clean = text.trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                val json = JSONObject(clean)
                BrowserAgentDecision(
                    action = json.optString("action", "STOP").uppercase(),
                    goal = if (json.has("goal") && !json.isNull("goal")) json.getString("goal") else null,
                    query = if (json.has("query") && !json.isNull("query")) json.getString("query") else null,
                    engine = if (json.has("engine") && !json.isNull("engine")) json.getString("engine") else null,
                    index = if (json.has("index") && !json.isNull("index")) json.getInt("index") else null,
                    text = if (json.has("text") && !json.isNull("text")) json.getString("text") else null,
                    selector = if (json.has("selector") && !json.isNull("selector")) json.getString("selector") else null,
                    submit = json.optBoolean("submit", false),
                    url = if (json.has("url") && !json.isNull("url")) json.getString("url") else null,
                    direction = json.optString("direction", "DOWN").uppercase(),
                    tabId = if (json.has("tabId") && !json.isNull("tabId")) json.getString("tabId") else null,
                    answer = if (json.has("answer") && !json.isNull("answer")) json.getString("answer") else null,
                    reason = if (json.has("reason") && !json.isNull("reason")) json.getString("reason") else null,
                    summary = json.optString("summary", "")
                )
            } catch (_: Exception) {
                BrowserAgentDecision(action = "STOP", summary = "Could not parse model decision")
            }
        }
    }
}

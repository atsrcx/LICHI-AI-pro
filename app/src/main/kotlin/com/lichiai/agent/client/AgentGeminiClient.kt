package com.lichiai.agent.client

import android.util.Log
import com.lichiai.agent.model.AgentAction
import com.lichiai.agent.model.AgentOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * AgentGeminiClient provides direct, unproxied communication with the Google Gemini API
 * exclusively for Autonomous Agent V2.
 *
 * CRITICAL REQUIREMENTS:
 * 1. Independent from Lichi's multi-provider config and LlmClient.
 * 2. Directly connects to generativelanguage.googleapis.com.
 * 3. Never logs API keys or includes them in exception messages.
 * 4. Validates and parses structured AgentOutput.
 */
class AgentGeminiClient {

    companion object {
        private const val TAG = "AgentGeminiClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val CONNECT_TIMEOUT_MS = 25000
        private const val READ_TIMEOUT_MS = 45000
    }

    /**
     * Sends prompt directly to Gemini API and parses the structured AgentOutput.
     */
    suspend fun generateAgentOutput(
        apiKey: String,
        model: String,
        prompt: String
    ): Result<AgentOutput> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Agent Gemini API key is missing."))
        }

        val targetModel = model.trim().ifBlank { "gemini-2.5-flash" }
        val endpointUrl = "$BASE_URL/$targetModel:generateContent?key=$apiKey"

        var connection: HttpURLConnection? = null
        try {
            val url = URL(endpointUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            // Build Gemini request payload with JSON mode
            val requestJson = JSONObject().apply {
                val contentsArray = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        put("role", "user")
                        val partsArray = JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        }
                        put("parts", partsArray)
                    }
                    put(contentObj)
                }
                put("contents", contentsArray)

                val genConfig = JSONObject().apply {
                    put("temperature", 0.2)
                    put("responseMimeType", "application/json")
                }
                put("generationConfig", genConfig)
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val statusCode = connection.responseCode
            val responseText = if (statusCode in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
                } ?: "HTTP $statusCode"
                Log.e(TAG, "Gemini API failed with status $statusCode: $errorBody")
                return@withContext Result.failure(
                    IllegalStateException("Gemini API call failed (HTTP $statusCode). Check API key and quotas.")
                )
            }

            val rawContent = extractContentText(responseText)
            if (rawContent.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Gemini returned an empty response candidate."))
            }

            val parsedOutput = parseStructuredAgentOutput(rawContent)
            Result.success(parsedOutput)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during AgentGeminiClient execution: ${e.javaClass.simpleName}")
            Result.failure(e)
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    private fun extractContentText(jsonResponse: String): String {
        return try {
            val root = JSONObject(jsonResponse)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val firstCand = candidates.getJSONObject(0)
            val content = firstCand.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            if (parts.length() == 0) return ""
            parts.getJSONObject(0).optString("text", "")
        } catch (e: Exception) {
            Log.w(TAG, "Failed extracting text from Gemini response JSON", e)
            ""
        }
    }

    /**
     * Parses the agent JSON response into [AgentOutput], stripping markdown code fences if present.
     */
    fun parseStructuredAgentOutput(rawText: String): AgentOutput {
        var cleanJson = rawText.trim()
        if (cleanJson.startsWith("```json")) {
            cleanJson = cleanJson.removePrefix("```json").trim()
        } else if (cleanJson.startsWith("```")) {
            cleanJson = cleanJson.removePrefix("```").trim()
        }
        if (cleanJson.endsWith("```")) {
            cleanJson = cleanJson.removeSuffix("```").trim()
        }

        return try {
            val obj = JSONObject(cleanJson)
            val thinking = obj.optString("thinking", "")
            val eval = obj.optString("evaluationPreviousGoal", "")
            val memory = obj.optString("memory", "")
            val nextGoal = obj.optString("nextGoal", "")

            val actionList = mutableListOf<AgentAction>()
            val actionArray = obj.optJSONArray("action")
            if (actionArray != null) {
                for (i in 0 until actionArray.length()) {
                    val actObj = actionArray.optJSONObject(i)
                    if (actObj != null) {
                        val name = actObj.optString("name", actObj.optString("action", "")).trim()
                        val params = mutableMapOf<String, String>()
                        val keys = actObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k != "name" && k != "action") {
                                params[k] = actObj.optString(k, "")
                            }
                        }
                        if (name.isNotBlank()) {
                            actionList.add(AgentAction(name = name, params = params))
                        }
                    }
                }
            }

            AgentOutput(
                thinking = thinking,
                evaluationPreviousGoal = eval,
                memory = memory,
                nextGoal = nextGoal,
                action = actionList
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Agent JSON output: $cleanJson", e)
            AgentOutput(
                thinking = "Failed parsing agent JSON: ${e.message}",
                evaluationPreviousGoal = "Parsing error",
                nextGoal = "Retry formatting",
                action = emptyList()
            )
        }
    }
}

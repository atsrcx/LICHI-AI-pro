package com.lichiai.voice.live

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DiscoveredGeminiModel(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val isLiveRecommended: Boolean = false,
    val hasBidiSupport: Boolean = false,
    val supportedMethods: List<String> = emptyList()
)

object GeminiLiveModelDiscovery {
    private const val TAG = "GeminiLiveModelDiscovery"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Standard static fallback list when offline or before API call
    val FALLBACK_LIVE_MODELS = listOf(
        DiscoveredGeminiModel(
            id = "gemini-2.0-flash-exp",
            name = "models/gemini-2.0-flash-exp",
            displayName = "Gemini 2.0 Flash Live (Recommended)",
            description = "Ultra-low-latency bidirectional native multimodal live model",
            isLiveRecommended = true,
            hasBidiSupport = true,
            supportedMethods = listOf("bidiGenerateContent", "generateContent")
        ),
        DiscoveredGeminiModel(
            id = "gemini-2.0-flash-realtime-exp",
            name = "models/gemini-2.0-flash-realtime-exp",
            displayName = "Gemini 2.0 Flash Realtime",
            description = "Experimental realtime bidirectional multimodal streaming model",
            isLiveRecommended = true,
            hasBidiSupport = true,
            supportedMethods = listOf("bidiGenerateContent", "generateContent")
        ),
        DiscoveredGeminiModel(
            id = "gemini-2.5-flash-native-audio-preview-12-2025",
            name = "models/gemini-2.5-flash-native-audio-preview-12-2025",
            displayName = "Gemini 2.5 Flash Native Audio Preview",
            description = "Native audio preview model for expressive conversational live voice",
            isLiveRecommended = true,
            hasBidiSupport = true,
            supportedMethods = listOf("bidiGenerateContent", "generateContent")
        ),
        DiscoveredGeminiModel(
            id = "gemini-2.0-flash",
            name = "models/gemini-2.0-flash",
            displayName = "Gemini 2.0 Flash",
            description = "Fast multimodal model with low-latency streaming capabilities",
            isLiveRecommended = true,
            hasBidiSupport = true,
            supportedMethods = listOf("generateContent", "bidiGenerateContent")
        ),
        DiscoveredGeminiModel(
            id = "gemini-2.0-flash-lite-preview-02-05",
            name = "models/gemini-2.0-flash-lite-preview-02-05",
            displayName = "Gemini 2.0 Flash Lite",
            description = "Lightweight high-throughput model optimized for responsive interactions",
            isLiveRecommended = true,
            hasBidiSupport = false,
            supportedMethods = listOf("generateContent")
        ),
        DiscoveredGeminiModel(
            id = "gemini-3.8-live",
            name = "models/gemini-3.8-live",
            displayName = "Gemini 3.8 Live",
            description = "Ultra-low-latency bidirectional live conversation model",
            isLiveRecommended = true,
            hasBidiSupport = true,
            supportedMethods = listOf("bidiGenerateContent", "generateContent")
        ),
        DiscoveredGeminiModel(
            id = "gemini-1.5-flash",
            name = "models/gemini-1.5-flash",
            displayName = "Gemini 1.5 Flash",
            description = "Fast and versatile multimodal model for lightweight tasks",
            isLiveRecommended = false,
            hasBidiSupport = false,
            supportedMethods = listOf("generateContent")
        ),
        DiscoveredGeminiModel(
            id = "gemini-1.5-pro",
            name = "models/gemini-1.5-pro",
            displayName = "Gemini 1.5 Pro",
            description = "Complex reasoning and broad context multimodal model",
            isLiveRecommended = false,
            hasBidiSupport = false,
            supportedMethods = listOf("generateContent")
        )
    )

    suspend fun fetchGeminiModels(apiKey: String, customBaseUrl: String? = null): List<DiscoveredGeminiModel> = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isBlank()) return@withContext FALLBACK_LIVE_MODELS

        val baseUrl = customBaseUrl?.trim()?.removeSuffix("/")?.takeIf { it.isNotEmpty() }
            ?: "https://generativelanguage.googleapis.com/v1beta"

        val url = if (baseUrl.contains("models")) {
            if (baseUrl.contains("?")) "$baseUrl&key=$key" else "$baseUrl?key=$key"
        } else {
            "$baseUrl/models?key=$key"
        }

        try {
            Log.d(TAG, "Fetching Gemini models from API endpoint: $baseUrl/models")
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch models from API: HTTP ${response.code}")
                return@withContext FALLBACK_LIVE_MODELS
            }

            val body = response.body?.string() ?: return@withContext FALLBACK_LIVE_MODELS
            val json = JSONObject(body)
            val modelsArray = json.optJSONArray("models") ?: return@withContext FALLBACK_LIVE_MODELS
            val fetchedList = mutableListOf<DiscoveredGeminiModel>()

            for (i in 0 until modelsArray.length()) {
                val item = modelsArray.getJSONObject(i)
                val rawName = item.optString("name", "")
                val cleanId = rawName.removePrefix("models/")
                val displayName = item.optString("displayName", cleanId)
                val description = item.optString("description", "")
                val methodsArray = item.optJSONArray("supportedGenerationMethods")
                val methods = mutableListOf<String>()
                if (methodsArray != null) {
                    for (j in 0 until methodsArray.length()) {
                        methods.add(methodsArray.getString(j))
                    }
                }

                val hasBidi = methods.any { it.contains("bidi", ignoreCase = true) }
                // Identify Live/Audio/Realtime/Flash models
                val isLive = hasBidi ||
                        cleanId.contains("live", ignoreCase = true) ||
                        cleanId.contains("realtime", ignoreCase = true) ||
                        cleanId.contains("native-audio", ignoreCase = true) ||
                        cleanId.contains("audio", ignoreCase = true) ||
                        cleanId.contains("flash", ignoreCase = true) ||
                        cleanId.contains("2.0", ignoreCase = true) ||
                        cleanId.contains("2.5", ignoreCase = true) ||
                        cleanId.contains("3.", ignoreCase = true)

                fetchedList.add(
                    DiscoveredGeminiModel(
                        id = cleanId,
                        name = rawName,
                        displayName = displayName,
                        description = description,
                        isLiveRecommended = isLive,
                        hasBidiSupport = hasBidi,
                        supportedMethods = methods
                    )
                )
            }

            if (fetchedList.isEmpty()) {
                FALLBACK_LIVE_MODELS
            } else {
                // Ensure default recommended models exist in list
                val existingIds = fetchedList.map { it.id }.toSet()
                val merged = fetchedList.toMutableList()
                for (fallback in FALLBACK_LIVE_MODELS) {
                    if (!existingIds.contains(fallback.id)) {
                        merged.add(fallback)
                    }
                }

                merged.sortedWith(
                    compareByDescending<DiscoveredGeminiModel> { it.hasBidiSupport }
                        .thenByDescending { it.isLiveRecommended }
                        .thenBy { it.displayName }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Gemini models from API: ${e.message}", e)
            FALLBACK_LIVE_MODELS
        }
    }
}

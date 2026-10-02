package com.lichiai.memory

import android.content.Context
import android.util.Log
import com.lichiai.api.ChatMessage
import com.lichiai.api.LlmClient
import com.lichiai.data.ProviderConfig
import com.lichiai.memory.data.UserMemoryDao
import com.lichiai.memory.data.UserMemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LlmMemoryManager private constructor(
    private val context: Context,
    private val memoryDao: UserMemoryDao
) {
    companion object {
        private const val TAG = "LlmMemoryManager"
        @Volatile
        private var instance: LlmMemoryManager? = null

        fun init(context: Context, dao: UserMemoryDao) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = LlmMemoryManager(context.applicationContext, dao)
                    }
                }
            }
        }

        fun getInstance(): LlmMemoryManager =
            instance ?: error("LlmMemoryManager is not initialized. Call init() first.")
    }

    /**
     * Builds a comprehensive, well-structured memory context for prompt injection.
     */
    suspend fun getFormattedMemoryContext(userId: String = "default_user"): String = withContext(Dispatchers.IO) {
        val memories = memoryDao.getAllMemories(userId)
        if (memories.isEmpty()) return@withContext ""

        val sb = StringBuilder()
        sb.appendLine("### [USER PERSISTENT KNOWLEDGE BASE & PROFILE]")
        sb.appendLine("(Authoritative ground truth. Seamlessly personalize all answers using these remembered facts):")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        // Group by category for clean LLM comprehension
        val grouped = memories.groupBy { it.category.lowercase(Locale.ROOT) }

        grouped.forEach { (category, items) ->
            val catHeader = category.replace("_", " ").uppercase(Locale.ROOT)
            sb.appendLine("\n**[$catHeader]**")
            items.forEach { m ->
                val dateStr = dateFormat.format(Date(m.updatedAt))
                val label = m.key.replace("_", " ").replaceFirstChar { it.uppercase() }
                sb.appendLine("• $label: \"${m.value}\" (Saved: $dateStr)")
            }
        }
        sb.toString().trim()
    }

    /**
     * Proactive Background Memory Extraction.
     * Evaluates conversational turns to extract implicit and explicit user facts.
     */
    suspend fun inspectAndExtract(
        userId: String = "default_user",
        userMessage: String,
        assistantResponse: String,
        provider: ProviderConfig,
        modelId: String,
        llmClient: LlmClient
    ) = withContext(Dispatchers.IO) {
        val trimmedMsg = userMessage.trim()
        // Skip tiny non-informative chatter
        if (trimmedMsg.length < 4 || isTrivialGreeting(trimmedMsg)) return@withContext

        val currentMemories = memoryDao.getAllMemories(userId)
        val currentJson = JSONArray()
        currentMemories.forEach { m ->
            currentJson.put(JSONObject().apply {
                put("category", m.category)
                put("key", m.key)
                put("value", m.value)
            })
        }

        val extractionSystemPrompt = """
        You are the Long-Term Memory Core of an intelligent AI assistant.
        Your job is to analyze user-assistant interactions and extract durable facts worth remembering across conversations.

        Current Stored User Knowledge Base:
        ${currentJson.toString(2)}

        Taxonomy Categories:
        - "identity": Name, age, role, occupation, location/residence.
        - "tech_stack": Programming languages, frameworks, SDKs, libraries, tools, OS.
        - "hardware": Phones, laptops, microcontrollers (Pico, ESP8266, Arduino), chips, components.
        - "projects": Project names, architecture choices, app ideas, active goals.
        - "preferences": Coding conventions, UI design styles, favorite music, foods, colors, personal tastes.
        - "relationships": Family members, colleagues, contacts mentioned.

        Rules for Extraction:
        1. Capture BOTH explicit statements ("Mera naam X hai", "Remember that Y") AND implicit statements ("Main Kotlin mein Android app bana raha hoon", "Hamare repair shop par...").
        2. If user changes or updates an existing fact (e.g. was TypeScript, now Kotlin), emit "UPSERT" with the EXACT SAME key so it cleanly updates.
        3. If user explicitly asks to forget something (e.g. "Forget my favorite color"), emit "DELETE" with that key.
        4. NEVER merge or cross-contaminate slots (e.g. A programming language must NEVER overwrite a residence or city slot).
        5. Atomic Extraction: If multiple items are stated (e.g. "Kotlin, Python, and C++"), store them cleanly under appropriate keys or concise lists.
        6. Do NOT store temporary ephemeral chatter (e.g. "Aaj mausam accha hai", "Give me a code snippet", "Explain this function").

        Respond ONLY with a valid raw JSON object matching this schema:
        {
          "actions": [
            {
              "operation": "UPSERT" | "DELETE",
              "category": "identity" | "tech_stack" | "hardware" | "projects" | "preferences" | "relationships",
              "key": "canonical_attribute_name",
              "value": "factual_value_string"
            }
          ]
        }
        If nothing durable needs saving or deleting, return {"actions": []}.
        """.trimIndent()

        val prompt = """
        User Utterance: "$userMessage"
        Assistant Response: "$assistantResponse"
        """.trimIndent()

        val messages = listOf(
            ChatMessage("system", extractionSystemPrompt),
            ChatMessage("user", prompt)
        )

        try {
            val responseText = llmClient.chatCompletion(
                provider = provider,
                modelId = modelId,
                messages = messages,
                temperature = 0.0f
            )

            val cleanJson = responseText.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            val root = JSONObject(cleanJson)
            val actions = root.optJSONArray("actions") ?: return@withContext

            for (i in 0 until actions.length()) {
                val act = actions.getJSONObject(i)
                val op = act.optString("operation", "UPSERT").uppercase(Locale.ROOT)
                val category = act.optString("category", "preferences").trim().lowercase(Locale.ROOT)
                val rawKey = act.optString("key", "").trim().lowercase(Locale.ROOT).replace(" ", "_")
                val value = act.optString("value", "").trim()

                if (rawKey.isBlank()) continue

                if (op == "DELETE") {
                    memoryDao.deleteMemoryByKey(userId, rawKey)
                    Log.i(TAG, "[Memory Engine] Deleted key '$rawKey' for user $userId")
                } else if (op == "UPSERT" && value.isNotBlank()) {
                    val entity = UserMemoryEntity(
                        id = "${userId}_${rawKey}",
                        userId = userId,
                        key = rawKey,
                        value = value,
                        category = category,
                        updatedAt = System.currentTimeMillis()
                    )
                    memoryDao.upsertMemory(entity)
                    Log.i(TAG, "[Memory Engine] Saved [$category] $rawKey = \"$value\" for user $userId")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Proactive memory extraction exception: ${e.message}")
        }
    }

    private fun isTrivialGreeting(msg: String): Boolean {
        val lower = msg.lowercase(Locale.ROOT).trim()
        val trivialPhrases = setOf(
            "hi", "hello", "hey", "namaste", "hola", "ok", "okay", "bye", "goodbye",
            "thanks", "thank you", "shukriya", "good morning", "good night", "test"
        )
        return lower in trivialPhrases
    }
}

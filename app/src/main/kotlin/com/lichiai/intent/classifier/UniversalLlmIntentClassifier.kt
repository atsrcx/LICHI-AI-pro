package com.lichiai.intent.classifier

import com.lichiai.api.ChatMessage
import com.lichiai.api.LlmClient
import com.lichiai.data.ProviderConfig
import com.lichiai.intent.model.AmbiguityLevel
import com.lichiai.intent.model.BrowserActionType
import com.lichiai.intent.model.FreshnessRequirement
import com.lichiai.intent.model.IntentContext
import com.lichiai.intent.model.IntentType
import com.lichiai.intent.model.IntentUnderstanding
import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.ResolvedIntent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
data class StructuredLlmIntent(
    @SerialName("user_goal") val userGoal: String? = null,
    @SerialName("intent_type") val intentType: String? = "CONVERSATION",
    val domain: String = "CHAT",
    val action: String? = null,
    val target: String? = null,
    val payload: String? = null,
    val entities: Map<String, String> = emptyMap(),
    val constraints: List<String> = emptyList(),
    @SerialName("desired_outcome") val desiredOutcome: String? = null,
    @SerialName("freshness_required") val freshnessRequired: String? = "NONE",
    @SerialName("is_follow_up") val isFollowUp: Boolean = false,
    @SerialName("is_correction") val isCorrection: Boolean = false,
    @SerialName("is_cancellation") val isCancellation: Boolean = false,
    val index: Int? = null,
    @SerialName("is_multi_step") val isMultiStep: Boolean = false,
    val acknowledgment: String? = null,
    @SerialName("clarification_question") val clarificationQuestion: String? = null
)

/**
 * Provider-neutral structured LLM Intent Analyst.
 * "LLM understands WHAT THE USER MEANS. Existing Lichi systems decide HOW THAT INTENT IS EXECUTED."
 *
 * Enforces strict schema validation. Never executes arbitrary LLM text or shell commands.
 */
class UniversalLlmIntentClassifier(
    private val llmClient: LlmClient
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val SYSTEM_PROMPT = """You are not an executor. You are a semantic intent analyst for Lichi AI on Android.
Your job is to understand what the user actually means from natural speech (English, Hindi, Hinglish, Roman Hindi).
Determine the user's true goal, intent type, domain, entities, desired outcome, and constraints.

Available Domains:
1. BROWSER: User wants to navigate web pages, view websites on screen, search Google/Bing/YouTube in the browser, scroll, or click candidate search results.
   - action: "SEARCH" | "NAVIGATE" | "CLICK_INDEX" | "SCROLL" | "FIND_ON_PAGE"
   - target: "google" | "youtube" | "wikipedia" or specific URL
   - payload: Clean search query without wrapper words like 'search karo' or 'google par'
   - index: 0-based integer if ordinal mentioned (e.g. "doosra result" -> 1, "pehla wala" -> 0)

2. WEB_SEARCH: User asks for factual info, live/current news, or latest prices to be retrieved into conversation.
   - action: "SEARCH"
   - payload: Clean topic/news/price query

3. ANDROID_AGENT: User wants to automate phone actions, open apps, send messages on WhatsApp/Instagram/Telegram, or adjust settings.
   - action: "OPEN_APP" | "MESSAGE" | "AUTOMATE"
   - target: App name (e.g. "instagram", "whatsapp", "settings")
   - payload: Message text or task description

4. CALLS: User wants to place a phone call to a contact or phone number.
   - action: "CALL"
   - target: Contact name or phone number

5. CHAT: Informational questions ("how to", "what is", "kaise karein"), explanations, reasoning, advice, or chit-chat.

6. CLARIFICATION: The input is genuinely missing required information to act safely.
   - clarification_question: Short, polite question in the user's language.

Output strictly valid JSON with no markdown formatting:
{
  "user_goal": "Concise summary of what the user wants to accomplish",
  "intent_type": "VISIBLE_BROWSER_TASK" | "WEB_SEARCH" | "ANDROID_ACTION" | "CALL_ACTION" | "CONVERSATION" | "QUESTION" | "CORRECTION" | "CANCELLATION" | "CONFIRMATION" | "CLARIFICATION",
  "domain": "BROWSER" | "WEB_SEARCH" | "ANDROID_AGENT" | "CALLS" | "CHAT" | "CLARIFICATION",
  "action": string or null,
  "target": string or null,
  "payload": string or null,
  "entities": {"contact": "...", "app": "...", "query": "..."},
  "constraints": ["in visible browser"],
  "desired_outcome": "Description of what successful execution achieves",
  "freshness_required": "CURRENT" | "LATEST" | "NONE",
  "is_follow_up": false,
  "is_correction": false,
  "is_cancellation": false,
  "index": int or null,
  "is_multi_step": false,
  "acknowledgment": "Short natural spoken acknowledgment in user's language",
  "clarification_question": string or null
}"""
    }

    suspend fun classify(
        text: String,
        context: IntentContext,
        provider: ProviderConfig,
        modelId: String
    ): Triple<ResolvedIntent, Float, IntentUnderstanding>? {
        if (provider.apiKey.isBlank() && !provider.baseUrl.contains("localhost")) {
            return null
        }

        val contextInfo = buildString {
            if (!context.currentBrowserUrl.isNullOrBlank()) {
                append("Active Browser URL: ${context.currentBrowserUrl}\n")
            }
            if (!context.currentBrowserTitle.isNullOrBlank()) {
                append("Active Browser Title: ${context.currentBrowserTitle}\n")
            }
            if (!context.lastSearchQuery.isNullOrBlank()) {
                append("Recent Search Query: ${context.lastSearchQuery}\n")
            }
            if (context.recentEntities.isNotEmpty()) {
                append("Recent Entities: ${context.recentEntities}\n")
            }
            if (context.recentTurns.isNotEmpty()) {
                append("Recent Turns: ${context.recentTurns.takeLast(3)}\n")
            }
        }

        val userPrompt = if (contextInfo.isNotBlank()) {
            "$contextInfo\nUser input: \"$text\""
        } else {
            "User input: \"$text\""
        }

        return try {
            val responseText = llmClient.chatCompletion(
                provider = provider,
                modelId = modelId,
                messages = listOf(
                    ChatMessage(role = "system", content = SYSTEM_PROMPT),
                    ChatMessage(role = "user", content = userPrompt)
                ),
                temperature = 0.0f
            )

            val parsed = parseAndValidate(responseText) ?: return null
            mapToResolvedIntentWithUnderstanding(parsed, text)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseAndValidate(rawOutput: String): StructuredLlmIntent? {
        val trimmed = rawOutput.trim()
        val jsonString = if (trimmed.contains("{") && trimmed.contains("}")) {
            val start = trimmed.indexOf("{")
            val end = trimmed.lastIndexOf("}")
            trimmed.substring(start, end + 1)
        } else trimmed

        return runCatching {
            json.decodeFromString<StructuredLlmIntent>(jsonString)
        }.getOrNull()
    }

    private fun mapToResolvedIntentWithUnderstanding(
        parsed: StructuredLlmIntent,
        rawText: String
    ): Triple<ResolvedIntent, Float, IntentUnderstanding> {
        val ack = parsed.acknowledgment.takeUnless { it.isNullOrBlank() } ?: "Sure, on it."

        val freshness = when (parsed.freshnessRequired?.uppercase(Locale.ROOT)) {
            "CURRENT" -> FreshnessRequirement.CURRENT
            "LATEST" -> FreshnessRequirement.LATEST
            else -> FreshnessRequirement.NONE
        }

        val intentType = when (parsed.intentType?.uppercase(Locale.ROOT)) {
            "VISIBLE_BROWSER_TASK" -> IntentType.VISIBLE_BROWSER_TASK
            "WEB_SEARCH" -> IntentType.WEB_SEARCH
            "ANDROID_ACTION" -> IntentType.ANDROID_ACTION
            "CALL_ACTION" -> IntentType.CALL_ACTION
            "CORRECTION" -> IntentType.CORRECTION
            "CANCELLATION" -> IntentType.CANCELLATION
            "CONFIRMATION" -> IntentType.CONFIRMATION
            "CLARIFICATION" -> IntentType.CLARIFICATION
            "QUESTION" -> IntentType.QUESTION
            else -> IntentType.CONVERSATION
        }

        val understanding = IntentUnderstanding(
            userGoal = parsed.userGoal ?: rawText,
            intentType = intentType,
            domain = parsed.domain.uppercase(Locale.ROOT),
            action = parsed.action,
            target = parsed.target,
            entities = parsed.entities,
            constraints = parsed.constraints,
            desiredOutcome = parsed.desiredOutcome,
            freshnessRequirement = freshness,
            isFollowUp = parsed.isFollowUp,
            isCorrection = parsed.isCorrection,
            isCancellation = parsed.isCancellation,
            ambiguity = if (parsed.clarificationQuestion.isNullOrBlank()) AmbiguityLevel.KNOWN else AmbiguityLevel.AMBIGUOUS
        )

        val resolvedPair: Pair<ResolvedIntent, Float> = when (parsed.domain.uppercase(Locale.ROOT)) {
            "BROWSER" -> {
                val action = when (parsed.action?.uppercase(Locale.ROOT)) {
                    "NAVIGATE" -> BrowserActionType.NAVIGATE
                    "CLICK_INDEX" -> BrowserActionType.CLICK_CANDIDATE
                    "SCROLL" -> BrowserActionType.SCROLL_DOWN
                    "FIND_ON_PAGE" -> BrowserActionType.FIND_ON_PAGE
                    else -> BrowserActionType.SEARCH
                }
                val intent = ResolvedIntent.BrowserTask(
                    action = action,
                    query = parsed.payload ?: rawText,
                    url = parsed.target,
                    searchEngine = parsed.target ?: "google",
                    candidateIndex = parsed.index,
                    rawPrompt = rawText,
                    naturalAcknowledgment = ack
                )
                Pair(intent, 0.88f)
            }
            "WEB_SEARCH" -> {
                val intent = ResolvedIntent.WebSearchTask(
                    query = parsed.payload ?: rawText,
                    naturalAcknowledgment = ack
                )
                Pair(intent, 0.88f)
            }
            "ANDROID_AGENT" -> {
                val intent = ResolvedIntent.AndroidAgentTask(
                    goal = parsed.payload ?: rawText,
                    targetApp = parsed.target,
                    naturalAcknowledgment = ack
                )
                Pair(intent, 0.85f)
            }
            "CALLS" -> {
                val contactTarget = parsed.target ?: parsed.payload ?: rawText
                val callIntent = com.lichiai.calling.intent.CallIntent(
                    action = com.lichiai.calling.intent.CallAction.CALL_CONTACT,
                    targetText = contactTarget,
                    originalText = rawText
                )
                val intent = ResolvedIntent.CallTask(
                    callIntent = callIntent,
                    naturalAcknowledgment = ack
                )
                Pair(intent, 0.90f)
            }
            "CLARIFICATION" -> {
                val question = parsed.clarificationQuestion ?: "Aap kya karna chahte hain?"
                Pair(ResolvedIntent.Clarification(question = question), 0.85f)
            }
            else -> {
                if (parsed.isCancellation) {
                    Pair(ResolvedIntent.Cancellation(naturalAcknowledgment = ack), 0.95f)
                } else {
                    Pair(ResolvedIntent.NormalChat(prompt = rawText, naturalAcknowledgment = ""), 0.75f)
                }
            }
        }

        return Triple(resolvedPair.first, resolvedPair.second, understanding)
    }
}

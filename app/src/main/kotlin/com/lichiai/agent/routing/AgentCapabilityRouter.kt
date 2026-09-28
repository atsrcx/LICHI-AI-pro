package com.lichiai.agent.routing

import com.lichiai.api.ChatMessage
import com.lichiai.api.LlmClient
import com.lichiai.data.ProviderConfig
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Result of the Agent routing decision.
 */
sealed class AgentRouteDecision {
    data class AgentTask(
        val originalRequest: String,
        val fullGoalWithContext: String,
        val naturalAcknowledgment: String,
        val matchedSkills: List<com.lichiai.skill.model.Skill> = emptyList()
    ) : AgentRouteDecision()

    data class SkillManagement(
        val request: com.lichiai.skill.router.SkillManagementRequest,
        val naturalResponse: String
    ) : AgentRouteDecision()

    data class ClarificationNeeded(
        val question: String
    ) : AgentRouteDecision()

    object NormalConversation : AgentRouteDecision()
}

@Serializable
private data class LlmIntentClassification(
    val route: String? = null,
    @SerialName("target_app") val targetApp: String? = null,
    @SerialName("target_contact") val targetContact: String? = null,
    val action: String? = null,
    @SerialName("message_content") val messageContent: String? = null,
    val acknowledgment: String? = null,
    @SerialName("clarification_question") val clarificationQuestion: String? = null
)

/**
 * AgentCapabilityRouter implements strict capability routing rules:
 *
 * 1. PRIORITY 1: Existing deterministic Lichi capabilities (calls, wake word, standard chat)
 * 2. PRIORITY 2: Autonomous Agent tasks (phone UI automation, multi-step app navigation in English, Hindi, and Hinglish)
 * 3. FALLBACK: Normal conversational LLM response
 *
 * Never steals existing Lichi call or conversational commands!
 */
object AgentCapabilityRouter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val EXISTING_CALL_PATTERNS = listOf(
        "call ", "dial ", "phone ", "ring ",
        "answer", "pickup", "pick up", "utha lo", "uthana",
        "reject", "decline", "hang up", "end call", "kaat do", "cut call",
        "mute", "unmute", "speaker", "speakerphone", "hold"
    )

    private val EXISTING_CALL_SUFFIXES = listOf(
        "ko phone lagao", "ko phone karo", "ko call lagao", "ko call karo",
        "ko call milao", "ko dial karo"
    )

    private val COMMON_APPS = listOf(
        "app", "application", "instagram", "whatsapp", "telegram", "settings", "youtube", "spotify",
        "chrome", "twitter", "facebook", "uber", "ola", "zomato", "swiggy",
        "amazon", "flipkart", "gmail", "maps", "camera", "gallery", "clock",
        "calculator", "files", "contacts", "play store", "linkedin", "snapchat", "reddit",
        // Devanagari app names
        "इंस्टाग्राम", "व्हाट्सएप", "टेलीग्राम", "सेटिंग्स", "यूट्यूब", "स्पॉटिफ़ाई",
        "क्रोम", "उबर", "ज़ोमैटो", "स्विगी", "कैमरा", "गैलरी"
    )

    private val ENGLISH_ACTION_VERBS = listOf(
        "and message", "and text", "and send", "and search", "and play",
        "and turn", "and change", "and toggle", "and post", "and open",
        "and find", "and buy", "and order", "and book", "and click", "and tap"
    )

    private val HINDI_ACTION_VERBS = listOf(
        "kholo aur", "khol aur", "khol do aur", "open karo aur", "open kar do aur",
        "khol karke", "open karke", "me jao aur", "mein jao aur", "pe jao aur", "par jao aur",
        "ko message", "ko text", "pe message", "par message", "ko bhejo", "par bhejo",
        "message kar do", "message bhejo", "message karo", "text kar do", "text bhejo",
        "on kar do", "off kar do", "chalu kar do", "band kar do", "badha do", "kam kar do",
        "chala do", "chalao", "play karo", "play kar do", "search karo", "search kar do",
        "dhoondo", "dhoond do", "order karo", "order kar do", "book karo", "book kar do",
        "gana lagao", "gaana lagao", "gana laga do", "gaana laga do", "gana chalao", "gaana chala do",
        "gana bajao", "gana baja do", "lagao", "laga do", "lagana", "bajao", "baja do",
        // Single verb triggers when app is mentioned
        "kholo", "khol do", "kholna", "open karo", "open kar do",
        // Devanagari triggers
        "खोलो", "खोल दो", "चलाओ", "चला दो", "मैसेज कर दो", "मैसेज करो", "भेज दो",
        "चालू कर दो", "चालू करो", "बंद कर दो", "सर्च करो", "गाना लगाओ", "गाना चलाओ", "लगा दो", "बजाओ"
    )

    private val DIRECT_AGENT_KEYWORDS = listOf(
        "automate", "autonomous agent", "ui agent", "phone agent",
        "click on", "tap on", "scroll down", "scroll up", "scroll to",
        "read my screen", "read screen", "what's on my screen", "what is on my screen",
        "screen pe kya", "screen par kya", "screen padho", "screen read karo",
        "send a whatsapp", "send a message on whatsapp", "send a message on telegram",
        "send a message on instagram", "book on uber", "order on zomato", "order on swiggy"
    )

    private const val INTENT_GATEWAY_SYSTEM_PROMPT = """You are the Natural Language Intent Gateway for Lichi on Android.
Classify the user's input (English, Hindi, Hinglish, Devanagari) into strictly one of three routes:

1. AGENT_TASK:
The user wants to automate an Android phone action, navigate apps, interact with UI, type messages in third-party apps, or control settings.
Examples:
- "Open Instagram and message Aditya"
- "Instagram kholo aur Aditya ko message kar do"
- "Instagram open kar do aur Aditya ko message bhej do"
- "इंस्टाग्राम खोलो और आदित्य को मैसेज कर दो"
- "Bhai Instagram chala do aur Aditya ko bol dena ki main 10 minute mein aa raha hoon"
- "WhatsApp kholo aur Rahul ko message kar do"
- "Settings khol do aur Bluetooth on kar do"
- "Chrome kholo aur Google par search karo"
- "YouTube chalao aur Arijit Singh ka gaana lagao"
- "Read my screen" / "Screen pe kya hai"
For AGENT_TASK, output:
- target_app: Target app name
- target_contact: Contact name if mentioned
- action: Short action verb
- message_content: Message text if specified
- acknowledgment: Short, warm, natural spoken confirmation in the user's language (e.g. "Haan, main kar rahi hoon", "Sure, on it")

2. CLARIFICATION_NEEDED:
The user wants to perform an action but omitted critical required parameters (e.g. "Aditya ko woh bhej do" with no message content).
For CLARIFICATION_NEEDED, output clarification_question in user's language (e.g. "Kaunsa message bheju?").

3. NORMAL_CONVERSATION:
General queries, chat, knowledge questions, explanations, math, or jokes.
Examples:
- "India ki capital kya hai?"
- "Mujhe ek joke sunao"
- "Tum kya kar sakti ho?"
- "Gemini aur GPT mein kya difference hai?"

Output ONLY valid raw JSON:
{
  "route": "AGENT_TASK" | "CLARIFICATION_NEEDED" | "NORMAL_CONVERSATION",
  "target_app": string or null,
  "target_contact": string or null,
  "action": string or null,
  "message_content": string or null,
  "acknowledgment": string or null,
  "clarification_question": string or null
}"""

    /**
     * Determines whether [text] matches fast-path heuristics for Autonomous Agent V2.
     */
    fun isAutonomousAgentTask(text: String): Boolean {
        val raw = text.trim()
        if (raw.isBlank()) return false
        val lower = raw.lowercase(Locale.getDefault())

        // 1. NEVER steal existing phone call commands
        if (isExistingCallCommand(lower)) {
            return false
        }

        // 2. Explicit prefix trigger (agent: / agent <task> / automate:)
        if (lower.startsWith("agent:") || lower.startsWith("agent ") || lower.startsWith("automate:") || lower.startsWith("automate ")) {
            return true
        }

        // 3. Direct agent keywords (screen interaction, explicit automation)
        for (kw in DIRECT_AGENT_KEYWORDS) {
            if (lower.contains(kw)) {
                return true
            }
        }

        // 4. App + Action combination detection (e.g. "Instagram kholo aur Aditya ko message kar do")
        val mentionsApp = COMMON_APPS.any { lower.contains(it) }

        if (mentionsApp) {
            // Check Hindi/Hinglish action indicators
            for (action in HINDI_ACTION_VERBS) {
                if (lower.contains(action)) {
                    return true
                }
            }

            // Check English multi-step actions (e.g. "open instagram and message aditya")
            for (action in ENGLISH_ACTION_VERBS) {
                if (lower.contains(action)) {
                    return true
                }
            }

            // Regex for: (open|launch|start|go to) <app>
            if (Regex("""\b(open|launch|start|go to)\s+[a-z0-9_\-\s]+""").containsMatchIn(lower)) {
                return true
            }

            // Regex for: <app> (kholo|khol do|open karo|open kar do|chalao|chala do)
            if (Regex("""\b[a-z0-9_\-]+\s+(kholo|khol do|open karo|open kar do|chalao|chala do)\b""").containsMatchIn(lower)) {
                return true
            }
        }

        return false
    }

    private val CANDIDATE_ACTION_INDICATORS = listOf(
        "kholo", "khol", "open", "launch", "chalao", "chala", "band", "close",
        "bhejo", "bhej", "send", "message", "msg", "type", "likho", "likh",
        "search", "dhoondo", "dhoondho", "scroll", "swipe", "click", "tap",
        "dabao", "screen", "screenshot", "padho", "padh", "read", "automate",
        "setting", "settings", "bluetooth", "wifi", "hotspot", "volume",
        "brightness", "install", "download", "uninstall", "delete", "post",
        "story", "reel", "order", "book", "gaana", "song"
    )

    private fun isPotentialAgentCandidate(lower: String, raw: String): Boolean {
        if (raw.any { it in '\u0900'..'\u097F' }) return true
        if (COMMON_APPS.any { lower.contains(it) }) return true
        if (CANDIDATE_ACTION_INDICATORS.any { lower.contains(it) }) return true
        return false
    }

    /**
     * Resolves the routing decision for [text] using the existing selected LLM provider
     * with fail-safe fallback to fast heuristics and normal conversation.
     */
    suspend fun resolveRouting(
        text: String,
        provider: ProviderConfig?,
        modelId: String?,
        llmClient: LlmClient,
        availableSkills: List<com.lichiai.skill.model.Skill> = emptyList()
    ): AgentRouteDecision {
        val raw = text.trim()
        if (raw.isBlank()) return AgentRouteDecision.NormalConversation
        val lower = raw.lowercase(Locale.getDefault())

        // 1. Hard guard: NEVER route phone calls to Agent
        if (isExistingCallCommand(lower)) {
            return AgentRouteDecision.NormalConversation
        }

        // 2. Skill Management commands detection (prevents accidental device task execution)
        val managementReq = com.lichiai.skill.router.SkillRouter.detectManagementRequest(raw)
        if (managementReq != null) {
            return AgentRouteDecision.SkillManagement(
                request = managementReq,
                naturalResponse = generateSkillManagementAcknowledgment(managementReq)
            )
        }

        // 3. Fast Deterministic Path for Confident Agent Tasks
        // If deterministic rules already establish that this is an Autonomous Agent task,
        // execute immediately without waiting for slow LLM classification.
        if (isAutonomousAgentTask(raw)) {
            val matchedSkills = com.lichiai.skill.router.SkillRouter.matchSkills(raw, availableSkills)
            return AgentRouteDecision.AgentTask(
                originalRequest = raw,
                fullGoalWithContext = raw,
                naturalAcknowledgment = generateFastAcknowledgment(raw),
                matchedSkills = matchedSkills
            )
        }

        // 4. Fast Deterministic Path for Standard Conversation
        // If the query contains no device actions, app names, or automation triggers,
        // it is an ordinary conversational query (e.g. "India ki capital kya hai?").
        // Avoid slow LLM classification roundtrip to allow immediate streaming.
        if (!isPotentialAgentCandidate(lower, raw)) {
            return AgentRouteDecision.NormalConversation
        }

        // 5. If provider is unavailable or not configured, fall back to normal conversation
        if (provider == null || provider.apiKey.isBlank() || modelId.isNullOrBlank()) {
            return AgentRouteDecision.NormalConversation
        }

        // 6. Ambiguous Candidate Path: Query LLM Provider for semantic classification
        try {
            val responseText = withTimeoutOrNull(4_000) {
                val messages = listOf(
                    ChatMessage("system", INTENT_GATEWAY_SYSTEM_PROMPT),
                    ChatMessage("user", raw)
                )
                llmClient.chatCompletion(
                    provider = provider,
                    modelId = modelId,
                    messages = messages,
                    temperature = 0.0f
                )
            }

            if (!responseText.isNullOrBlank()) {
                val parsed = extractClassificationJson(responseText)
                if (parsed != null) {
                    when (parsed.route?.uppercase(Locale.getDefault())) {
                        "AGENT_TASK" -> {
                            val contextDetails = listOfNotNull(
                                parsed.targetApp?.takeIf { it.isNotBlank() }?.let { "Target App: $it" },
                                parsed.targetContact?.takeIf { it.isNotBlank() }?.let { "Contact: $it" },
                                parsed.action?.takeIf { it.isNotBlank() }?.let { "Action: $it" },
                                parsed.messageContent?.takeIf { it.isNotBlank() }?.let { "Message: \"$it\"" }
                            )
                            val enrichedGoal = if (contextDetails.isNotEmpty()) {
                                "$raw\n[Context: ${contextDetails.joinToString(" | ")}]"
                            } else {
                                raw
                            }
                            val ack = parsed.acknowledgment?.takeIf { it.isNotBlank() }
                                ?: generateFastAcknowledgment(raw)
                            val matchedSkills = com.lichiai.skill.router.SkillRouter.matchSkills(raw, availableSkills)
                            return AgentRouteDecision.AgentTask(
                                originalRequest = raw,
                                fullGoalWithContext = enrichedGoal,
                                naturalAcknowledgment = ack,
                                matchedSkills = matchedSkills
                            )
                        }
                        "CLARIFICATION_NEEDED" -> {
                            val q = parsed.clarificationQuestion?.takeIf { it.isNotBlank() }
                                ?: "Kaunsa task perform karna hai?"
                            return AgentRouteDecision.ClarificationNeeded(q)
                        }
                        "NORMAL_CONVERSATION" -> {
                            return AgentRouteDecision.NormalConversation
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // LLM classification failed or timed out; proceed to fail-safe fallback
        }

        // 7. Fail-safe fallback: If fast heuristic matches, still route to Agent safely
        return if (isAutonomousAgentTask(raw)) {
            val matchedSkills = com.lichiai.skill.router.SkillRouter.matchSkills(raw, availableSkills)
            AgentRouteDecision.AgentTask(
                originalRequest = raw,
                fullGoalWithContext = raw,
                naturalAcknowledgment = generateFastAcknowledgment(raw),
                matchedSkills = matchedSkills
            )
        } else {
            AgentRouteDecision.NormalConversation
        }
    }

    private fun generateSkillManagementAcknowledgment(request: com.lichiai.skill.router.SkillManagementRequest): String {
        return when (request) {
            is com.lichiai.skill.router.SkillManagementRequest.ListSkills -> "Showing installed Lichi skills."
            is com.lichiai.skill.router.SkillManagementRequest.EnableSkill -> "Enabling ${request.targetName} skill."
            is com.lichiai.skill.router.SkillManagementRequest.DisableSkill -> "Disabling ${request.targetName} skill."
            is com.lichiai.skill.router.SkillManagementRequest.DeleteSkill -> "Deleting ${request.targetName} skill."
            is com.lichiai.skill.router.SkillManagementRequest.ExportSkill -> "Exporting ${request.targetName} skill."
            is com.lichiai.skill.router.SkillManagementRequest.ProposeCreateSkill -> "Creating a skill specification for ${request.topicOrGoal}."
        }
    }

    private fun extractClassificationJson(text: String): LlmIntentClassification? {
        return runCatching {
            val startIdx = text.indexOf('{')
            val endIdx = text.lastIndexOf('}')
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                val jsonStr = text.substring(startIdx, endIdx + 1)
                json.decodeFromString(LlmIntentClassification.serializer(), jsonStr)
            } else {
                null
            }
        }.getOrNull()
    }

    fun generateFastAcknowledgment(text: String): String {
        val lower = text.lowercase(Locale.getDefault())
        val isHindi = lower.contains("kholo") || lower.contains("khol") || lower.contains("kar do") ||
                lower.contains("karo") || lower.contains("chala") || lower.contains("bhejo") ||
                lower.contains("bhej") || text.any { it in '\u0900'..'\u097F' }
        return if (isHindi) {
            "Haan, main kar rahi hoon."
        } else {
            "Sure, working on it now."
        }
    }

    private fun isExistingCallCommand(lower: String): Boolean {
        for (pattern in EXISTING_CALL_PATTERNS) {
            if (lower.startsWith(pattern) || lower == pattern.trim()) {
                return true
            }
        }
        for (suffix in EXISTING_CALL_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true
            }
        }
        return false
    }
}

package com.lichiai.orchestrator.planner

import com.lichiai.api.ChatMessage
import com.lichiai.api.LlmClient
import com.lichiai.data.ProviderConfig
import com.lichiai.intent.model.IntentContext
import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.RiskLevel
import com.lichiai.orchestrator.catalog.CapabilityCatalogV2
import com.lichiai.orchestrator.model.DecisionMode
import com.lichiai.orchestrator.model.OrchestrationDecision
import com.lichiai.orchestrator.model.PlanStep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
internal data class StructuredStepJson(
    val capability: String,
    val action: String,
    val arguments: Map<String, String> = emptyMap(),
    @SerialName("expected_outcome") val expectedOutcome: String? = null
)

@Serializable
internal data class StructuredPlannerOutput(
    val mode: String = "EXECUTE", // EXECUTE | CONVERSE | CLARIFY | CONFIRM | CANCEL
    val goal: String,
    @SerialName("reasoning_summary") val reasoningSummary: String = "",
    val acknowledgment: String? = null,
    val steps: List<StructuredStepJson> = emptyList(),
    @SerialName("clarification_question") val clarificationQuestion: String? = null,
    @SerialName("confirmation_prompt") val confirmationPrompt: String? = null,
    @SerialName("direct_response") val directResponse: String? = null,
    @SerialName("dialogue_act") val dialogueAct: String? = null
)

/**
 * Semantic Brain: Translates natural user requests across English, Hindi, Hinglish,
 * into structured multi-step plans coordinating verified existing Lichi capabilities.
 */
class UniversalLlmPlanner(
    private val llmClient: LlmClient,
    private val catalog: CapabilityCatalogV2
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val SYSTEM_PROMPT = """You are Lichi's Universal Task Orchestrator.
Your job is to understand what the user wants and coordinate existing Lichi capabilities.
You are NOT the direct executor.
You must use only capabilities supplied by the system.
You must not invent tools or capabilities.
You must not invent device state or hallucinate previous actions.
You must distinguish informational questions from device/browser actions.
You must distinguish background factual web search from visible Chromium browser interaction.
You must preserve task context, references (e.g. "yeh wala", "doosra result", "ismein download dhundo"), user corrections ("nahi browser mein karo", "nahi Rohit ko"), negation, and cancellation ("chhodo", "rehne do").

Output strictly valid JSON with NO markdown code fences and NO additional text:
{
  "mode": "EXECUTE" | "CONVERSE" | "CLARIFY" | "CONFIRM" | "CANCEL" | "INTERRUPT" | "RESUME" | "MODIFY_TASK",
  "goal": "Concise statement of user goal",
  "reasoning_summary": "1 sentence on capability choice",
  "acknowledgment": "Natural, polite spoken acknowledgment in user's language (e.g. 'Browser mein PUBG search kar raha hoon')",
  "steps": [
    {
      "capability": "BROWSER" | "WEB_SEARCH" | "ANDROID_AGENT" | "CALLS" | "TERMINAL" | "TIME_REMINDER" | "MEDIA_YOUTUBE" | "DEVICE_CONTROL" | "SKILL_MANAGEMENT" | "CHAT",
      "action": "SEARCH" | "NAVIGATE" | "CLICK_CANDIDATE" | "SCROLL_DOWN" | "FIND_ON_PAGE" | "AUTOMATE" | "OPEN_APP" | "CALL" | "EXECUTE" | "SSH_CONNECT" | "OPEN" | "CREATE" | "LIST" | "PLAY" | "SET_SETTING" | "CONVERSE",
      "arguments": { "key": "value" },
      "expected_outcome": "What success looks like for this step"
    }
  ],
  "clarification_question": null or string if mode is CLARIFY,
  "confirmation_prompt": null or string if mode is CONFIRM,
  "direct_response": null or string if mode is CONVERSE
}"""
    }

    suspend fun plan(
        rawInput: String,
        normalizedInput: String,
        context: IntentContext,
        provider: ProviderConfig,
        modelId: String
    ): OrchestrationDecision? {
        if (provider.apiKey.isBlank() && !provider.baseUrl.contains("localhost")) {
            return null
        }

        val prompt = buildUserPrompt(rawInput, normalizedInput, context)

        return try {
            val responseText = llmClient.chatCompletion(
                provider = provider,
                modelId = modelId,
                messages = listOf(
                    ChatMessage(role = "system", content = SYSTEM_PROMPT),
                    ChatMessage(role = "user", content = prompt)
                ),
                temperature = 0.0f
            )

            val parsed = parseJson(responseText) ?: return null
            validateAndConstructDecision(parsed, rawInput)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildUserPrompt(
        rawInput: String,
        normalizedInput: String,
        context: IntentContext
    ): String {
        val convId = context.conversationId ?: "default_session"
        val semanticCtx = com.lichiai.context.engine.UniversalContextContinuityEngine.getInstance().getContext(convId)

        return buildString {
            append(catalog.formatCatalogForPrompt())
            append("\nCURRENT TASK & CONVERSATION CONTEXT:\n")
            if (!semanticCtx.activeTopic.isNullOrBlank()) {
                append("• Active Topic: ${semanticCtx.activeTopic}\n")
            }
            if (!semanticCtx.activeGoal.isNullOrBlank()) {
                append("• Active Goal: ${semanticCtx.activeGoal}\n")
            }
            val activeEntity = semanticCtx.getActiveEntity()
            if (activeEntity != null) {
                append("• Active Focus Entity: ${activeEntity.toCompactSummary()}\n")
            }
            if (semanticCtx.verifiedFacts.isNotEmpty()) {
                append("• Verified Facts: ${semanticCtx.verifiedFacts.entries.take(8).joinToString { "${it.key}: ${it.value}" }}\n")
            }
            if (!context.currentBrowserUrl.isNullOrBlank()) {
                append("• Active Browser URL: ${context.currentBrowserUrl}\n")
            }
            if (!context.currentBrowserTitle.isNullOrBlank()) {
                append("• Active Browser Title: ${context.currentBrowserTitle}\n")
            }
            if (context.browserCandidates.isNotEmpty()) {
                append("• Active Browser Candidates: ${context.browserCandidates.take(5).joinToString(" | ")}\n")
            }
            if (!context.lastSearchQuery.isNullOrBlank()) {
                append("• Last Search Query: ${context.lastSearchQuery}\n")
            }
            if (context.lastExecutedCapability != null) {
                append("• Last Executed Capability: ${context.lastExecutedCapability.name}\n")
            }
            if (context.recentEntities.isNotEmpty()) {
                append("• Active Entities: ${context.recentEntities}\n")
            }
            if (context.recentTurns.isNotEmpty()) {
                append("• Recent Conversation:\n")
                for ((u, a) in context.recentTurns.takeLast(3)) {
                    append("  User: $u\n  Assistant: ${a.take(80)}\n")
                }
            }

            append("\nUSER REQUEST:\n")
            append("Raw: \"$rawInput\"\n")
            if (normalizedInput != rawInput) {
                append("Normalized: \"$normalizedInput\"\n")
            }
        }
    }

    private fun parseJson(raw: String): StructuredPlannerOutput? {
        val trimmed = raw.trim()
        val jsonStr = if (trimmed.contains("{") && trimmed.contains("}")) {
            val start = trimmed.indexOf("{")
            val end = trimmed.lastIndexOf("}")
            trimmed.substring(start, end + 1)
        } else trimmed

        return runCatching {
            json.decodeFromString<StructuredPlannerOutput>(jsonStr)
        }.getOrNull()
    }

    private fun validateAndConstructDecision(
        parsed: StructuredPlannerOutput,
        rawInput: String
    ): OrchestrationDecision? {
        val mode = when (parsed.mode.uppercase(Locale.ROOT)) {
            "CONVERSE" -> DecisionMode.CONVERSE
            "CLARIFY" -> DecisionMode.CLARIFY
            "CONFIRM" -> DecisionMode.CONFIRM
            "CANCEL" -> DecisionMode.CANCEL
            "INTERRUPT" -> DecisionMode.INTERRUPT
            "RESUME" -> DecisionMode.RESUME
            "MODIFY_TASK" -> DecisionMode.MODIFY_TASK
            else -> DecisionMode.EXECUTE
        }

        val naturalAck = parsed.acknowledgment.takeUnless { it.isNullOrBlank() }
            ?: when (mode) {
                DecisionMode.CONVERSE -> ""
                DecisionMode.CLARIFY -> parsed.clarificationQuestion ?: "Aap kya karna chahte hain?"
                DecisionMode.CONFIRM -> parsed.confirmationPrompt ?: "Kya aap ise proceed karna chahte hain?"
                DecisionMode.CANCEL -> "Task cancel kar diya gaya hai."
                DecisionMode.INTERRUPT -> "Task pause kar rahi hoon..."
                DecisionMode.RESUME -> "Task resume kar rahi hoon..."
                DecisionMode.MODIFY_TASK -> "Plan update kar diya."
                DecisionMode.DONE -> "Task complete ho gaya."
                DecisionMode.EXECUTE -> "Sure, on it."
            }

        val resolvedDialogueAct = parsed.dialogueAct?.let { actStr ->
            runCatching { com.lichiai.context.model.DialogueAct.valueOf(actStr.uppercase(Locale.ROOT)) }.getOrNull()
        }

        if (mode == DecisionMode.CONVERSE) {
            return OrchestrationDecision(
                mode = DecisionMode.CONVERSE,
                goal = parsed.goal.ifBlank { rawInput },
                plan = emptyList(),
                naturalAcknowledgment = "",
                reasoningSummary = parsed.reasoningSummary,
                isDirectResponse = true,
                directResponseText = parsed.directResponse ?: "",
                dialogueAct = resolvedDialogueAct ?: com.lichiai.context.model.DialogueAct.QUESTION
            )
        }

        if (mode == DecisionMode.CLARIFY) {
            return OrchestrationDecision(
                mode = DecisionMode.CLARIFY,
                goal = parsed.goal.ifBlank { rawInput },
                plan = emptyList(),
                naturalAcknowledgment = naturalAck,
                clarificationQuestion = parsed.clarificationQuestion ?: "Please clarify your request.",
                reasoningSummary = parsed.reasoningSummary,
                dialogueAct = resolvedDialogueAct ?: com.lichiai.context.model.DialogueAct.CLARIFICATION
            )
        }

        if (mode == DecisionMode.CANCEL) {
            return OrchestrationDecision(
                mode = DecisionMode.CANCEL,
                goal = parsed.goal.ifBlank { rawInput },
                plan = emptyList(),
                naturalAcknowledgment = naturalAck,
                reasoningSummary = parsed.reasoningSummary,
                dialogueAct = resolvedDialogueAct ?: com.lichiai.context.model.DialogueAct.CANCELLATION
            )
        }

        if (mode == DecisionMode.RESUME) {
            return OrchestrationDecision(
                mode = DecisionMode.RESUME,
                goal = parsed.goal.ifBlank { rawInput },
                plan = emptyList(),
                naturalAcknowledgment = naturalAck,
                reasoningSummary = parsed.reasoningSummary,
                dialogueAct = resolvedDialogueAct ?: com.lichiai.context.model.DialogueAct.CONTINUATION
            )
        }

        // Validate plan steps
        val validSteps = mutableListOf<PlanStep>()
        for ((idx, s) in parsed.steps.withIndex()) {
            val capEnum = runCatching { LichiCapability.valueOf(s.capability.uppercase(Locale.ROOT)) }.getOrNull()
                ?: continue // Reject hallucinated capabilities

            val spec = catalog.findSpec(capEnum)
            if (spec == null || !spec.isAvailable()) {
                // Capability is unknown or disabled
                continue
            }

            // Check action validity
            val validAction = spec.supportedActions.firstOrNull {
                it.actionName.equals(s.action, ignoreCase = true)
            }?.actionName ?: spec.supportedActions.firstOrNull()?.actionName ?: s.action.uppercase(Locale.ROOT)

            validSteps.add(
                PlanStep(
                    stepIndex = idx,
                    capability = capEnum,
                    action = validAction,
                    arguments = s.arguments,
                    expectedOutcome = s.expectedOutcome ?: "Completed ${capEnum.displayName}",
                    riskLevel = spec.riskLevel
                )
            )
        }

        if (mode == DecisionMode.EXECUTE && validSteps.isEmpty()) {
            // No valid execution steps were validated
            return null
        }

        val hasHighRisk = validSteps.any { it.riskLevel == RiskLevel.HIGH }
        val requiresConfirm = mode == DecisionMode.CONFIRM || (parsed.confirmationPrompt != null)

        return OrchestrationDecision(
            mode = if (requiresConfirm) DecisionMode.CONFIRM else DecisionMode.EXECUTE,
            goal = parsed.goal.ifBlank { rawInput },
            plan = validSteps,
            naturalAcknowledgment = naturalAck,
            reasoningSummary = parsed.reasoningSummary,
            confirmationPrompt = parsed.confirmationPrompt,
            requiresConfirmation = requiresConfirm
        )
    }
}

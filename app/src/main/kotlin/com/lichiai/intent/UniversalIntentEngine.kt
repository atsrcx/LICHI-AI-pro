package com.lichiai.intent

import com.lichiai.api.LlmClient
import com.lichiai.data.ProviderConfig
import com.lichiai.intent.classifier.UniversalLlmIntentClassifier
import com.lichiai.intent.context.ContextBuilder
import com.lichiai.intent.model.AmbiguityLevel
import com.lichiai.intent.model.FreshnessRequirement
import com.lichiai.intent.model.IntentContext
import com.lichiai.intent.model.IntentResolutionResult
import com.lichiai.intent.model.IntentType
import com.lichiai.intent.model.IntentUnderstanding
import com.lichiai.intent.model.ResolutionSource
import com.lichiai.intent.model.ResolvedIntent
import com.lichiai.intent.normalizer.InputNormalizer
import com.lichiai.intent.registry.CapabilityRegistry
import com.lichiai.intent.router.DeterministicRuleRouter
import com.lichiai.intent.router.ReferenceAndCorrectionResolver
import com.lichiai.intent.router.SemanticRouter
import com.lichiai.skill.model.Skill

/**
 * Universal Intent Engine for Lichi AI.
 * Understands natural speech in English, Hindi, Hinglish, and Roman Hindi,
 * and routes user requests deterministically and semantically to the correct peer capability.
 */
class UniversalIntentEngine(
    val capabilityRegistry: CapabilityRegistry,
    val contextBuilder: ContextBuilder,
    private val deterministicRouter: DeterministicRuleRouter = DeterministicRuleRouter(),
    private val semanticRouter: SemanticRouter = SemanticRouter(),
    private val llmClient: LlmClient? = null
) {

    private val llmClassifier = llmClient?.let { UniversalLlmIntentClassifier(it) }

    /**
     * Resolves user intent across all layers:
     * 1. Normalization (STT cleanup, Hinglish phonetics, whitespace)
     * 2. Contextual Reference & Correction Resolution ("doosra result", "ismein download dhundo", "nahi browser mein karo")
     * 3. Deterministic Rule Router (Direct calls, direct URLs, browser controls, device controls)
     * 4. Semantic Router (Deep natural language & Hinglish parsing)
     * 5. Provider-neutral LLM Classification (fallback for ambiguous/complex phrasing)
     * 6. Default Conversational Chat
     */
    suspend fun resolve(
        rawInput: String,
        context: IntentContext? = null,
        provider: ProviderConfig? = null,
        modelId: String? = null,
        allowLlmFallback: Boolean = true
    ): IntentResolutionResult {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return IntentResolutionResult(
                intent = ResolvedIntent.NormalChat(prompt = ""),
                confidence = 1.0f,
                source = ResolutionSource.FALLBACK,
                normalizedInput = "",
                rationale = "Blank input."
            )
        }

        // 1. Input Normalization
        val normalized = InputNormalizer.normalize(trimmed)
        val activeContext = context ?: contextBuilder.buildContext()

        // 2. Reference and Correction Check (High Priority)
        val contextResolution = ReferenceAndCorrectionResolver.resolve(normalized, activeContext)
        if (contextResolution != null && contextResolution.second >= 0.85f) {
            val isCorr = normalized.contains("nahi") || normalized.contains("galat")
            val isCancel = contextResolution.first is ResolvedIntent.Cancellation
            val understanding = createUnderstanding(
                intent = contextResolution.first,
                rawText = trimmed,
                isFollowUp = !isCorr && !isCancel,
                isCorrection = isCorr,
                isCancellation = isCancel
            )
            return IntentResolutionResult(
                intent = contextResolution.first,
                confidence = contextResolution.second,
                source = if (isCorr) ResolutionSource.CORRECTION else ResolutionSource.CONTEXT_FOLLOWUP,
                normalizedInput = normalized,
                rationale = "Resolved via active conversation/browser context.",
                understanding = understanding
            )
        }

        // 3. Deterministic Rule Router (Zero-LLM fast path)
        val deterministicResult = deterministicRouter.route(normalized)
        if (deterministicResult != null) {
            val understanding = createUnderstanding(
                intent = deterministicResult.first,
                rawText = trimmed
            )
            return IntentResolutionResult(
                intent = deterministicResult.first,
                confidence = deterministicResult.second,
                source = ResolutionSource.DETERMINISTIC_RULE,
                normalizedInput = normalized,
                rationale = "Matched deterministic rule.",
                understanding = understanding
            )
        }

        // 4. Semantic Router (Deep natural language & Hinglish parsing)
        val semanticResult = semanticRouter.route(normalized)
        if (semanticResult != null && semanticResult.second >= 0.75f) {
            val understanding = createUnderstanding(
                intent = semanticResult.first,
                rawText = trimmed
            )
            return IntentResolutionResult(
                intent = semanticResult.first,
                confidence = semanticResult.second,
                source = ResolutionSource.SEMANTIC_MATCH,
                normalizedInput = normalized,
                rationale = "Matched semantic pattern.",
                understanding = understanding
            )
        }

        // 5. Provider-neutral LLM Classifier (Fallback for complex or highly conversational phrasing)
        if (allowLlmFallback && llmClassifier != null && provider != null && !modelId.isNullOrBlank()) {
            val llmResult = llmClassifier.classify(
                text = normalized,
                context = activeContext,
                provider = provider,
                modelId = modelId
            )
            if (llmResult != null) {
                return IntentResolutionResult(
                    intent = llmResult.first,
                    confidence = llmResult.second,
                    source = ResolutionSource.LLM_CLASSIFICATION,
                    normalizedInput = normalized,
                    rationale = "Resolved via provider-neutral LLM classification.",
                    understanding = llmResult.third
                )
            }
        }

        // 6. Default to standard conversational chat
        val chatIntent = ResolvedIntent.NormalChat(prompt = rawInput)
        return IntentResolutionResult(
            intent = chatIntent,
            confidence = 0.70f,
            source = ResolutionSource.FALLBACK,
            normalizedInput = normalized,
            rationale = "Defaulted to conversational AI.",
            understanding = createUnderstanding(chatIntent, trimmed)
        )
    }

    private fun createUnderstanding(
        intent: ResolvedIntent,
        rawText: String,
        isFollowUp: Boolean = false,
        isCorrection: Boolean = false,
        isCancellation: Boolean = false
    ): IntentUnderstanding {
        val intentType = when (intent) {
            is ResolvedIntent.BrowserTask -> IntentType.VISIBLE_BROWSER_TASK
            is ResolvedIntent.WebSearchTask -> IntentType.WEB_SEARCH
            is ResolvedIntent.AndroidAgentTask -> IntentType.ANDROID_ACTION
            is ResolvedIntent.CallTask -> IntentType.CALL_ACTION
            is ResolvedIntent.MediaTask -> IntentType.MEDIA_ACTION
            is ResolvedIntent.DeviceControlTask -> IntentType.DEVICE_ACTION
            is ResolvedIntent.TimeReminderTask -> IntentType.DEVICE_ACTION
            is ResolvedIntent.MultiStepTask -> IntentType.MULTI_STEP_TASK
            is ResolvedIntent.Clarification -> IntentType.CLARIFICATION
            is ResolvedIntent.Cancellation -> IntentType.CANCELLATION
            is ResolvedIntent.SkillManagementTask -> IntentType.ANDROID_ACTION
            is ResolvedIntent.TerminalTask -> IntentType.DEVICE_ACTION
            is ResolvedIntent.ResumeTask -> IntentType.CONVERSATION
            is ResolvedIntent.TaskInterruption -> IntentType.CANCELLATION
            is ResolvedIntent.ContextualQuestion -> IntentType.CONVERSATION
            is ResolvedIntent.NormalChat -> IntentType.CONVERSATION
        }

        return IntentUnderstanding(
            userGoal = intent.naturalAcknowledgment.ifBlank { rawText },
            intentType = intentType,
            domain = intent.capability.name,
            desiredOutcome = intent.naturalAcknowledgment,
            isFollowUp = isFollowUp,
            isCorrection = isCorrection,
            isCancellation = isCancellation,
            ambiguity = AmbiguityLevel.KNOWN
        )
    }
}

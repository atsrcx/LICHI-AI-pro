package com.lichiai.agent.client

import android.content.Context
import android.util.Log
import com.lichiai.agent.data.AgentSettings
import com.lichiai.agent.model.AgentOutput
import com.lichiai.api.ChatMessage
import com.lichiai.api.LlmClient
import com.lichiai.data.ProviderStore
import com.lichiai.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * AutonomousAgentLlmProvider provides a clean, unified reasoning adapter for Autonomous Agent V2.
 *
 * It bridges two operational reasoning backends into ONE canonical Agent contract:
 * 1. Current Provider Mode (useCurrentProvider = true):
 *    Reuses the active Lichi provider, active model, endpoints, headers, and credentials from
 *    ProviderStore & SettingsRepository via LlmClient.
 * 2. Dedicated Gemini Mode (useCurrentProvider = false):
 *    Direct unproxied communication with the Google Gemini API using dedicated Agent credentials.
 */
class AgentLlmProvider(
    private val context: Context,
    val geminiClient: AgentGeminiClient = AgentGeminiClient(),
    private val llmClient: LlmClient = LlmClient(),
    private val settingsRepository: SettingsRepository = SettingsRepository(context),
    private val providerStore: ProviderStore = ProviderStore(context)
) {
    companion object {
        private const val TAG = "AgentLlmProvider"
    }

    /**
     * Executes the agent reasoning step and produces structured [AgentOutput].
     */
    suspend fun generateAgentOutput(
        settings: AgentSettings,
        prompt: String
    ): Result<AgentOutput> {
        return if (settings.useCurrentProvider) {
            generateViaCurrentProvider(prompt)
        } else {
            generateViaDedicatedGemini(settings, prompt)
        }
    }

    private suspend fun generateViaDedicatedGemini(
        settings: AgentSettings,
        prompt: String
    ): Result<AgentOutput> {
        if (settings.apiKey.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Agent Gemini API key is missing. Please configure it in Settings under Autonomous Agent V2 or enable 'Use Current Provider'.")
            )
        }
        return geminiClient.generateAgentOutput(
            apiKey = settings.apiKey,
            model = settings.model,
            prompt = prompt
        )
    }

    private suspend fun generateViaCurrentProvider(prompt: String): Result<AgentOutput> {
        return try {
            val appSettings = settingsRepository.settings.first()
            val providers = providerStore.snapshot()
            val activeProvider = providers.firstOrNull { it.id == appSettings.activeProviderId }
                ?: providers.firstOrNull()

            if (activeProvider == null) {
                return Result.failure(
                    IllegalStateException("No active LLM provider configured. Please add and configure a provider in Settings > Providers.")
                )
            }

            val activeModel = appSettings.activeModel.ifBlank {
                activeProvider.models.firstOrNull() ?: ""
            }
            if (activeModel.isBlank()) {
                return Result.failure(
                    IllegalStateException("No model selected for provider '${activeProvider.name}'. Please select a model in Settings.")
                )
            }

            Log.d(TAG, "[Agent Provider] Reasoning via Current Provider '${activeProvider.name}' (model: $activeModel)")

            val messages = listOf(
                ChatMessage(
                    role = "system",
                    content = "You are an autonomous Android device agent. Respond strictly with a valid JSON object matching the requested schema. Do not enclose in markdown explanation or conversational commentary."
                ),
                ChatMessage(
                    role = "user",
                    content = prompt
                )
            )

            val rawResponse = llmClient.chatCompletion(
                provider = activeProvider,
                modelId = activeModel,
                messages = messages,
                temperature = 0.1f
            )

            if (rawResponse.isBlank()) {
                return Result.failure(
                    IllegalStateException("Provider '${activeProvider.name}' returned an empty response.")
                )
            }

            val parsedOutput = geminiClient.parseStructuredAgentOutput(rawResponse)
            Result.success(parsedOutput)
        } catch (e: Exception) {
            Log.e(TAG, "[Agent Provider] Current provider reasoning failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}

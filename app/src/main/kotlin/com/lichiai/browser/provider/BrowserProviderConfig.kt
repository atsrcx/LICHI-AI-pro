package com.lichiai.browser.provider

import kotlinx.serialization.Serializable

enum class BrowserProviderType {
    GEMINI,
    OPENAI,
    ANTHROPIC,
    OLLAMA,
    GROQ,
    CUSTOM
}

@Serializable
data class BrowserProviderConfig(
    val id: String,
    val name: String,
    val type: BrowserProviderType,
    val endpoint: String = "",
    val apiKey: String = "",
    val activeModel: String = "",
    val isEnabled: Boolean = true
)

@Serializable
data class BrowserAgentProviderSettings(
    val useCurrentProvider: Boolean = true,
    val activeProviderId: String = "gemini_browser",
    val activeModel: String = "gemini-2.5-flash",
    val fallbackProviderId: String? = null,
    val zeroLlmFastPathEnabled: Boolean = true,
    val maxTokensPerTask: Int = 1000
)

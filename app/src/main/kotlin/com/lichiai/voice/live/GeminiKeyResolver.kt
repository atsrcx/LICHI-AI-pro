package com.lichiai.voice.live

import com.lichiai.data.ProviderConfig
import com.lichiai.data.ProviderStore

/**
 * Resolves the Google Gemini API key from configured providers and environment variables.
 */
object GeminiKeyResolver {

    fun isGeminiProvider(provider: ProviderConfig?): Boolean {
        if (provider == null) return false
        val name = provider.name.lowercase()
        val url = provider.baseUrl.lowercase()
        return name.contains("gemini") ||
                url.contains("generativelanguage.googleapis.com") ||
                url.contains("gemini") ||
                provider.models.any { it.contains("gemini", ignoreCase = true) }
    }

    suspend fun resolveGeminiProvider(
        activeProvider: ProviderConfig?,
        providerStore: ProviderStore?
    ): ProviderConfig? {
        if (isGeminiProvider(activeProvider) && activeProvider?.apiKey?.isNotBlank() == true) {
            return activeProvider
        }
        val all = providerStore?.snapshot() ?: emptyList()
        val gemini = all.firstOrNull { isGeminiProvider(it) && it.apiKey.isNotBlank() }
        if (gemini != null) return gemini

        // If the active provider has an API key (e.g. user set Gemini as custom provider)
        if (activeProvider != null && activeProvider.apiKey.isNotBlank()) {
            return activeProvider
        }
        return null
    }

    suspend fun resolveApiKey(
        activeProvider: ProviderConfig?,
        providerStore: ProviderStore?
    ): String? {
        val providerKey = resolveGeminiProvider(activeProvider, providerStore)?.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (!providerKey.isNullOrBlank()) return providerKey

        // Fallback to environment variable if present
        return runCatching { System.getenv("GEMINI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
    }
}

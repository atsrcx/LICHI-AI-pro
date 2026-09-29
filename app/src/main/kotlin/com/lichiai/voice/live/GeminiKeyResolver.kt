package com.lichiai.voice.live

import com.lichiai.data.ProviderConfig
import com.lichiai.data.ProviderStore

/**
 * Resolves the Google Gemini API key from existing providers without creating a second key system.
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
        return resolveGeminiProvider(activeProvider, providerStore)?.apiKey?.trim()?.takeIf { it.isNotEmpty() }
    }
}

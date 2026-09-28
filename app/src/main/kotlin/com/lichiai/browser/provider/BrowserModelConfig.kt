package com.lichiai.browser.provider

import kotlinx.serialization.Serializable

@Serializable
data class BrowserModelConfig(
    val modelId: String,
    val displayName: String = modelId,
    val contextWindow: Int = 8192,
    val temperature: Float = 0.2f,
    val maxOutputTokens: Int = 1000
)

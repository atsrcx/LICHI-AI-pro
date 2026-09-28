package com.lichiai.intent.registry

import android.content.Context
import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.RiskLevel

data class CapabilityMetadata(
    val capability: LichiCapability,
    val description: String,
    val supportedActions: List<String>,
    val riskLevel: RiskLevel,
    val requiresPermissions: List<String> = emptyList(),
    val isAvailable: Boolean = true
)

/**
 * Discovers and provides metadata for all peer Lichi capabilities.
 */
class CapabilityRegistry(
    private val context: Context? = null,
    private val isAutonomousAgentEnabled: () -> Boolean = { true },
    private val isWebSearchEnabled: () -> Boolean = { true }
) {

    fun getAllCapabilities(): List<CapabilityMetadata> {
        return listOf(
            CapabilityMetadata(
                capability = LichiCapability.BROWSER,
                description = "Visible full-featured Chromium browser with isolated Browser Agent",
                supportedActions = listOf("navigate", "search", "clickCandidate", "scroll", "findOnPage", "tabs"),
                riskLevel = RiskLevel.LOW,
                isAvailable = true
            ),
            CapabilityMetadata(
                capability = LichiCapability.WEB_SEARCH,
                description = "Real-time Web Intelligence API with multi-query fact verification",
                supportedActions = listOf("search", "imageSearch", "newsSearch", "factCheck"),
                riskLevel = RiskLevel.LOW,
                isAvailable = isWebSearchEnabled()
            ),
            CapabilityMetadata(
                capability = LichiCapability.ANDROID_AGENT,
                description = "Autonomous Device Agent V2 for navigating third-party apps and UI automation",
                supportedActions = listOf("execute_phone_task", "tap", "type", "scroll", "inspect_screen"),
                riskLevel = RiskLevel.HIGH,
                isAvailable = isAutonomousAgentEnabled()
            ),
            CapabilityMetadata(
                capability = LichiCapability.CALLS,
                description = "Universal Call Engine for direct telephone calls and SIM selection",
                supportedActions = listOf("call_contact", "call_number", "answer", "decline"),
                riskLevel = RiskLevel.HIGH,
                requiresPermissions = listOf(android.Manifest.permission.CALL_PHONE),
                isAvailable = true
            ),
            CapabilityMetadata(
                capability = LichiCapability.MEDIA_YOUTUBE,
                description = "Media and YouTube playback and search",
                supportedActions = listOf("play", "search", "open"),
                riskLevel = RiskLevel.LOW,
                isAvailable = true
            ),
            CapabilityMetadata(
                capability = LichiCapability.DEVICE_CONTROL,
                description = "Device settings adjustment (volume, brightness, flashlight)",
                supportedActions = listOf("set_volume", "set_brightness", "toggle_torch"),
                riskLevel = RiskLevel.LOW,
                isAvailable = true
            ),
            CapabilityMetadata(
                capability = LichiCapability.SKILL_MANAGEMENT,
                description = "Manage, enable, and configure agent skills",
                supportedActions = listOf("install_skill", "list_skills", "enable_skill", "disable_skill"),
                riskLevel = RiskLevel.MEDIUM,
                isAvailable = true
            ),
            CapabilityMetadata(
                capability = LichiCapability.CHAT,
                description = "General conversational AI and direct question answering",
                supportedActions = listOf("converse", "explain", "summarize"),
                riskLevel = RiskLevel.LOW,
                isAvailable = true
            )
        )
    }

    fun getMetadata(capability: LichiCapability): CapabilityMetadata? {
        return getAllCapabilities().firstOrNull { it.capability == capability }
    }
}

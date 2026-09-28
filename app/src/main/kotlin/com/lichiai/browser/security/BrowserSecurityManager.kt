package com.lichiai.browser.security

import android.net.Uri

enum class ActionRiskLevel {
    SAFE_AUTOMATIC,
    POTENTIALLY_SENSITIVE,
    FORBIDDEN
}

data class SecurityValidationResult(
    val allowed: Boolean,
    val riskLevel: ActionRiskLevel,
    val requiresUserConfirmation: Boolean = false,
    val reason: String = ""
)

/**
 * Validates all Browser Agent requests before execution.
 * Enforces zero access to Android OS shell, device controls, or unauthorized files.
 */
object BrowserSecurityManager {

    private val FORBIDDEN_SCHEMES = setOf("file", "content", "javascript", "intent")
    private val ALLOWED_SCHEMES = setOf("http", "https", "about")

    fun validateNavigationUrl(url: String): SecurityValidationResult {
        if (url.isBlank()) {
            return SecurityValidationResult(false, ActionRiskLevel.FORBIDDEN, reason = "URL cannot be empty")
        }

        if (url == "about:blank") {
            return SecurityValidationResult(true, ActionRiskLevel.SAFE_AUTOMATIC)
        }

        val scheme = when {
            url.startsWith("about:") -> "about"
            url.contains("://") -> url.substringBefore("://").lowercase(java.util.Locale.ROOT).trim()
            else -> ""
        }
        if (scheme in FORBIDDEN_SCHEMES) {
            return SecurityValidationResult(
                allowed = false,
                riskLevel = ActionRiskLevel.FORBIDDEN,
                reason = "Access to scheme '$scheme' is forbidden for security."
            )
        }

        if (scheme !in ALLOWED_SCHEMES) {
            return SecurityValidationResult(
                allowed = false,
                riskLevel = ActionRiskLevel.FORBIDDEN,
                reason = "Only HTTPS and HTTP schemes are permitted."
            )
        }

        return SecurityValidationResult(true, ActionRiskLevel.SAFE_AUTOMATIC)
    }

    fun validateAction(actionName: String, params: Map<String, String>): SecurityValidationResult {
        val lower = actionName.lowercase()
        return when (lower) {
            "navigate" -> {
                val url = params["url"] ?: ""
                validateNavigationUrl(url)
            }
            "search", "scroll", "back", "forward", "reload", "opentab", "closetab", "switchtab",
            "extract", "findtext", "getpagecontext", "stoptask" -> {
                SecurityValidationResult(true, ActionRiskLevel.SAFE_AUTOMATIC)
            }
            "click", "clickcandidate" -> {
                // Check if target seems like a payment / submit financial action
                val desc = (params["semanticDescription"] ?: params["text"] ?: "").lowercase()
                if (desc.contains("buy now") || desc.contains("pay") || desc.contains("checkout") || desc.contains("place order")) {
                    SecurityValidationResult(
                        allowed = true,
                        riskLevel = ActionRiskLevel.POTENTIALLY_SENSITIVE,
                        requiresUserConfirmation = true,
                        reason = "Sensitive purchase action requires explicit user confirmation."
                    )
                } else {
                    SecurityValidationResult(true, ActionRiskLevel.SAFE_AUTOMATIC)
                }
            }
            "type" -> {
                val selector = (params["selector"] ?: "").lowercase()
                val text = (params["text"] ?: "").lowercase()
                if (selector.contains("password") || selector.contains("pass") || selector.contains("pin") || selector.contains("cvv")) {
                    SecurityValidationResult(
                        allowed = false,
                        riskLevel = ActionRiskLevel.FORBIDDEN,
                        reason = "Entering passwords, PINs, or financial secrets via agent is forbidden."
                    )
                } else {
                    SecurityValidationResult(true, ActionRiskLevel.SAFE_AUTOMATIC)
                }
            }
            "download" -> {
                SecurityValidationResult(
                    allowed = true,
                    riskLevel = ActionRiskLevel.POTENTIALLY_SENSITIVE,
                    requiresUserConfirmation = false, // downloads trigger Android notification and confirm prompt
                    reason = "Standard web download."
                )
            }
            else -> {
                // Unknown action
                SecurityValidationResult(
                    allowed = false,
                    riskLevel = ActionRiskLevel.FORBIDDEN,
                    reason = "Action '$actionName' is not recognized by Browser Capability Policy."
                )
            }
        }
    }
}

package com.lichiai.browser.runtime

import com.lichiai.browser.perception.PagePerceptionSnapshot
import com.lichiai.browser.perception.SemanticElement
import java.util.Locale

enum class TargetConfidence {
    HIGH,
    MEDIUM,
    LOW
}

sealed class TargetResolutionResult {
    data class Resolved(
        val element: SemanticElement,
        val confidence: TargetConfidence,
        val resolutionTier: String,
        val explanation: String,
        val generationId: String = element.generationId
    ) : TargetResolutionResult()

    data class StaleTarget(
        val targetQuery: String,
        val currentGenerationId: String,
        val reason: String
    ) : TargetResolutionResult()

    data class NotFound(
        val targetQuery: String,
        val reason: String
    ) : TargetResolutionResult()

    data class Ambiguous(
        val targetQuery: String,
        val candidateMatches: List<SemanticElement>,
        val reason: String
    ) : TargetResolutionResult()
}

/**
 * Target Grounding Engine for Human-Like Browser Interaction.
 *
 * Implements strict resolution priority:
 * 1. Semantic ID
 * 2. Accessibility Label / aria-label
 * 3. Exact visible text
 * 4. Role + Text (e.g. button "Download", link "Pricing")
 * 5. Href / target domain
 * 6. CSS / DOM tag / placeholder
 * 7. Structural relationship
 * 8. Visual bounds
 * 9. Coordinate / index fallback
 *
 * Enforces generation validity to prevent executing on stale DOM elements.
 */
class BrowserTargetResolver {

    fun resolveTarget(
        targetQuery: String,
        snapshot: PagePerceptionSnapshot,
        expectedGenerationId: String? = null
    ): TargetResolutionResult {
        val trimmed = targetQuery.trim()
        if (trimmed.isBlank()) {
            return TargetResolutionResult.NotFound(trimmed, "Target query was empty.")
        }

        // Generation Check: If caller expects a specific generation and it doesn't match current snapshot
        if (expectedGenerationId != null && expectedGenerationId != snapshot.generationId) {
            return TargetResolutionResult.StaleTarget(
                targetQuery = trimmed,
                currentGenerationId = snapshot.generationId,
                reason = "Target was identified in generation '$expectedGenerationId', but current page perception is '${snapshot.generationId}'."
            )
        }

        val lower = trimmed.lowercase(Locale.ROOT)
        val elements = snapshot.semanticElements

        // 1. Semantic ID Match (Highest Priority)
        val bySemanticId = elements.firstOrNull { it.semanticId.equals(trimmed, ignoreCase = true) }
        if (bySemanticId != null) {
            return TargetResolutionResult.Resolved(
                element = bySemanticId,
                confidence = TargetConfidence.HIGH,
                resolutionTier = "1_SEMANTIC_ID",
                explanation = "Matched semantic ID '${bySemanticId.semanticId}'."
            )
        }

        // 2. Accessibility Label / Placeholder Match
        val byAriaOrPlaceholder = elements.firstOrNull {
            it.placeholder.equals(trimmed, ignoreCase = true) ||
            it.labelOrText.equals(trimmed, ignoreCase = true)
        }
        if (byAriaOrPlaceholder != null) {
            return TargetResolutionResult.Resolved(
                element = byAriaOrPlaceholder,
                confidence = TargetConfidence.HIGH,
                resolutionTier = "2_ARIA_OR_PLACEHOLDER",
                explanation = "Matched exact label or placeholder."
            )
        }

        // 3. Exact Visible Text Match
        val byExactText = elements.firstOrNull {
            it.labelOrText.trim().equals(trimmed, ignoreCase = true)
        }
        if (byExactText != null) {
            return TargetResolutionResult.Resolved(
                element = byExactText,
                confidence = TargetConfidence.HIGH,
                resolutionTier = "3_EXACT_TEXT",
                explanation = "Matched exact element text."
            )
        }

        // 4. Role + Text Match (e.g. "button submit", "search input")
        val byRoleAndText = elements.firstOrNull { el ->
            val elText = el.labelOrText.lowercase(Locale.ROOT)
            val elTag = el.tag.lowercase(Locale.ROOT)
            val elType = el.type.lowercase(Locale.ROOT)
            (elTag.contains(lower) || elType.contains(lower) || el.semanticId.contains(lower)) &&
                    (elText.contains(lower) || lower.contains(elText.take(15)))
        }
        if (byRoleAndText != null) {
            return TargetResolutionResult.Resolved(
                element = byRoleAndText,
                confidence = TargetConfidence.MEDIUM,
                resolutionTier = "4_ROLE_AND_TEXT",
                explanation = "Matched element role and text substring."
            )
        }

        // 5. Href / Domain Match
        val byHref = elements.firstOrNull {
            it.href.isNotBlank() && it.href.contains(lower)
        }
        if (byHref != null) {
            return TargetResolutionResult.Resolved(
                element = byHref,
                confidence = TargetConfidence.MEDIUM,
                resolutionTier = "5_HREF_DOMAIN",
                explanation = "Matched target link href/domain: ${byHref.href}."
            )
        }

        // 6. Substring Text / Fuzzy Match
        val bySubstring = elements.firstOrNull {
            it.labelOrText.lowercase(Locale.ROOT).contains(lower) ||
            it.placeholder.lowercase(Locale.ROOT).contains(lower)
        }
        if (bySubstring != null) {
            return TargetResolutionResult.Resolved(
                element = bySubstring,
                confidence = TargetConfidence.MEDIUM,
                resolutionTier = "6_SUBSTRING_TEXT",
                explanation = "Matched partial substring in text: '${bySubstring.labelOrText}'."
            )
        }

        // 7. Index / Number Fallback (e.g. "#1", "2", "3")
        val indexMatch = Regex("#?(\\d+)").matchEntire(trimmed)
        if (indexMatch != null) {
            val targetIdx = indexMatch.groupValues[1].toIntOrNull()
            if (targetIdx != null) {
                val byIndex = elements.firstOrNull { it.originalIndex == targetIdx }
                    ?: elements.getOrNull(targetIdx - 1)
                if (byIndex != null) {
                    return TargetResolutionResult.Resolved(
                        element = byIndex,
                        confidence = TargetConfidence.MEDIUM,
                        resolutionTier = "7_INDEX_FALLBACK",
                        explanation = "Resolved element by sequential index #$targetIdx."
                    )
                }
            }
        }

        return TargetResolutionResult.NotFound(
            targetQuery = trimmed,
            reason = "No interactive element matching '$trimmed' in generation '${snapshot.generationId}' (${elements.size} available elements)."
        )
    }
}

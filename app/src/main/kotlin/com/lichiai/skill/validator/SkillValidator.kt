package com.lichiai.skill.validator

import com.lichiai.skill.model.Skill
import java.util.Locale

data class SkillValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val sanitizedMarkdown: String = ""
)

/**
 * Security and structural validator for Lichi Skill System V1.
 *
 * Enforces:
 * 1. Safe non-executable markdown instructions.
 * 2. Absolute defense against Prompt Injection and System Prompt override attempts.
 * 3. Preservation of core ActionVerificationEngine and Agent V2 safety rules.
 */
object SkillValidator {

    private val FORBIDDEN_EXECUTABLE_PATTERNS = listOf(
        Regex("""<script[\s\S]*?>[\s\S]*?<\/script>""", RegexOption.IGNORE_CASE),
        Regex("""\b(eval|exec|system|getRuntime|ProcessBuilder)\s*\(""", RegexOption.IGNORE_CASE),
        Regex("""\b(chmod|chown|rm\s+-rf|dd\s+if=)\b""", RegexOption.IGNORE_CASE),
        Regex("""\.(dex|apk|so|jar)\b""", RegexOption.IGNORE_CASE)
    )

    private val FORBIDDEN_PROMPT_INJECTION_PATTERNS = listOf(
        "ignore all previous instructions",
        "ignore previous rules",
        "forget all rules",
        "disregard safety",
        "bypass security",
        "bypass verification",
        "skip verification",
        "never verify",
        "always report success",
        "always claim done",
        "you are no longer an android agent",
        "override system prompt",
        "disable accessibility safety",
        "jailbreak",
        "dan mode"
    )

    fun validate(markdown: String, skillId: String? = null): SkillValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val trimmed = markdown.trim()
        if (trimmed.isBlank()) {
            return SkillValidationResult(
                isValid = false,
                errors = listOf("Skill content cannot be empty.")
            )
        }

        if (trimmed.length < 20) {
            errors.add("Skill content is too short to provide actionable agent instructions.")
        }

        // 1. Executable code prevention
        for (pattern in FORBIDDEN_EXECUTABLE_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                errors.add("Executable code or scripts are strictly prohibited in Skill Markdown.")
                break
            }
        }

        // 2. Prompt injection & safety evasion prevention
        val lower = trimmed.lowercase(Locale.getDefault())
        for (forbidden in FORBIDDEN_PROMPT_INJECTION_PATTERNS) {
            if (lower.contains(forbidden)) {
                errors.add("Safety violation: Skill attempts to override core agent safety/verification rules ('$forbidden').")
                break
            }
        }

        // 3. ID format validation if provided
        if (skillId != null) {
            if (!Regex("""^[a-zA-Z0-9_\-]+$""").matches(skillId)) {
                errors.add("Skill ID '$skillId' contains invalid characters. Use letters, numbers, hyphens, and underscores only.")
            }
        }

        // 4. Structure warnings
        if (!lower.contains("# ")) {
            warnings.add("Missing title heading (# Skill Name).")
        }
        if (!lower.contains("workflow") && !lower.contains("steps")) {
            warnings.add("No explicit ## Workflow section found. Adding step-by-step guidance improves agent accuracy.")
        }

        // 5. Sanitization
        var sanitized = trimmed
        for (forbidden in FORBIDDEN_PROMPT_INJECTION_PATTERNS) {
            sanitized = sanitized.replace(forbidden, "[filtered]", ignoreCase = true)
        }

        return SkillValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            sanitizedMarkdown = sanitized
        )
    }

    fun validateSkill(skill: Skill): SkillValidationResult {
        return validate(skill.markdownContent, skill.id)
    }
}

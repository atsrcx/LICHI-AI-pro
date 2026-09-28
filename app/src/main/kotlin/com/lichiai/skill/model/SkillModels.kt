package com.lichiai.skill.model

import kotlinx.serialization.Serializable

/**
 * Origin of the skill.
 */
@Serializable
enum class SkillSource {
    SYSTEM,          // Pre-installed core application skill (protected from deletion)
    USER,            // Created or pasted by user
    IMPORTED,        // Imported from external .md file
    AGENT_GENERATED  // Generated autonomously by Agent upon user request
}

/**
 * Declared risk level of the skill.
 */
@Serializable
enum class SkillRiskLevel {
    LOW,       // Read-only, app opening, viewing
    MEDIUM,    // Form typing, non-destructive navigation
    HIGH,      // Messaging, sending content, posting
    CRITICAL   // Deletion, financial, account security
}

/**
 * Application-level policy permissions declared by a Skill.
 * Note: These do NOT grant Android OS permissions; they define the boundary of allowed agent actions.
 */
@Serializable
enum class SkillPermission {
    SCREEN_READING,
    OPEN_APP,
    TYPE_TEXT,
    SEND_MESSAGE,
    CALLS,
    DELETE_CONTENT
}

/**
 * Historical snapshot of a skill for audit and rollback.
 */
@Serializable
data class SkillVersion(
    val version: String,
    val markdownContent: String,
    val timestamp: Long,
    val changeReason: String
)

/**
 * Core domain model for Lichi Skill System V1.
 */
@Serializable
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "1.0",
    val source: SkillSource = SkillSource.USER,
    val enabled: Boolean = true,
    val riskLevel: SkillRiskLevel = SkillRiskLevel.MEDIUM,
    val permissions: List<SkillPermission> = listOf(SkillPermission.SCREEN_READING, SkillPermission.OPEN_APP),
    val markdownContent: String,
    val purpose: String = "",
    val whenToUse: String = "",
    val whenNotToUse: String = "",
    val workflow: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
    val safetyNotes: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val versionHistory: List<SkillVersion> = emptyList(),
    val userEditable: Boolean = true
) {
    /**
     * Formats the skill instructions into task-scoped context for Agent V2.
     */
    fun toAgentContextString(): String {
        return buildString {
            appendLine("### Skill: $name (v$version)")
            if (purpose.isNotBlank()) appendLine("- Purpose: $purpose")
            if (whenToUse.isNotBlank()) appendLine("- When to apply: $whenToUse")
            if (whenNotToUse.isNotBlank()) appendLine("- When NOT to apply: $whenNotToUse")
            if (workflow.isNotEmpty()) {
                appendLine("- Recommended Workflow:")
                workflow.forEachIndexed { i, step -> appendLine("  ${i + 1}. $step") }
            }
            if (rules.isNotEmpty()) {
                appendLine("- Skill Rules:")
                rules.forEach { r -> appendLine("  * $r") }
            }
            if (safetyNotes.isNotEmpty()) {
                appendLine("- Safety & Verification:")
                safetyNotes.forEach { s -> appendLine("  ! $s") }
            }
            appendLine("- Declared Risk: $riskLevel")
        }
    }
}

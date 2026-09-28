package com.lichiai.skill.parser

import com.lichiai.skill.model.Skill
import com.lichiai.skill.model.SkillPermission
import com.lichiai.skill.model.SkillRiskLevel
import com.lichiai.skill.model.SkillSource
import java.util.Locale

/**
 * Human-readable Markdown parser & serializer for Lichi Skill System V1.
 */
object SkillParser {

    /**
     * Parses Markdown text (SKILL.md) into a structured [Skill] object.
     */
    fun parse(
        markdown: String,
        defaultId: String? = null,
        source: SkillSource = SkillSource.USER
    ): Skill {
        val lines = markdown.lines()
        var currentSection = ""
        val sectionContent = mutableMapOf<String, MutableList<String>>()

        var skillName = ""
        var inFrontmatter = false
        val frontmatterMap = mutableMapOf<String, String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed == "---") {
                inFrontmatter = !inFrontmatter
                continue
            }
            if (inFrontmatter) {
                val parts = trimmed.split(":", limit = 2)
                if (parts.size == 2) {
                    frontmatterMap[parts[0].trim().lowercase(Locale.getDefault())] = parts[1].trim()
                }
                continue
            }

            if (trimmed.startsWith("# ")) {
                if (skillName.isBlank()) {
                    skillName = trimmed.removePrefix("# ").trim()
                }
                currentSection = "title"
                continue
            }

            if (trimmed.startsWith("## ")) {
                currentSection = trimmed.removePrefix("## ").trim().lowercase(Locale.getDefault())
                sectionContent.getOrPut(currentSection) { mutableListOf() }
                continue
            }

            if (currentSection.isNotBlank() && trimmed.isNotBlank()) {
                sectionContent.getOrPut(currentSection) { mutableListOf() }.add(trimmed)
            }
        }

        // Extract metadata or fallback to parsed headings
        val parsedName = frontmatterMap["name"]
            ?.takeIf { it.isNotBlank() }
            ?: skillName.ifBlank { "Custom Skill" }

        val id = frontmatterMap["id"]?.takeIf { it.isNotBlank() }
            ?: defaultId
            ?: generateSlug(parsedName)

        val description = frontmatterMap["description"]
            ?: sectionContent["purpose"]?.firstOrNull()
            ?: sectionContent["when to use"]?.firstOrNull()
            ?: "Custom instruction package for $parsedName"

        val version = frontmatterMap["version"] ?: "1.0"

        val riskLevel = when (frontmatterMap["risk"]?.uppercase(Locale.getDefault())) {
            "CRITICAL" -> SkillRiskLevel.CRITICAL
            "HIGH" -> SkillRiskLevel.HIGH
            "LOW" -> SkillRiskLevel.LOW
            else -> SkillRiskLevel.MEDIUM
        }

        val purpose = sectionContent["purpose"]?.joinToString(" ") ?: ""
        val whenToUse = sectionContent["when to use"]?.joinToString(" ")
            ?: sectionContent["triggers"]?.joinToString(" ") ?: ""
        val whenNotToUse = sectionContent["when not to use"]?.joinToString(" ") ?: ""

        val rawWorkflow = sectionContent["workflow"] ?: sectionContent["steps"] ?: emptyList()
        val workflow = rawWorkflow.map { it.replace(Regex("""^\d+\.\s*"""), "").removePrefix("- ").trim() }
            .filter { it.isNotBlank() }

        val rawRules = sectionContent["rules"] ?: sectionContent["constraints"] ?: emptyList()
        val rules = rawRules.map { it.removePrefix("* ").removePrefix("- ").trim() }
            .filter { it.isNotBlank() }

        val rawSafety = sectionContent["safety"] ?: sectionContent["verification"] ?: emptyList()
        val safetyNotes = rawSafety.map { it.removePrefix("! ").removePrefix("- ").trim() }
            .filter { it.isNotBlank() }

        val permissions = mutableListOf(SkillPermission.SCREEN_READING, SkillPermission.OPEN_APP)
        if (markdown.contains("message", ignoreCase = true) || markdown.contains("send", ignoreCase = true)) {
            permissions.add(SkillPermission.TYPE_TEXT)
            permissions.add(SkillPermission.SEND_MESSAGE)
        }
        if (markdown.contains("delete", ignoreCase = true) || markdown.contains("remove", ignoreCase = true)) {
            permissions.add(SkillPermission.DELETE_CONTENT)
        }

        return Skill(
            id = id,
            name = parsedName,
            description = description,
            version = version,
            source = source,
            enabled = true,
            riskLevel = riskLevel,
            permissions = permissions.distinct(),
            markdownContent = markdown.trim(),
            purpose = purpose,
            whenToUse = whenToUse,
            whenNotToUse = whenNotToUse,
            workflow = workflow,
            rules = rules,
            safetyNotes = safetyNotes,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            versionHistory = emptyList(),
            userEditable = source != SkillSource.SYSTEM
        )
    }

    /**
     * Serializes a [Skill] back to a pristine, clean Markdown format suitable for file export.
     */
    fun toMarkdown(skill: Skill): String {
        return buildString {
            appendLine("---")
            appendLine("id: ${skill.id}")
            appendLine("name: ${skill.name}")
            appendLine("version: ${skill.version}")
            appendLine("risk: ${skill.riskLevel}")
            appendLine("source: ${skill.source}")
            appendLine("---")
            appendLine()
            appendLine("# ${skill.name}")
            appendLine()
            if (skill.purpose.isNotBlank()) {
                appendLine("## Purpose")
                appendLine(skill.purpose)
                appendLine()
            }
            if (skill.whenToUse.isNotBlank()) {
                appendLine("## When to use")
                appendLine(skill.whenToUse)
                appendLine()
            }
            if (skill.whenNotToUse.isNotBlank()) {
                appendLine("## When NOT to use")
                appendLine(skill.whenNotToUse)
                appendLine()
            }
            if (skill.workflow.isNotEmpty()) {
                appendLine("## Workflow")
                skill.workflow.forEachIndexed { idx, step ->
                    appendLine("${idx + 1}. $step")
                }
                appendLine()
            }
            if (skill.rules.isNotEmpty()) {
                appendLine("## Rules")
                skill.rules.forEach { rule ->
                    appendLine("- $rule")
                }
                appendLine()
            }
            if (skill.safetyNotes.isNotEmpty()) {
                appendLine("## Safety")
                skill.safetyNotes.forEach { note ->
                    appendLine("- $note")
                }
                appendLine()
            }
        }.trim()
    }

    private fun generateSlug(name: String): String {
        return name.lowercase(Locale.getDefault())
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
            .ifBlank { "skill-${System.currentTimeMillis()}" }
    }
}

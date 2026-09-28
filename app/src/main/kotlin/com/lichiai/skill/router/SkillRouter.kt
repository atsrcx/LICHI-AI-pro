package com.lichiai.skill.router

import android.util.Log
import com.lichiai.skill.model.Skill
import java.util.Locale

sealed class SkillManagementRequest {
    object ListSkills : SkillManagementRequest()
    data class EnableSkill(val targetName: String) : SkillManagementRequest()
    data class DisableSkill(val targetName: String) : SkillManagementRequest()
    data class DeleteSkill(val targetName: String) : SkillManagementRequest()
    data class ExportSkill(val targetName: String) : SkillManagementRequest()
    data class ProposeCreateSkill(val topicOrGoal: String) : SkillManagementRequest()
}

/**
 * Intelligent Router for Lichi Skill System V1.
 *
 * Responsibilities:
 * 1. Strictly intercepts natural language Skill Management commands ("Disable Instagram skill", "Show skills")
 *    preventing accidental execution of device tasks.
 * 2. Matches active, enabled skills for Autonomous Agent V2 execution.
 * 3. Supports multi-skill selection for compound cross-app tasks.
 * 4. Gracefully produces empty matching lists when no skills match, allowing Agent V2 fallback.
 */
object SkillRouter {

    private const val TAG = "SkillRouter"

    /**
     * Checks if [text] is a natural language Skill Management instruction.
     */
    fun detectManagementRequest(text: String): SkillManagementRequest? {
        val lower = text.trim().lowercase(Locale.getDefault())

        // 1. List / show skills
        if (lower == "skills" || lower == "show skills" || lower == "show my skills" ||
            lower == "list skills" || lower == "my skills" || lower.contains("skills dikhao") ||
            lower.contains("skills list karo") || lower.contains("mere skills")
        ) {
            return SkillManagementRequest.ListSkills
        }

        // 2. Disable skill
        val disableMatch = Regex("""\b(disable|turn off|band karo|band kar do)\s+(my\s+)?([a-z0-9_\-\s]+?)\s+skill\b""")
            .find(lower)
        if (disableMatch != null) {
            val target = disableMatch.groupValues[3].trim()
            return SkillManagementRequest.DisableSkill(target)
        }

        // 3. Enable skill
        val enableMatch = Regex("""\b(enable|turn on|chalu karo|chalu kar do|on karo|on kar do)\s+(my\s+)?([a-z0-9_\-\s]+?)\s+skill\b""")
            .find(lower)
        if (enableMatch != null) {
            val target = enableMatch.groupValues[3].trim()
            return SkillManagementRequest.EnableSkill(target)
        }

        // 4. Delete skill
        val deleteMatch = Regex("""\b(delete|remove|hatao|hata do)\s+(my\s+)?([a-z0-9_\-\s]+?)\s+skill\b""")
            .find(lower)
        if (deleteMatch != null) {
            val target = deleteMatch.groupValues[3].trim()
            return SkillManagementRequest.DeleteSkill(target)
        }

        // 5. Export skill
        val exportMatch = Regex("""\b(export|download|share)\s+(my\s+)?([a-z0-9_\-\s]+?)\s+skill\b""")
            .find(lower)
        if (exportMatch != null) {
            val target = exportMatch.groupValues[3].trim()
            return SkillManagementRequest.ExportSkill(target)
        }

        // 6. Propose create skill
        val createMatch = Regex("""\b(create|make|build|banao|ek naya)\s+(a\s+)?skill\s+(for|to|ke liye)\s+(.+)""")
            .find(lower)
        if (createMatch != null) {
            val topic = createMatch.groupValues[4].trim()
            return SkillManagementRequest.ProposeCreateSkill(topic)
        }

        return null
    }

    /**
     * Matches relevant active skills for [userGoal].
     *
     * Respects:
     * - Explicit user inclusions ("Use Instagram skill")
     * - Explicit user exclusions ("Don't use Instagram skill")
     * - Multi-domain queries (e.g. YouTube + WhatsApp)
     */
    fun matchSkills(userGoal: String, availableSkills: List<Skill>): List<Skill> {
        val lower = userGoal.lowercase(Locale.getDefault())
        val enabledSkills = availableSkills.filter { it.enabled }
        if (enabledSkills.isEmpty()) {
            Log.d(TAG, "No enabled skills available to match.")
            return emptyList()
        }

        // Check explicit exclusions first
        val excludedSkillIds = mutableSetOf<String>()
        for (skill in enabledSkills) {
            val skillNameLower = skill.name.lowercase(Locale.getDefault())
            if (lower.contains("don't use $skillNameLower") ||
                lower.contains("do not use $skillNameLower") ||
                lower.contains("without $skillNameLower") ||
                lower.contains("$skillNameLower use mat karo")
            ) {
                excludedSkillIds.add(skill.id)
            }
        }

        val candidates = enabledSkills.filter { it.id !in excludedSkillIds }
        val matched = mutableListOf<Skill>()

        for (skill in candidates) {
            val nameLower = skill.name.lowercase(Locale.getDefault())
            val idLower = skill.id.lowercase(Locale.getDefault())

            // 1. Explicit mention
            if (lower.contains("use $nameLower") || lower.contains("with $nameLower") ||
                lower.contains("$nameLower use karo")
            ) {
                matched.add(skill)
                continue
            }

            // 2. Domain / Keyword matching based on skill metadata
            val triggers = mutableListOf<String>()
            triggers.add(nameLower.removeSuffix(" assistant").removeSuffix(" skill"))
            triggers.add(idLower.removeSuffix("-assistant").removeSuffix("-skill"))

            if (skill.whenToUse.isNotBlank()) {
                val words = skill.whenToUse.lowercase(Locale.getDefault())
                    .split(" ", ",", ".")
                    .filter { it.length > 3 }
                triggers.addAll(words)
            }

            val isDomainRelevant = when {
                nameLower.contains("instagram") && (lower.contains("instagram") || lower.contains("insta") || lower.contains("इंस्टाग्राम")) -> true
                nameLower.contains("whatsapp") && (lower.contains("whatsapp") || lower.contains("व्हाट्सएप")) -> true
                nameLower.contains("youtube") && (lower.contains("youtube") || lower.contains("यूट्यूब")) -> true
                nameLower.contains("settings") && (lower.contains("settings") || lower.contains("bluetooth") || lower.contains("wifi") || lower.contains("hotspot")) -> true
                else -> triggers.any { it.isNotBlank() && lower.contains(it) }
            }

            if (isDomainRelevant) {
                matched.add(skill)
            }
        }

        val result = matched.distinctBy { it.id }
        Log.i(TAG, "SkillRouter matched ${result.size} skills for goal '$userGoal': ${result.map { it.name }}")
        return result
    }
}

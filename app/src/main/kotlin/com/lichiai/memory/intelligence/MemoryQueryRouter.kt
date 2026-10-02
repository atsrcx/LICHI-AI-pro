package com.lichiai.memory.intelligence

import com.lichiai.memory.model.TemporalIntent
import java.util.Locale

enum class MemoryQueryCategory {
    DIRECT_LOOKUP,
    PROFILE_RECALL,
    HISTORICAL_RECALL,
    SEMANTIC_RECALL,
    LEXICAL_RECALL,
    ENTITY_RECALL,
    RAW_LEDGER_RECALL
}

data class RoutedQueryDecision(
    val primaryCategory: MemoryQueryCategory,
    val secondaryCategories: List<MemoryQueryCategory> = emptyList(),
    val temporalIntent: TemporalIntent = TemporalIntent.CURRENT,
    val directLookupKey: String? = null,
    val isTrivialDirectLookup: Boolean = false,
    val requiresSemanticSearch: Boolean = true,
    val requiresLexicalSearch: Boolean = true,
    val requiresEntitySearch: Boolean = true,
    val requiresLedgerSearch: Boolean = false
)

/**
 * Deterministic Query Router for Memory OS V6.
 * Categorizes incoming memory requests to bypass neural embedding for exact lookups.
 */
object MemoryQueryRouter {

    fun routeQuery(rawQuery: String): RoutedQueryDecision {
        val clean = rawQuery.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) {
            return RoutedQueryDecision(
                primaryCategory = MemoryQueryCategory.PROFILE_RECALL,
                isTrivialDirectLookup = true,
                requiresSemanticSearch = false
            )
        }

        // 1. Detect Temporal Intent
        val isHistorical = clean.contains("pehle") || clean.contains("before") || clean.contains("previously") ||
                clean.contains("earlier") || clean.contains("past") || clean.contains("purana")
        val isAllTime = clean.contains("history") || clean.contains("timeline") || clean.contains("kahan kahan") ||
                clean.contains("saari") || clean.contains("all past")

        val temporalIntent = when {
            isAllTime -> TemporalIntent.ALL
            isHistorical -> TemporalIntent.HISTORICAL
            else -> TemporalIntent.CURRENT
        }

        // 2. Direct Profile Lookup Fast Path
        // Name
        if (clean.contains("mera naam") || clean.contains("my name") || clean == "who am i" ||
            clean.contains("apna naam") || clean == "kya naam hai mera") {
            return RoutedQueryDecision(
                primaryCategory = MemoryQueryCategory.DIRECT_LOOKUP,
                secondaryCategories = listOf(MemoryQueryCategory.PROFILE_RECALL),
                temporalIntent = TemporalIntent.CURRENT,
                directLookupKey = "user_name",
                isTrivialDirectLookup = true,
                requiresSemanticSearch = false,
                requiresLedgerSearch = false
            )
        }

        // Current Residence
        if ((clean.contains("kahan rehta") || clean.contains("where do i live") || clean.contains("meri city") ||
                    clean.contains("mera ghar") || clean.contains("current location")) && !isHistorical) {
            return RoutedQueryDecision(
                primaryCategory = MemoryQueryCategory.DIRECT_LOOKUP,
                secondaryCategories = listOf(MemoryQueryCategory.PROFILE_RECALL),
                temporalIntent = TemporalIntent.CURRENT,
                directLookupKey = "user_residence",
                isTrivialDirectLookup = true,
                requiresSemanticSearch = false,
                requiresLedgerSearch = false
            )
        }

        // Preferred Language (Current vs Historical)
        if (clean.contains("language") || clean.contains("bhasha") || clean.contains("boli")) {
            return RoutedQueryDecision(
                primaryCategory = if (isHistorical) MemoryQueryCategory.HISTORICAL_RECALL else MemoryQueryCategory.DIRECT_LOOKUP,
                secondaryCategories = listOf(MemoryQueryCategory.PROFILE_RECALL),
                temporalIntent = if (isHistorical) TemporalIntent.HISTORICAL else TemporalIntent.CURRENT,
                directLookupKey = "user_pref_language",
                isTrivialDirectLookup = !isHistorical,
                requiresSemanticSearch = isHistorical,
                requiresLedgerSearch = isHistorical
            )
        }

        // UI / Theme Preference
        if (clean.contains("ui") || clean.contains("theme") || clean.contains("dark mode") || clean.contains("light mode")) {
            return RoutedQueryDecision(
                primaryCategory = MemoryQueryCategory.DIRECT_LOOKUP,
                secondaryCategories = listOf(MemoryQueryCategory.PROFILE_RECALL),
                temporalIntent = if (isHistorical) TemporalIntent.HISTORICAL else TemporalIntent.CURRENT,
                directLookupKey = "user_pref_ui_theme",
                isTrivialDirectLookup = !isHistorical,
                requiresSemanticSearch = isHistorical,
                requiresLedgerSearch = false
            )
        }

        // Active Project / Work context
        val isTechnicalDetail = clean.contains("fix") || clean.contains("generation id") || clean.contains("bug") ||
                clean.contains("issue") || clean.contains("error") || clean.contains("version")
        if (!isTechnicalDetail && (
            (clean.contains("project") && (clean.contains("what") || clean.contains("kya") || clean.contains("which") || clean.contains("konsa") || clean.contains("active") || clean.contains("current") || clean.contains("mera") || clean.contains("my") || clean.contains("working on") || clean == "project")) ||
            clean.contains("kaam kar raha") || clean.contains("kaam kar rahe")
        )) {
            return RoutedQueryDecision(
                primaryCategory = if (isHistorical) MemoryQueryCategory.HISTORICAL_RECALL else MemoryQueryCategory.DIRECT_LOOKUP,
                secondaryCategories = listOf(MemoryQueryCategory.PROFILE_RECALL, MemoryQueryCategory.ENTITY_RECALL),
                temporalIntent = if (isHistorical) TemporalIntent.HISTORICAL else TemporalIntent.CURRENT,
                directLookupKey = "active_project",
                isTrivialDirectLookup = false,
                requiresSemanticSearch = true,
                requiresEntitySearch = true,
                requiresLedgerSearch = isHistorical
            )
        }

        // Coding instructions
        if (clean.contains("coding") || clean.contains("file path") || clean.contains("file paths")) {
            return RoutedQueryDecision(
                primaryCategory = MemoryQueryCategory.DIRECT_LOOKUP,
                secondaryCategories = listOf(MemoryQueryCategory.PROFILE_RECALL),
                temporalIntent = TemporalIntent.CURRENT,
                directLookupKey = "user_instruction_coding_file_paths",
                isTrivialDirectLookup = false,
                requiresSemanticSearch = true,
                requiresLedgerSearch = false
            )
        }

        // Tech preference
        if (clean.contains("android") || clean.contains("kotlin") || clean.contains("programming language")) {
            return RoutedQueryDecision(
                primaryCategory = MemoryQueryCategory.DIRECT_LOOKUP,
                secondaryCategories = listOf(MemoryQueryCategory.PROFILE_RECALL),
                temporalIntent = TemporalIntent.CURRENT,
                directLookupKey = "user_pref_tech_android",
                isTrivialDirectLookup = false,
                requiresSemanticSearch = true,
                requiresLedgerSearch = false
            )
        }

        // Browser agent / Project state
        if (clean.contains("browser agent") || clean.contains("verification")) {
            return RoutedQueryDecision(
                primaryCategory = MemoryQueryCategory.DIRECT_LOOKUP,
                secondaryCategories = listOf(MemoryQueryCategory.SEMANTIC_RECALL),
                temporalIntent = TemporalIntent.CURRENT,
                directLookupKey = "project_browser_verification_status",
                isTrivialDirectLookup = false,
                requiresSemanticSearch = true,
                requiresLedgerSearch = true
            )
        }

        // Email / Phone
        if (clean.contains("email") || clean.contains("phone number") || clean.contains("contact number")) {
            val key = if (clean.contains("email")) "user_email" else "user_phone"
            return RoutedQueryDecision(
                primaryCategory = MemoryQueryCategory.DIRECT_LOOKUP,
                secondaryCategories = listOf(MemoryQueryCategory.PROFILE_RECALL),
                temporalIntent = TemporalIntent.CURRENT,
                directLookupKey = key,
                isTrivialDirectLookup = true,
                requiresSemanticSearch = false,
                requiresLedgerSearch = false
            )
        }

        // 3. Historical Recall
        if (isHistorical || isAllTime) {
            val directKey = when {
                clean.contains("rehta") || clean.contains("lived") || clean.contains("city") || clean.contains("residence") -> "user_residence"
                clean.contains("language") || clean.contains("bhasha") -> "user_pref_language"
                clean.contains("project") || clean.contains("kaam") -> "active_project"
                clean.contains("name") || clean.contains("naam") -> "user_name"
                else -> null
            }

            return RoutedQueryDecision(
                primaryCategory = MemoryQueryCategory.HISTORICAL_RECALL,
                secondaryCategories = listOf(MemoryQueryCategory.SEMANTIC_RECALL, MemoryQueryCategory.LEXICAL_RECALL),
                temporalIntent = temporalIntent,
                directLookupKey = directKey,
                isTrivialDirectLookup = false,
                requiresSemanticSearch = true,
                requiresLexicalSearch = true,
                requiresEntitySearch = true,
                requiresLedgerSearch = true
            )
        }

        // 4. Raw Ledger Explicit Recall ("woh conversation jahan...", "kal kya baat hui thi")
        if (clean.contains("conversation") || clean.contains("baat hui thi") || clean.contains("turn") ||
            clean.contains("chat history") || clean.contains("last message") || clean.contains("verbatim")) {
            return RoutedQueryDecision(
                primaryCategory = MemoryQueryCategory.RAW_LEDGER_RECALL,
                secondaryCategories = listOf(MemoryQueryCategory.SEMANTIC_RECALL, MemoryQueryCategory.LEXICAL_RECALL),
                temporalIntent = temporalIntent,
                requiresSemanticSearch = true,
                requiresLexicalSearch = true,
                requiresLedgerSearch = true
            )
        }

        // 5. Technical / Project / Agent Keyword Query (e.g. "generation ID wala fix", "browser target generation")
        val isTechnicalLexical = clean.contains("fix") || clean.contains("id") || clean.contains("browser") ||
                clean.contains("terminal") || clean.contains("tool") || clean.contains("agent") ||
                clean.contains("error") || clean.contains("code") || clean.contains("version")

        if (isTechnicalLexical) {
            return RoutedQueryDecision(
                primaryCategory = MemoryQueryCategory.LEXICAL_RECALL,
                secondaryCategories = listOf(MemoryQueryCategory.SEMANTIC_RECALL, MemoryQueryCategory.ENTITY_RECALL),
                temporalIntent = temporalIntent,
                requiresSemanticSearch = true,
                requiresLexicalSearch = true,
                requiresEntitySearch = true,
                requiresLedgerSearch = true
            )
        }

        // 6. Default General Semantic Recall
        return RoutedQueryDecision(
            primaryCategory = MemoryQueryCategory.SEMANTIC_RECALL,
            secondaryCategories = listOf(MemoryQueryCategory.LEXICAL_RECALL, MemoryQueryCategory.ENTITY_RECALL),
            temporalIntent = temporalIntent,
            requiresSemanticSearch = true,
            requiresLexicalSearch = true,
            requiresEntitySearch = true,
            requiresLedgerSearch = false
        )
    }
}

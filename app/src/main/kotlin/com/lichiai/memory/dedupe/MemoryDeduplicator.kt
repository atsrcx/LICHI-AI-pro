package com.lichiai.memory.dedupe

import java.util.Locale

object MemoryDeduplicator {
    /**
     * Generates a deterministic identity key for a memory slot.
     * Identity is strictly scoped to the user, category, subject, and predicate.
     * Value is deliberately excluded so that updates to the same slot share the same key,
     * while distinct slots with identical values (e.g. shirt color = blue vs favorite color = blue)
     * remain strictly isolated.
     */
    fun generateDedupeKey(
        userId: String,
        category: String,
        subject: String,
        predicate: String
    ): String {
        val cleanUser = userId.trim().lowercase(Locale.ROOT).ifBlank { "default_user" }
        val cleanCat = category.trim().lowercase(Locale.ROOT).ifBlank { "general" }
        val cleanSub = subject.trim().lowercase(Locale.ROOT).ifBlank { "user" }
        val cleanPred = canonicalizePredicate(predicate).trim().lowercase(Locale.ROOT)
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_]".toRegex(), "")
            .ifBlank { "attribute" }

        return "${cleanUser}::${cleanCat}::${cleanSub}::${cleanPred}"
    }

    /**
     * Normalizes predicates to avoid fragmenting identical concepts.
     */
    fun canonicalizePredicate(predicate: String): String {
        val p = predicate.trim().lowercase(Locale.ROOT).replace(" ", "_")
        return when (p) {
            "interests", "hobbies", "hobby", "likes", "preference" -> "interest"
            "favorite_language", "coding_language", "dev_language" -> "programming_language"
            "city", "current_city", "hometown", "lives_in" -> "residence"
            "preferred_name", "user_name" -> "name"
            else -> p
        }
    }
}

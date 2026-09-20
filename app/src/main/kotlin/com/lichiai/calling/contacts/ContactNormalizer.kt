package com.lichiai.calling.contacts

import java.text.Normalizer
import java.util.Locale

object ContactNormalizer {

    private val PUNCTUATION_REGEX = Regex("[^\\p{L}\\p{Nd}\\s]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    private val HONORIFICS = listOf(
        "ji", "bhai", "bhaiya", "bro", "sir", "mr", "mrs", "miss", "dr", "uncle", "aunty", "beta", "didi"
    )

    fun normalize(input: String): String {
        if (input.isBlank()) return ""
        val unaccented = Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        val cleaned = PUNCTUATION_REGEX.replace(unaccented, " ")
        return MULTI_SPACE_REGEX.replace(cleaned, " ").trim().lowercase(Locale.ROOT)
    }

    /**
     * Splits into searchable tokens and generates variations without common honorific suffixes.
     */
    fun extractSearchTokens(input: String): Set<String> {
        val normalized = normalize(input)
        if (normalized.isBlank()) return emptySet()

        val tokens = normalized.split(" ").filter { it.isNotBlank() }.toMutableSet()
        tokens.add(normalized)

        // Without honorifics (e.g. "rahul ji" -> "rahul", "vikas bhaiya" -> "vikas")
        val strippedTokens = tokens.map { token ->
            var t = token
            for (h in HONORIFICS) {
                if (t.endsWith(h) && t.length > h.length + 2) {
                    t = t.substring(0, t.length - h.length).trim()
                }
            }
            t
        }
        tokens.addAll(strippedTokens)

        // Handle Hindi Devanagari transliteration common substitutions
        val transliterated = transliterateHindiDevanagari(normalized)
        if (transliterated.isNotBlank()) {
            tokens.add(transliterated)
        }

        return tokens
    }

    /**
     * Converts common Devanagari Hindi text to Latin script phonetic equivalents for matching.
     */
    fun transliterateHindiDevanagari(input: String): String {
        var str = input
        val hindiToLatin = listOf(
            "राहुल" to "rahul",
            "भैया" to "bhaiya",
            "भाई" to "bhai",
            "मम्मी" to "mummy",
            "पापा" to "papa",
            "माँ" to "maa",
            "पिताजी" to "pitaji",
            "दीदी" to "didi",
            "अमित" to "amit",
            "विकास" to "vikas",
            "रोहित" to "rohit",
            "दोस्त" to "dost",
            "ऑफिस" to "office",
            "घर" to "home"
        )
        for ((hi, en) in hindiToLatin) {
            str = str.replace(hi, en)
        }
        return normalize(str)
    }

    /**
     * Computes Levenshtein distance for fuzzy matching minor STT phonetic variations.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val a = s1.lowercase(Locale.ROOT)
        val b = s2.lowercase(Locale.ROOT)
        val costs = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previousCost = i - 1
            costs[0] = i
            for (j in 1..b.length) {
                val currentCost = costs[j]
                val match = if (a[i - 1] == b[j - 1]) 0 else 1
                costs[j] = minOf(costs[j] + 1, costs[j - 1] + 1, previousCost + match)
                previousCost = currentCost
            }
        }
        return costs[b.length]
    }

    /**
     * Returns true if two words match with high similarity (Levenshtein <= 1 for 4-6 chars, <= 2 for 7+ chars).
     */
    fun isFuzzyMatch(queryWord: String, targetWord: String): Boolean {
        val q = normalize(queryWord)
        val t = normalize(targetWord)
        if (q.length < 3 || t.length < 3) return q == t
        if (q == t) return true
        val distance = levenshteinDistance(q, t)
        return when {
            q.length <= 4 -> distance <= 1 && (q.first() == t.first())
            q.length <= 6 -> distance <= 1
            else -> distance <= 2
        }
    }
}

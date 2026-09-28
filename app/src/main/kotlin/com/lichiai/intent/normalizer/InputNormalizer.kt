package com.lichiai.intent.normalizer

import java.util.Locale

/**
 * Normalizes speech-to-text input, Roman Hindi / Hinglish variations,
 * and casual conversational fillers without altering the underlying user intent.
 */
object InputNormalizer {

    private val FILLER_PREFIXES = listOf(
        "yaar", "zara", "bhai", "bhaiya", "please", "arre", "are", "oye",
        "sun", "suno", "sun na", "ek baar", "ek minute", "can you", "please can you",
        "could you", "just", "hey lichi", "lichi", "hello", "hi", "namaste"
    )

    private val FILLER_WORDS = listOf(
        "yaar", "zara", "bhai", "bhaiya", "please", "na", "to", "bhi"
    )

    /**
     * Normalizes the raw input string:
     * - Trims & collapses redundant whitespace
     * - Removes common STT repeat glitches (e.g. "kholo kholo" -> "kholo")
     * - Standardizes Hinglish phonetics
     */
    fun normalize(input: String): String {
        var text = input.trim().lowercase(Locale.ROOT)
        if (text.isBlank()) return ""

        // 1. Collapse multiple whitespaces & tabs
        text = text.replace(Regex("\\s+"), " ")

        // 2. Remove common STT duplicate word stutter (e.g. "browser browser kholo" -> "browser kholo")
        val words = text.split(" ")
        val deduplicatedWords = mutableListOf<String>()
        var lastWord = ""
        for (w in words) {
            val lower = w.lowercase(Locale.ROOT)
            if (lower != lastWord || lower.length <= 2) {
                deduplicatedWords.add(w)
                lastWord = lower
            }
        }
        text = deduplicatedWords.joinToString(" ")

        // 3. Normalize common Roman Hindi phonetic variations
        text = normalizePhonetics(text)

        return text.trim()
    }

    /**
     * Extracts clean payload by removing conversational filler words from the beginning
     * and end of queries.
     */
    fun stripConversationalFillers(input: String): String {
        var clean = normalize(input)
        val lower = clean.lowercase(Locale.ROOT)

        for (prefix in FILLER_PREFIXES) {
            if (lower.startsWith("$prefix ")) {
                clean = clean.substring(prefix.length).trim()
                break
            }
        }
        return clean
    }

    private fun normalizePhonetics(input: String): String {
        var s = input
        // Normalize "khlo" -> "kholo"
        s = s.replace(Regex("\\bkhlo\\b", RegexOption.IGNORE_CASE), "kholo")
        // Normalize "per" / "pr" -> "par"
        s = s.replace(Regex("\\bper\\b", RegexOption.IGNORE_CASE), "par")
        s = s.replace(Regex("\\bpr\\b", RegexOption.IGNORE_CASE), "par")
        // Normalize "dhoondo" / "dhundo"
        s = s.replace(Regex("\\bdhoondo\\b", RegexOption.IGNORE_CASE), "dhundho")
        s = s.replace(Regex("\\bdhoond\\b", RegexOption.IGNORE_CASE), "dhund")
        // Normalize "dusra" -> "doosra"
        s = s.replace(Regex("\\bdusra\\b", RegexOption.IGNORE_CASE), "doosra")
        // Normalize "tisra" -> "teesra"
        s = s.replace(Regex("\\btisra\\b", RegexOption.IGNORE_CASE), "teesra")
        // Normalize "niche" -> "neeche"
        s = s.replace(Regex("\\bniche\\b", RegexOption.IGNORE_CASE), "neeche")
        // Normalize "oopar" -> "upar"
        s = s.replace(Regex("\\boopar\\b", RegexOption.IGNORE_CASE), "upar")
        return s
    }
}

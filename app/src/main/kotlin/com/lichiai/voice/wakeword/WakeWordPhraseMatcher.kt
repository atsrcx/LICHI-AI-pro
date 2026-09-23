package com.lichiai.voice.wakeword

object WakeWordPhraseMatcher {

    /**
     * Normalizes a string by converting to lowercase, removing punctuation,
     * trimming, and collapsing multi-spaces into single spaces.
     */
    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        return text
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ") // keep letters, digits and whitespace
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Builds a Vosk grammar JSON string for the active phrases.
     * Vosk format: '["phrase one", "phrase two", "[unk]"]'
     */
    fun buildGrammarJson(phrases: List<WakePhraseConfig>): String {
        val activePhrases = phrases
            .filter { it.isEnabled && it.phrase.isNotBlank() }
            .map { normalize(it.phrase) }
            .filter { it.isNotBlank() }
            .distinct()

        val tokens = activePhrases + "[unk]"
        return "[" + tokens.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
    }

    /**
     * Extracts recognized text from Vosk JSON response string.
     * Works for both final result {"text": "..."} and partial result {"partial": "..."}.
     */
    fun extractTextFromJson(jsonString: String?): String {
        if (jsonString.isNullOrBlank()) return ""
        val trimmed = jsonString.trim()
        
        // Fast token extraction without requiring android.os or org.json
        if (trimmed.contains("\"text\"")) {
            val afterKey = trimmed.substringAfter("\"text\"")
            val colonIdx = afterKey.indexOf(':')
            if (colonIdx >= 0) {
                val valuePart = afterKey.substring(colonIdx + 1).trim()
                if (valuePart.startsWith("\"")) {
                    return valuePart.substring(1).substringBefore("\"")
                }
            }
        }
        if (trimmed.contains("\"partial\"")) {
            val afterKey = trimmed.substringAfter("\"partial\"")
            val colonIdx = afterKey.indexOf(':')
            if (colonIdx >= 0) {
                val valuePart = afterKey.substring(colonIdx + 1).trim()
                if (valuePart.startsWith("\"")) {
                    return valuePart.substring(1).substringBefore("\"")
                }
            }
        }
        return ""
    }

    /**
     * Checks if the recognized text strictly matches any of the active configured wake phrases.
     * Returns the matched configured phrase if matched, or null otherwise.
     */
    fun findMatchingPhrase(
        recognizedRawText: String,
        activePhrases: List<WakePhraseConfig>,
        sensitivity: WakeWordSensitivity = WakeWordSensitivity.STRICT
    ): WakePhraseConfig? {
        val normalizedHypothesis = normalize(recognizedRawText)
        if (normalizedHypothesis.isBlank() || normalizedHypothesis == "[unk]") return null

        val enabledList = activePhrases.filter { it.isEnabled && it.phrase.isNotBlank() }

        // 1. Direct exact match
        for (config in enabledList) {
            val normalizedTarget = normalize(config.phrase)
            if (normalizedTarget.isNotBlank() && normalizedHypothesis == normalizedTarget) {
                return config
            }
        }

        // 2. Strict phrase boundary matching
        // Ensures that e.g. "hey lichi" is recognized even if preceded by silence/filler,
        // but strictly prevents substring false positives (e.g. "hey listen" or "i like this").
        for (config in enabledList) {
            val normalizedTarget = normalize(config.phrase)
            if (normalizedTarget.isBlank()) continue

            // Split into tokens for strict word-boundary matching
            val hypTokens = normalizedHypothesis.split(" ").filter { it.isNotBlank() }
            val targetTokens = normalizedTarget.split(" ").filter { it.isNotBlank() }

            if (hypTokens.isEmpty() || targetTokens.isEmpty()) continue

            if (sensitivity == WakeWordSensitivity.STRICT) {
                // In STRICT mode:
                // If single word (e.g. "lichi"): only accept if it's the exact and only word in hypothesis
                if (targetTokens.size == 1) {
                    if (hypTokens.size == 1 && hypTokens[0] == targetTokens[0]) {
                        return config
                    }
                } else {
                    // For multi-word phrase (e.g. "hey lichi"), must exactly match the hypothesis or end the hypothesis
                    if (hypTokens == targetTokens) {
                        return config
                    }
                    if (hypTokens.size == targetTokens.size + 1 && hypTokens.takeLast(targetTokens.size) == targetTokens) {
                        return config
                    }
                }
            } else {
                // In BALANCED mode: allow phrase anywhere as complete contiguous word tokens
                if (containsContiguousTokens(hypTokens, targetTokens)) {
                    // Additional safety check: do not match single-word target inside long sentence
                    if (targetTokens.size == 1 && hypTokens.size > 2) {
                        continue
                    }
                    return config
                }
            }
        }

        return null
    }

    private fun containsContiguousTokens(source: List<String>, target: List<String>): Boolean {
        if (target.isEmpty() || source.size < target.size) return false
        for (i in 0..source.size - target.size) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }
}

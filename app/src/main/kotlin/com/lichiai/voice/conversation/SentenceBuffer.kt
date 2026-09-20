package com.lichiai.voice.conversation

class SentenceBuffer(
    private val onSentenceReady: (String) -> Unit
) {
    private val buffer = java.lang.StringBuilder()

    private val abbreviations = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.",
        "e.g.", "i.e.", "vs.", "etc.", "approx.", "dept.",
        "jan.", "feb.", "mar.", "apr.", "jun.", "jul.", "aug.", "sep.", "oct.", "nov.", "dec."
    )

    fun appendToken(token: String) {
        buffer.append(token)
        processBuffer()
    }

    private fun processBuffer() {
        while (true) {
            val text = buffer.toString()
            val boundaryIndex = findFirstSentenceBoundary(text)
            if (boundaryIndex == -1) break

            val sentence = text.substring(0, boundaryIndex + 1).trim()
            buffer.delete(0, boundaryIndex + 1)

            if (sentence.isNotBlank()) {
                val cleanedSentence = cleanSentenceForTts(sentence)
                if (cleanedSentence.isNotBlank()) {
                    onSentenceReady(cleanedSentence)
                }
            }
        }
    }

    fun flush() {
        val remaining = buffer.toString().trim()
        buffer.setLength(0)
        if (remaining.isNotBlank()) {
            val cleaned = cleanSentenceForTts(remaining)
            if (cleaned.isNotBlank()) {
                onSentenceReady(cleaned)
            }
        }
    }

    fun clear() {
        buffer.setLength(0)
    }

    private fun findFirstSentenceBoundary(text: String): Int {
        val len = text.length
        for (i in 0 until len) {
            val char = text[i]

            // Check newlines
            if (char == '\n' || char == '\r') {
                return i
            }

            // Check standard delimiters: . ! ? 。 ！ ？
            if (char == '.' || char == '!' || char == '?' || char == '。' || char == '！' || char == '？') {
                // Ignore ellipsis (...)
                if (char == '.' && i + 1 < len && text[i + 1] == '.') continue
                if (char == '.' && i > 0 && text[i - 1] == '.') continue

                // Ignore decimals (e.g. 3.14)
                if (char == '.' && i > 0 && i + 1 < len && text[i - 1].isDigit() && text[i + 1].isDigit()) continue

                // Check abbreviations
                if (char == '.') {
                    val wordStart = text.lastIndexOf(' ', i - 1) + 1
                    val word = text.substring(wordStart, i + 1).lowercase()
                    if (abbreviations.contains(word)) continue
                }

                // If followed by space or end of buffer or punctuation
                if (i + 1 == len || text[i + 1].isWhitespace() || text[i + 1] == '"' || text[i + 1] == ')' || text[i + 1] == '”') {
                    // Include any trailing quote/bracket
                    var endIdx = i
                    if (i + 1 < len && (text[i + 1] == '"' || text[i + 1] == '”' || text[i + 1] == '\'')) {
                        endIdx = i + 1
                    }
                    return endIdx
                }
            }
        }
        return -1
    }

    private fun cleanSentenceForTts(raw: String): String {
        return raw
            // Remove markdown code blocks and backticks
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace("`", "")
            // Remove markdown headers #, bold/italic asterisks
            .replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("[*_~]"), "")
            // Remove markdown links [title](url) -> title
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            // Remove excessive whitespace
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

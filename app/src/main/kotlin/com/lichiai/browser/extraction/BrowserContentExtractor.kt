package com.lichiai.browser.extraction

import com.lichiai.browser.context.BrowserPageCandidate
import com.lichiai.browser.context.BrowserTableData
import com.lichiai.browser.perception.PagePerceptionSnapshot
import com.lichiai.browser.security.WebSecuritySanitizer
import java.util.Locale

enum class TemporalValidity {
    CURRENT,
    HISTORICAL,
    ESTIMATED,
    LAUNCH,
    SALE,
    UNKNOWN
}

data class ExtractedPriceItem(
    val rawValue: String,
    val sourceUrl: String,
    val validity: TemporalValidity,
    val contextSnippet: String
)

data class ExtractedStructuredContent(
    val sourceUrl: String,
    val title: String,
    val headings: List<String>,
    val mainTextSnippet: String,
    val prices: List<ExtractedPriceItem>,
    val tables: List<BrowserTableData>,
    val candidateLinks: List<BrowserPageCandidate>,
    val extractionTimestamp: Long = System.currentTimeMillis()
)

/**
 * Robust extraction engine for web content.
 * Enforces Section 15: preserves source URLs and distinguishes CURRENT,
 * HISTORICAL, ESTIMATED, LAUNCH, and SALE validity.
 */
object BrowserContentExtractor {

    fun extractStructured(snapshot: PagePerceptionSnapshot): ExtractedStructuredContent {
        val sanitizedText = WebSecuritySanitizer.sanitizeWebContent(snapshot.visibleTextSnippet)
        val headings = extractHeadings(sanitizedText)
        val classifiedPrices = snapshot.candidatePrices.map { price ->
            classifyPriceValidity(price, sanitizedText, snapshot.url)
        }

        return ExtractedStructuredContent(
            sourceUrl = snapshot.url,
            title = snapshot.title,
            headings = headings,
            mainTextSnippet = sanitizedText.take(1200),
            prices = classifiedPrices,
            tables = snapshot.extractedTables,
            candidateLinks = snapshot.candidateLinks
        )
    }

    private fun extractHeadings(text: String): List<String> {
        val lines = text.split("\n")
        return lines.filter { line ->
            val trimmed = line.trim()
            trimmed.length in 4..70 && !trimmed.endsWith(".") && !trimmed.contains("http")
        }.take(8)
    }

    private fun classifyPriceValidity(
        price: String,
        contextText: String,
        sourceUrl: String
    ): ExtractedPriceItem {
        val lowerText = contextText.lowercase(Locale.ROOT)
        val priceIndex = lowerText.indexOf(price.lowercase(Locale.ROOT))
        val snippet = if (priceIndex >= 0) {
            val start = (priceIndex - 60).coerceAtLeast(0)
            val end = (priceIndex + 60).coerceAtMost(lowerText.length)
            lowerText.substring(start, end)
        } else {
            lowerText.take(120)
        }

        val validity = when {
            snippet.contains("was launched at") || snippet.contains("launch price") || snippet.contains("originally announced") ->
                TemporalValidity.LAUNCH
            snippet.contains("discount") || snippet.contains("sale") || snippet.contains("deal") || snippet.contains("off") ->
                TemporalValidity.SALE
            snippet.contains("estimated") || snippet.contains("approx") || snippet.contains("expected price") || snippet.contains("rumored") ->
                TemporalValidity.ESTIMATED
            snippet.contains("last year") || snippet.contains("older model") || snippet.contains("discontinued") ->
                TemporalValidity.HISTORICAL
            price.isNotBlank() ->
                TemporalValidity.CURRENT
            else ->
                TemporalValidity.UNKNOWN
        }

        return ExtractedPriceItem(
            rawValue = price,
            sourceUrl = sourceUrl,
            validity = validity,
            contextSnippet = snippet
        )
    }
}

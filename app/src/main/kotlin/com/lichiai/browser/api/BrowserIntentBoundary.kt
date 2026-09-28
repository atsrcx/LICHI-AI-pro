package com.lichiai.browser.api

import java.util.Locale

/**
 * Top-Level Intent Boundary for Lichi Browser.
 * Enforces strict capability routing:
 * Browser intents terminate exclusively at BrowserAgent.
 * Existing Agent V2 and its providers are NEVER invoked for browser operations.
 */
object BrowserIntentBoundary {

    private val EXPLICIT_BROWSER_KEYWORDS = listOf(
        "browser", "internet", "website", "web page", "webpage", "web",
        "url", "chrome", "firefox", "safari"
    )

    private val DIRECT_SITES = mapOf(
        "google" to "https://www.google.com",
        "youtube" to "https://www.youtube.com",
        "wikipedia" to "https://www.wikipedia.org",
        "reddit" to "https://www.reddit.com",
        "github" to "https://www.github.com",
        "twitter" to "https://www.x.com",
        "x.com" to "https://www.x.com"
    )

    private val NON_BROWSER_APP_ACTIONS = listOf(
        "whatsapp", "instagram", "telegram", "settings", "setting", "camera", "gallery",
        "clock", "alarm", "call", "phone", "dial", "contact", "contacts", "bluetooth",
        "wifi", "flashlight", "torch", "volume", "brightness"
    )

    /**
     * Determines whether the given user input is a Browser Intent.
     */
    fun isBrowserIntent(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Explicit hash syntax (#google, #youtube, #open, #PUBG, etc.)
        if (trimmed.startsWith("#")) return true

        // 2. Direct URLs or common web domains
        val words = trimmed.split(Regex("\\s+"))
        for (w in words) {
            val clean = w.removeSuffix(".").removeSuffix(",").trim()
            if (clean.startsWith("http://") || clean.startsWith("https://")) return true
            if (clean.contains(".") && (
                        clean.endsWith(".com") || clean.endsWith(".org") || clean.endsWith(".net") ||
                        clean.endsWith(".in") || clean.endsWith(".io") || clean.endsWith(".ai") ||
                        clean.endsWith(".co") || clean.endsWith(".gov") || clean.endsWith(".edu")
                    )) {
                return true
            }
        }

        // 3. Reject other OS device app actions (e.g. WhatsApp, Calls, Bluetooth, Torch)
        val hasNonBrowserApp = NON_BROWSER_APP_ACTIONS.any {
            lower.contains(it) && !lower.contains("web") && !lower.contains("browser")
        }
        if (hasNonBrowserApp && !lower.contains("browser")) {
            return false
        }

        // 4. Explicit browser commands: "browser kholo", "open browser", "browser mein ...", "browser par ..."
        if (lower.contains("browser") || lower.contains("internet kholo") || lower.contains("open internet")) {
            return true
        }

        // 5. Site opening: "google kholo", "youtube kholo", "open wikipedia"
        for ((site, _) in DIRECT_SITES) {
            if (lower in listOf("$site kholo", "open $site", "$site open karo", "$site chalao") ||
                lower.startsWith("$site kholo") || lower.startsWith("open $site") ||
                lower.contains("$site par") || lower.contains("$site pe") ||
                lower.contains("$site mein") || lower.contains("$site pr")) {
                return true
            }
        }

        // 6. In-browser page actions
        val inBrowserActions = listOf(
            "doosra result", "pehla result", "teesra result", "third result", "first result", "second result",
            "next result", "pehli link", "doosri link", "third link", "official website kholo",
            "scroll down", "scroll up", "neeche scroll", "upar scroll", "page refresh", "reload",
            "previous page", "wapas jao", "go back", "next page", "forward jao", "new tab",
            "naya tab", "tab band", "close tab", "download option", "download link"
        )
        if (inBrowserActions.any { lower.contains(it) }) {
            return true
        }

        // 7. Search commands directed at the web: "PUBG game search karo", "search latest AI news"
        if (lower.startsWith("search ") || lower.contains("search karo") || lower.contains("dhundho") || lower.contains("khojo")) {
            return true
        }

        return false
    }

    /**
     * Resolves the structured BrowserUserIntent and generates user-visible status feedback.
     */
    fun resolveIntent(text: String): Pair<BrowserUserIntent, String> {
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // Check for direct site opening without search
        for ((site, url) in DIRECT_SITES) {
            if (lower in listOf("$site kholo", "open $site", "$site open karo", "$site chalao")) {
                val capitalized = site.replaceFirstChar { it.uppercase() }
                return Pair(
                    BrowserUserIntent.NavigateUrl(url),
                    "$capitalized open kar rahi hoon..."
                )
            }
        }

        // Parse via isolated BrowserCommandParser
        val intent = BrowserCommandParser.parse(trimmed)
        val acknowledgment = when (intent) {
            is BrowserUserIntent.OpenBrowser -> "Browser khol rahi hoon..."
            is BrowserUserIntent.NavigateUrl -> "Website open kar rahi hoon: ${intent.url}..."
            is BrowserUserIntent.Search -> {
                val eng = if (!intent.searchEngine.isNullOrBlank()) {
                    intent.searchEngine!!.replaceFirstChar { it.uppercase() }
                } else "Google"
                "$eng par \"${intent.query}\" search kar rahi hoon..."
            }
            is BrowserUserIntent.SearchAndOpen -> {
                val eng = if (!intent.searchEngine.isNullOrBlank()) {
                    intent.searchEngine!!.replaceFirstChar { it.uppercase() }
                } else "Google"
                val targetDesc = when (intent.targetCriterion) {
                    is TargetCriterion.Official -> "official website"
                    is TargetCriterion.Relevant -> "relevant website"
                    is TargetCriterion.Price -> "price result"
                    is TargetCriterion.Download -> "download page"
                    is TargetCriterion.Ordinal -> "${intent.targetCriterion.index} number result"
                    is TargetCriterion.Custom -> intent.targetCriterion.description
                }
                "$eng par \"${intent.searchQuery}\" search karke $targetDesc open kar rahi hoon..."
            }
            is BrowserUserIntent.ClickCandidate -> {
                if (intent.index != null) "Search results mein ${intent.index} number result open kar rahi hoon..."
                else "Relevant website open kar rahi hoon..."
            }
            is BrowserUserIntent.Scroll -> "Page scroll kar rahi hoon..."
            is BrowserUserIntent.Reload -> "Page refresh kar rahi hoon..."
            is BrowserUserIntent.GoBack -> "Peeche wapas jaa rahi hoon..."
            is BrowserUserIntent.GoForward -> "Aage jaa rahi hoon..."
            is BrowserUserIntent.OpenNewTab -> "Naya tab open kar rahi hoon..."
            is BrowserUserIntent.CloseCurrentTab -> "Tab band kar rahi hoon..."
            is BrowserUserIntent.ExtractInformation -> "Page par se information dekh rahi hoon..."
            is BrowserUserIntent.DownloadTarget -> "Download option dhoondh rahi hoon..."
            is BrowserUserIntent.StopTask -> "Browser task rok diya gaya hai."
            else -> "Browser par action execute kar rahi hoon..."
        }

        return Pair(intent, acknowledgment)
    }
}

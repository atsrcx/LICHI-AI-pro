package com.lichiai.intent.router

import com.lichiai.intent.model.BrowserActionType
import com.lichiai.intent.model.ResolvedIntent
import com.lichiai.skill.model.Skill
import java.util.Locale

/**
 * Semantic Router parses natural language in English, Hindi, Hinglish, and Roman Hindi.
 * Correctly separates WEB_SEARCH vs BROWSER vs ANDROID_AGENT without query contamination.
 */
class SemanticRouter(
    private val availableSkillsProvider: () -> List<Skill> = { emptyList() }
) {

    private val WEB_SEARCH_PREFIXES = listOf(
        "web search karo", "web search kar do", "web search kar do aur", "web search",
        "web pe search karo", "web par search karo", "web me search karo", "web mein search karo",
        "internet pe search karo", "internet par search karo", "internet search karo",
        "search api se", "online search karo", "api se search karo"
    )

    private val WEB_SEARCH_TRIGGERS = listOf(
        "web pe", "web par", "web mein", "web me", "internet par", "internet pe",
        "latest news", "taaza khabar", "aaj ki news", "current price", "weather today"
    )

    private val BROWSER_TRIGGERS = listOf(
        "browser kholo", "browser open karo", "browser mein", "browser me", "browser par", "browser pe",
        "chrome kholo", "chrome mein", "chrome par", "chrome pe", "lichi browser"
    )

    private val ANDROID_APP_NAMES = listOf(
        "instagram", "whatsapp", "telegram", "settings", "setting", "youtube", "spotify", "uber", "ola",
        "zomato", "swiggy", "amazon", "flipkart", "camera", "gallery", "clock",
        "calculator", "files", "contacts", "play store", "snapchat", "linkedin",
        "यूट्यूब", "स्पॉटिफ़ाई", "इंस्टाग्राम", "व्हाट्सएप", "टेलीग्राम", "सेटिंग्स"
    )

    private val QUESTION_MARKERS = listOf(
        "kaise karte hain", "kaise karein", "kaise kare", "kaise bhejte", "how to", "kya hai",
        "what is", "kaun hai", "who is", "kisne banaya", "kisne kiya", "meaning", "explain karo",
        "ka matlab", "ka reason"
    )

    fun route(normalizedText: String): Pair<ResolvedIntent, Float>? {
        val trimmed = normalizedText.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Check if input is a pure Question / Information Request (e.g. "Instagram kya hai?", "PUBG kisne banaya?")
        val isQuestion = QUESTION_MARKERS.any { lower.contains(it) }
        if (isQuestion) {
            // If it's asking for factual/world knowledge or real-time info
            if (lower.contains("kisne banaya") || lower.contains("kab release") || lower.contains("price") || lower.contains("kaunsa")) {
                var query = lower
                for (m in QUESTION_MARKERS + listOf("zara", "yaar", "batao", "batana", "hai", "bata do")) {
                    query = query.replace(m, " ")
                }
                query = query.replace(Regex("\\s+"), " ").trim()
                if (query.isNotBlank()) {
                    return Pair(
                        ResolvedIntent.WebSearchTask(
                            query = query,
                            naturalAcknowledgment = "$query ke baare mein dhoondh rahi hoon..."
                        ),
                        0.90f
                    )
                }
            }
            // Otherwise, purely conversational answer
            return Pair(
                ResolvedIntent.NormalChat(
                    prompt = rawTextToPrompt(trimmed),
                    naturalAcknowledgment = ""
                ),
                0.95f
            )
        }

        // 2. Check for Multi-Intent Sequential commands
        // Example: "Google pe PUBG search karo aur official website kholo"
        val multiIntent = matchMultiIntent(lower, trimmed)
        if (multiIntent != null) {
            return Pair(multiIntent, 0.95f)
        }

        // 3. Explicit Web Search Intent vs Browser Intent
        // "Web search karo latest Android news" -> WEB_SEARCH
        // "Web pe aaj Android ke baare mein kya news chal rahi hai?" -> WEB_SEARCH
        // "Abhi iPhone 17 Pro ka price check karo" -> WEB_SEARCH
        // "PUBG ki web par latest information dekhni hai" -> WEB_SEARCH
        val webSearchMatch = matchWebSearchIntent(lower, trimmed)
        if (webSearchMatch != null) {
            return Pair(webSearchMatch, 0.95f)
        }

        // 4. Browser Search / Navigation
        // "Browser kholo aur Google par PUBG game search karo" -> BROWSER
        // "Google pe PUBG game dhundho" -> BROWSER
        // "Yaar zara browser mein dekh na woh PUBG wali official site" -> BROWSER
        val browserMatch = matchBrowserIntent(lower, trimmed)
        if (browserMatch != null) {
            return Pair(browserMatch, 0.95f)
        }

        // 5. Android Device Automation / Agent V2
        // "Instagram kholo aur Aditya ko message kar do" -> ANDROID_AGENT
        // "Aditya ko bol de kal milne aa raha hai kya" -> ANDROID_AGENT
        val agentMatch = matchAndroidAgentIntent(lower, trimmed)
        if (agentMatch != null) {
            return Pair(agentMatch, 0.90f)
        }

        // 6. Media Playback Intent ("YouTube par gaana chalao", "play Arijit Singh on Spotify")
        val mediaMatch = matchMediaIntent(lower, trimmed)
        if (mediaMatch != null) {
            return Pair(mediaMatch, 0.90f)
        }

        return null
    }

    private fun rawTextToPrompt(text: String): String = text

    private fun matchMultiIntent(lower: String, rawText: String): ResolvedIntent.MultiStepTask? {
        // e.g. "Google pe PUBG search karo aur official website kholo"
        val multiStepPatterns = listOf(
            Regex("(.+?)\\s+(?:aur|phir|then|and)\\s+(?:jo\\s+)?(?:official website|official site|doosra result|pehla result)\\s+(?:hai usko\\s+)?kholo", RegexOption.IGNORE_CASE),
            Regex("(.+?)\\s+(?:aur|phir|then|and)\\s+(?:official website|official site|doosra result|pehla result)\\s+kholo", RegexOption.IGNORE_CASE)
        )

        for (pat in multiStepPatterns) {
            val match = pat.find(lower)
            if (match != null) {
                val firstPart = match.groupValues[1].trim()
                val firstIntentPair = route(firstPart)
                if (firstIntentPair != null && firstIntentPair.first is ResolvedIntent.BrowserTask) {
                    val step1 = firstIntentPair.first as ResolvedIntent.BrowserTask
                    val isOfficial = lower.contains("official")
                    val isSecond = lower.contains("doosra")
                    val step2 = ResolvedIntent.BrowserTask(
                        action = BrowserActionType.CLICK_CANDIDATE,
                        candidateIndex = if (isSecond) 1 else 0,
                        findTarget = if (isOfficial) "official" else null,
                        rawPrompt = "kholo",
                        naturalAcknowledgment = if (isOfficial) "Official site open kar rahi hoon..." else "Result open kar rahi hoon..."
                    )
                    return ResolvedIntent.MultiStepTask(
                        steps = listOf(step1, step2),
                        description = rawText,
                        naturalAcknowledgment = "Google par ${step1.query} search karke website open kar rahi hoon..."
                    )
                }
            }
        }
        return null
    }

    private fun matchWebSearchIntent(lower: String, rawText: String): ResolvedIntent.WebSearchTask? {
        // Check for pricing check or latest web info
        // e.g. "Abhi iPhone 17 Pro ka price check karo", "iPhone 17 pro ka price batao"
        if (lower.contains("price check") || (lower.contains("price") && (lower.contains("batao") || lower.contains("check")))) {
            var q = lower
            for (w in listOf("abhi", "zara", "yaar", "ka", "price", "check karo", "check", "batao", "batana", "kar do")) {
                q = q.replace(Regex("\\b$w\\b"), " ")
            }
            val cleanQ = q.replace(Regex("\\s+"), " ").trim()
            if (cleanQ.isNotBlank()) {
                return ResolvedIntent.WebSearchTask(
                    query = "$cleanQ price",
                    naturalAcknowledgment = "$cleanQ ka current price check kar rahi hoon..."
                )
            }
        }

        // e.g. "PUBG ki web par latest information dekhni hai"
        if (lower.contains("web par") || lower.contains("web pe") || lower.contains("web mein")) {
            if (lower.contains("information") || lower.contains("info") || lower.contains("dekhni hai") || lower.contains("dekhna hai")) {
                var q = lower
                for (w in listOf("web par", "web pe", "web mein", "latest", "information", "info", "dekhni hai", "dekhna hai", "zara", "yaar", "ki", "ka")) {
                    q = q.replace(Regex("\\b$w\\b"), " ")
                }
                val cleanQ = q.replace(Regex("\\s+"), " ").trim()
                if (cleanQ.isNotBlank()) {
                    return ResolvedIntent.WebSearchTask(
                        query = "$cleanQ latest information",
                        isNewsSearch = true,
                        naturalAcknowledgment = "$cleanQ ki information search kar rahi hoon..."
                    )
                }
            }
        }

        // Check if query begins with an explicit web search prefix
        for (prefix in WEB_SEARCH_PREFIXES) {
            if (lower.startsWith(prefix)) {
                val query = extractQueryAfterPrefix(rawText, prefix)
                if (query.isNotBlank()) {
                    val isImage = query.contains("image") || query.contains("photo") || query.contains("pic")
                    val isNews = query.contains("news") || query.contains("khabar")
                    return ResolvedIntent.WebSearchTask(
                        query = query,
                        isImageSearch = isImage,
                        isNewsSearch = isNews,
                        naturalAcknowledgment = "Web search se $query dhoondh rahi hoon..."
                    )
                }
            }
        }

        // Check if input mentions "web pe ... news" or "web par ... kya chal raha" without browser keywords
        val hasBrowserWord = BROWSER_TRIGGERS.any { lower.contains(it) }
        if (!hasBrowserWord) {
            val isWebTrigger = WEB_SEARCH_TRIGGERS.any { lower.contains(it) }
            if (isWebTrigger && (lower.contains("kya") || lower.contains("batana") || lower.contains("batao") || lower.contains("dekhna") || lower.contains("chal raha"))) {
                var cleanQuery = lower
                for (t in listOf("web pe", "web par", "web mein", "web me", "zara", "yaar", "dekhna", "batao", "batana", "kya chal raha hai", "kya chal raha", "bata raha hai")) {
                    cleanQuery = cleanQuery.replace(t, " ")
                }
                cleanQuery = cleanQuery.replace(Regex("\\s+"), " ").trim()
                if (cleanQuery.isNotBlank()) {
                    return ResolvedIntent.WebSearchTask(
                        query = cleanQuery,
                        isNewsSearch = cleanQuery.contains("news"),
                        naturalAcknowledgment = "$cleanQuery ke baare mein search kar rahi hoon..."
                    )
                }
            }
        }

        return null
    }

    private fun matchBrowserIntent(lower: String, rawText: String): ResolvedIntent.BrowserTask? {
        // Targets: google, youtube, duckduckgo, bing
        val targetEngine = when {
            lower.contains("youtube") -> "youtube"
            lower.contains("bing") -> "bing"
            lower.contains("duckduckgo") -> "duckduckgo"
            else -> "google"
        }

        // Patterns:
        // "Browser kholo aur Google par PUBG game search karo"
        // "Google pe PUBG game dhundho"
        // "Google par PUBG search karo"
        // "YouTube kholo aur Arijit Singh search karo"
        // "Browser mein YouTube kholo aur Arijit Singh search karo"
        val browserSearchPatterns = listOf(
            // "browser kholo aur google pe/par X search karo"
            Regex("(?:browser|chrome)\\s+(?:kholo|open karo)\\s+(?:aur|and)\\s+(?:google|youtube|bing)\\s+(?:par|pe|me|mein)?\\s*(.+?)\\s*(?:search karo|search kar do|dhundho|dhund do|khojo)?$", RegexOption.IGNORE_CASE),
            // "browser mein / browser par X search karo"
            Regex("(?:browser|chrome)\\s+(?:mein|me|par|pe)\\s+(?:google|youtube|bing)?\\s*(.+?)\\s*(?:search karo|search kar do|dhundho|dhund do|khojo)$", RegexOption.IGNORE_CASE),
            // "google/youtube pe/par X search karo"
            Regex("(?:google|youtube|bing)\\s+(?:par|pe|me|mein|pr)\\s*(.+?)\\s*(?:search karo|search kar do|dhundho|dhund do|khojo|chalao)$", RegexOption.IGNORE_CASE),
            // "google/youtube pe/par search karo/dhundho X"
            Regex("(?:google|youtube|bing)\\s+(?:par|pe|me|mein|pr)\\s*(?:search karo|search kar do|dhundho|dhund do|khojo|search)\\s+(.+?)$", RegexOption.IGNORE_CASE),
            // "google/youtube kholo aur X search karo"
            Regex("(?:google|youtube)\\s+(?:kholo|open karo|chalao)\\s+(?:aur|and)\\s*(.+?)\\s*(?:search karo|search kar do|dhundho|dhund do|play karo)?$", RegexOption.IGNORE_CASE),
            // Generic: "X search karo" when browser context is implied or directly asked
            Regex("(.+?)\\s+(?:ko\\s+)?(?:google|browser)\\s+(?:par|pe|mein)\\s*(?:search karo|dhundho)$", RegexOption.IGNORE_CASE),
            // "browser mein dekh na / dekho woh X"
            Regex("(?:browser|chrome)\\s+(?:mein|me)\\s+(?:dekh na|dekhna|dekho|check karo)?\\s*(?:woh\\s+)?(.+?)$", RegexOption.IGNORE_CASE)
        )

        for (pat in browserSearchPatterns) {
            val match = pat.find(lower)
            if (match != null) {
                var query = match.groupValues[1].trim()
                query = cleanExtractedQuery(query)
                if (query.isNotBlank()) {
                    return ResolvedIntent.BrowserTask(
                        action = BrowserActionType.SEARCH,
                        query = query,
                        searchEngine = targetEngine,
                        rawPrompt = rawText,
                        naturalAcknowledgment = "${targetEngine.replaceFirstChar { it.uppercase() }} par $query search kar rahi hoon..."
                    )
                }
            }
        }

        // Direct search: "PUBG game search karo"
        if (lower.endsWith("search karo") || lower.endsWith("search kar do") || lower.endsWith("dhundho") || lower.endsWith("dhund do")) {
            val isAgentApp = ANDROID_APP_NAMES.any { lower.contains(it) }
            if (!isAgentApp && !lower.startsWith("web ")) {
                var query = lower
                for (suf in listOf("search karo", "search kar do", "dhundho", "dhund do", "zara", "yaar", "ko", "par", "pe")) {
                    query = query.replace(Regex("\\b$suf\\b"), " ")
                }
                query = query.replace(Regex("\\s+"), " ").trim()
                if (query.isNotBlank()) {
                    return ResolvedIntent.BrowserTask(
                        action = BrowserActionType.SEARCH,
                        query = query,
                        searchEngine = "google",
                        rawPrompt = rawText,
                        naturalAcknowledgment = "Google par $query search kar rahi hoon..."
                    )
                }
            }
        }

        return null
    }

    private fun matchAndroidAgentIntent(lower: String, rawText: String): ResolvedIntent.AndroidAgentTask? {
        // Checks if an Android app and an automation action are requested:
        // "Instagram kholo mere phone mein"
        // "Instagram kholo aur Aditya ko message kar do"
        // "Settings mein jao aur Bluetooth on karo"
        // "Zomato kholo aur pizza order karo"
        val matchedApp = ANDROID_APP_NAMES.firstOrNull { lower.contains(it) }
        val hasAutomationVerb = listOf(
            "message", "text", "bhejo", "bhej do", "on kar do", "off kar do", "chalu", "band",
            "order", "book", "click", "tap", "scroll", "read screen", "screen padho", "screen pe kya hai",
            "gana", "gaana", "lagao", "laga do", "chalao", "chala do", "bajao", "baja do",
            "गाना", "चलाओ", "चला दो", "लगाओ", "लगा दो", "बजाओ", "मैसेज"
        ).any { lower.contains(it) }

        val isOpenVerb = listOf("kholo", "open", "chalao", "khol do", "start", "खोलो", "खोल दो", "चलाओ").any { lower.contains(it) }

        if (matchedApp != null && (hasAutomationVerb || isOpenVerb || lower.contains("kholo aur") || lower.contains("open karke"))) {
            // Find matched skills if any
            val skills = availableSkillsProvider().filter { skill ->
                lower.contains(skill.name.lowercase(Locale.ROOT)) ||
                (skill.purpose.isNotBlank() && lower.contains(skill.purpose.lowercase(Locale.ROOT))) ||
                (skill.whenToUse.isNotBlank() && lower.contains(skill.whenToUse.lowercase(Locale.ROOT)))
            }

            return ResolvedIntent.AndroidAgentTask(
                goal = rawText,
                targetApp = matchedApp,
                matchedSkills = skills,
                naturalAcknowledgment = "${matchedApp.replaceFirstChar { it.uppercase() }} mein task execute kar rahi hoon..."
            )
        }

        // Direct screen perception / automation queries
        if (lower.contains("read my screen") || lower.contains("screen pe kya") || lower.contains("screen padho") || lower.contains("automate phone")) {
            return ResolvedIntent.AndroidAgentTask(
                goal = rawText,
                naturalAcknowledgment = "Screen check karke automate kar rahi hoon..."
            )
        }

        // Conversational message automation: "Aditya ko bol de kal milne aa raha hai kya"
        val messageRegex = Regex("(.+?)\\s+ko\\s+(?:bol de|bol do|keh de|keh do|bata de|bata do|message kar do|message karo|text kar do|text karo)\\s+(.+)", RegexOption.IGNORE_CASE)
        val msgMatch = messageRegex.find(lower)
        if (msgMatch != null) {
            var recipient = msgMatch.groupValues[1]
            for (filler in listOf("yaar", "zara", "bhai", "please", "can you")) {
                recipient = recipient.replace(Regex("(?i)\\b$filler\\b"), " ")
            }
            recipient = recipient.replace(Regex("\\s+"), " ").trim()
            val messageContent = msgMatch.groupValues[2].trim()
            if (recipient.isNotBlank() && messageContent.isNotBlank() && !recipient.contains("kaise")) {
                return ResolvedIntent.AndroidAgentTask(
                    goal = "$recipient ko message bhejo: $messageContent",
                    targetApp = "whatsapp",
                    naturalAcknowledgment = "${recipient.replaceFirstChar { it.uppercase() }} ko message bhej rahi hoon..."
                )
            }
        }

        return null
    }

    private fun matchMediaIntent(lower: String, rawText: String): ResolvedIntent.MediaTask? {
        val hasMediaTrigger = lower.contains("gana lagao") || lower.contains("gaana lagao") ||
            lower.contains("gana laga do") || lower.contains("gaana laga do") ||
            lower.contains("gana chalao") || lower.contains("gaana chalao") ||
            lower.contains("song chalao") || lower.contains("song lagao") ||
            lower.contains("gana bajao") || lower.contains("gana baja do") ||
            lower.contains("play song") || lower.contains("play ") ||
            lower.contains("गाना चलाओ") || lower.contains("गाना लगाओ") || lower.contains("बजाओ")

        if (hasMediaTrigger) {
            var query = lower
            val fillerWords = listOf(
                "mere phone ki", "mere phone mein", "mere phone me", "phone ki", "phone mein",
                "youtube mein", "youtube me", "youtube par", "youtube pe", "spotify mein", "spotify me",
                "spotify par", "spotify pe", "gaana chalao", "gana chalao", "gaana chala do", "gana chala do",
                "gaana lagao", "gana lagao", "gaana laga do", "gana laga do", "gana bajao", "gana baja do",
                "song chalao", "song lagao", "play song", "play", "chalao", "chala do", "lagao", "laga do",
                "bajao", "baja do", "sunao", "suna do", "ek", "zara", "yaar", "ko", "ka", "ki", "mein", "par", "pe"
            )
            for (w in fillerWords) {
                query = query.replace(Regex("(?i)\\b$w\\b"), " ")
            }
            query = query.replace(Regex("\\s+"), " ").trim()
            if (query.isNotBlank()) {
                val app = if (lower.contains("spotify")) "spotify" else "youtube"
                val finalQuery = if (!query.contains("song") && !query.contains("gana")) "$query song" else query
                return ResolvedIntent.MediaTask(
                    query = finalQuery,
                    targetApp = app,
                    naturalAcknowledgment = "$finalQuery play kar rahi hoon..."
                )
            }
        }
        return null
    }

    private fun extractQueryAfterPrefix(text: String, prefix: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val idx = lower.indexOf(prefix)
        if (idx >= 0) {
            var q = text.substring(idx + prefix.length).trim()
            // Clean common suffix command words
            for (suf in listOf("karo", "kar do", "batana", "batao", "dekhna", "hai", "kya hai")) {
                if (q.lowercase(Locale.ROOT).endsWith(" $suf")) {
                    q = q.substring(0, q.length - suf.length - 1).trim()
                }
            }
            return q
        }
        return text
    }

    private fun cleanExtractedQuery(raw: String): String {
        var s = raw.trim()
        val removeKeywords = listOf(
            "search karo", "search kar do", "search", "dhundho", "dhund do",
            "khojo", "google pe", "google par", "youtube pe", "youtube par", "browser mein",
            "browser me", "browser par", "browser pe", "chrome mein", "chrome pe", "aur",
            "kholo", "open karo", "zara", "yaar", "please", "dekh na", "dekhna", "dekho", "wali", "woh",
            "ek", "ka gana lagao", "gana lagao", "gana laga do", "gaana lagao", "gaana laga do",
            "gana chalao", "gaana chalao", "lagao", "laga do", "chalao", "chala do", "sunao", "suna do",
            "bajao", "baja do", "play karo", "play kar do", "kar do", "karo"
        )
        for (kw in removeKeywords) {
            s = s.replace(Regex("(?i)\\b$kw\\b"), " ")
        }
        s = s.replace(Regex("\\s+"), " ").trim()
        // If the query was "Arijit Singh ka gana", clean to "Arijit Singh song"
        if (s.endsWith(" ka gana") || s.endsWith(" gana")) {
            s = s.replace(Regex("(?i)\\s+ka\\s+gana$"), " song").replace(Regex("(?i)\\s+gana$"), " song")
        }
        return s.trim()
    }
}

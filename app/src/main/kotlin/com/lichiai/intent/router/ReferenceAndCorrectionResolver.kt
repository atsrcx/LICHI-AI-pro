package com.lichiai.intent.router

import com.lichiai.calling.intent.CallAction
import com.lichiai.calling.intent.CallIntent
import com.lichiai.intent.model.ActiveTaskState
import com.lichiai.intent.model.BrowserActionType
import com.lichiai.intent.model.IntentContext
import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.ResolvedIntent
import java.util.Locale

/**
 * Resolves conversational references ("doosra result", "yeh wala", "ismein download dhundo", "pehle wala")
 * and follow-up corrections ("nahi browser mein karo", "nahi Rohit ko", "nahi Instagram pe")
 * using live IntentContext.
 */
object ReferenceAndCorrectionResolver {

    private val CANCELLATION_TRIGGERS = listOf(
        "chhodo", "rehne do", "cancel", "cancel karo", "mat karo", "nahi chahiye",
        "leave it", "stop", "bas", "ruk jao", "ruko", "ek minute ruk", "mat kar"
    )

    private val RESUME_TRIGGERS = listOf(
        "continue", "continue that", "resume", "wapas chalu karo", "wapas start karo",
        "aage badho", "carry on", "phir se chalu karo", "continue karo"
    )

    private val CONFIRMATION_YES = listOf(
        "haan", "yes", "kar do", "theek hai", "haan bhai", "ok", "sure", "ha", "yep", "bilkul"
    )

    private val CORRECTION_TRIGGERS = listOf(
        "nahi", "arre nahi", "are nahi", "no", "not this", "galat", "wrong", "mat karo",
        "nahi nahi", "actually", "instead", "chrome mein", "incognito mein"
    )

    private val CANDIDATE_INDEX_MAP = mapOf(
        "pehla" to 0, "first" to 0, "1st" to 0, "one" to 0, "ek" to 0,
        "doosra" to 1, "dusra" to 1, "second" to 1, "2nd" to 1, "two" to 1, "do" to 1,
        "teesra" to 2, "tisra" to 2, "third" to 2, "3rd" to 2, "three" to 2, "teen" to 2,
        "chautha" to 3, "fourth" to 3, "4th" to 3, "four" to 3, "char" to 3,
        "panchwa" to 4, "fifth" to 4, "5th" to 4, "five" to 4, "paanch" to 4
    )

    /**
     * Resolves references and corrections.
     * Returns Pair<ResolvedIntent, Float>? where Float is the confidence (e.g. 0.95f), or null if not applicable.
     */
    fun resolve(
        normalizedText: String,
        context: IntentContext
    ): Pair<ResolvedIntent, Float>? {
        val lower = normalizedText.lowercase(Locale.ROOT).trim()

        // 0. Contextual Question ("iska matlab kya hai?", "iska matlab kya hota hai?", "what does this mean?")
        if (lower.startsWith("iska matlab") || lower.contains("iska matlab kya") || lower == "ye kya hai" || lower == "iska explanation" || lower == "what does this mean" || lower == "what happened" || lower.startsWith("is context mein")) {
            val ref = context.lastAssistantResponse ?: context.recentResults.firstOrNull() ?: context.currentBrowserTitle
            return Pair(
                ResolvedIntent.ContextualQuestion(
                    question = normalizedText,
                    referenceContext = ref,
                    naturalAcknowledgment = ""
                ),
                0.99f
            )
        }

        // 0.5 Task Resumption ("continue", "continue that", "resume", "wapas chalu karo")
        val isResume = RESUME_TRIGGERS.any { lower == it || lower == "$it." || lower.startsWith("$it ") }
        if (isResume) {
            return Pair(
                ResolvedIntent.ResumeTask(
                    reason = normalizedText,
                    naturalAcknowledgment = "Task resume kar rahi hoon..."
                ),
                1.0f
            )
        }

        // 0.8 Task Interruption with new command (e.g. "ruko, rahul ko call karo", "wait, open terminal")
        for (prefix in listOf("ruko,", "ruko ,", "wait,", "wait ,", "ek minute,", "arre ruko,")) {
            if (lower.startsWith(prefix)) {
                val nextPart = lower.removePrefix(prefix).trim()
                if (nextPart.isNotBlank()) {
                    return Pair(
                        ResolvedIntent.TaskInterruption(
                            reason = "User interrupted with: $nextPart",
                            nextIntent = null, // Will be dispatched by orchestrator
                            naturalAcknowledgment = "Task pause kar rahi hoon..."
                        ),
                        0.98f
                    )
                }
            }
        }

        // 1. Natural Standalone Cancellation Check ("chhodo", "rehne do", "cancel karo", "mat karo", "stop", "ruko")
        val isCancellation = CANCELLATION_TRIGGERS.any { trigger ->
            lower == trigger || lower == "$trigger." || lower == "$trigger!" ||
            lower.startsWith("$trigger ") || lower.endsWith(" $trigger") || lower.contains(trigger) && !lower.contains("aur ")
        }
        if (isCancellation) {
            return Pair(
                ResolvedIntent.Cancellation(
                    reason = normalizedText,
                    naturalAcknowledgment = "Theek hai, task cancel kar diya."
                ),
                1.0f
            )
        }

        // 2. Pending Confirmation Check ("haan", "kar do", "yes") only when active pending confirmation exists
        if (context.pendingConfirmation != null || context.activeTaskState == ActiveTaskState.WAITING_FOR_CONFIRMATION) {
            val isConfirmYes = CONFIRMATION_YES.any { lower == it || lower == "$it." || lower.startsWith("$it ") }
            if (isConfirmYes) {
                val description = context.pendingConfirmation ?: "Requested action"
                return Pair(
                    ResolvedIntent.NormalChat(
                        prompt = "User confirmed: $description",
                        naturalAcknowledgment = "Ji, $description execute kar rahi hoon."
                    ),
                    0.99f
                )
            }
        }

        // 3. Correction check (e.g. "Nahi browser mein karo", "Nahi, Rohit ko", "Nahi Instagram pe", "Nahi call karo")
        val isCorrection = CORRECTION_TRIGGERS.any { lower.startsWith(it) || lower.contains(" nahi ") || lower.contains("instead") }
        if (isCorrection) {
            val resolvedCorrection = resolveCorrection(lower, normalizedText, context)
            if (resolvedCorrection != null) {
                return Pair(resolvedCorrection, 0.95f)
            }
        }

        // 4. Candidate / Link selection in Browser context (e.g. "doosra result kholo", "pehle wala kholo", "wahi wala kholo")
        val candidateMatch = matchCandidateSelection(lower, normalizedText, context)
        if (candidateMatch != null) {
            return Pair(candidateMatch, 0.98f)
        }

        // 5. Official website selection ("jo official website hai na usmein jao", "official link kholo")
        if (lower.contains("official website") || lower.contains("official link") || lower.contains("official site") || lower.contains("official wala")) {
            return Pair(
                ResolvedIntent.BrowserTask(
                    action = BrowserActionType.CLICK_CANDIDATE,
                    candidateIndex = 0,
                    findTarget = "official",
                    rawPrompt = normalizedText,
                    naturalAcknowledgment = "Official website open kar rahi hoon..."
                ),
                0.95f
            )
        }

        // 6. In-page search ("ismein download dhundo", "is page mein download option hai kya")
        val inPageFindMatch = matchInPageFind(lower, normalizedText)
        if (inPageFindMatch != null) {
            return Pair(inPageFindMatch, 0.95f)
        }

        // 7. In-page scroll commands ("thoda neeche jao", "neeche scroll karo", "thoda upar jao")
        if (lower.contains("thoda neeche") || lower.contains("neeche jao") || lower.contains("scroll down") || lower.contains("neeche scroll")) {
            return Pair(
                ResolvedIntent.BrowserTask(
                    action = BrowserActionType.SCROLL_DOWN,
                    rawPrompt = normalizedText,
                    naturalAcknowledgment = "Page scroll kar rahi hoon..."
                ),
                0.98f
            )
        }
        if (lower.contains("thoda upar") || lower.contains("upar jao") || lower.contains("scroll up") || lower.contains("upar scroll")) {
            return Pair(
                ResolvedIntent.BrowserTask(
                    action = BrowserActionType.SCROLL_UP,
                    rawPrompt = normalizedText,
                    naturalAcknowledgment = "Page scroll kar rahi hoon..."
                ),
                0.98f
            )
        }

        // 8. Navigation history ("back karo", "wapas jao", "go back", "previous page")
        if (lower in listOf("back karo", "wapas jao", "go back", "back jao", "peeche jao", "previous page")) {
            return Pair(
                ResolvedIntent.BrowserTask(
                    action = BrowserActionType.BACK,
                    rawPrompt = normalizedText,
                    naturalAcknowledgment = "Peeche ja rahi hoon..."
                ),
                0.99f
            )
        }

        // 9. UNIVERSAL CONTEXT CONTINUITY ENGINE RESOLUTION
        // Handles natural pronouns, demonstratives, Hinglish/Hindi references, cross-capability transitions,
        // and entity attribute inquiries (followers, bio, website, specifics, email, phone).
        if (context.lastPlatformProfile != null) {
            com.lichiai.context.engine.UniversalContextContinuityEngine.getInstance().restoreFromProfiles(
                conversationId = context.conversationId ?: "default_session",
                profiles = (listOfNotNull(context.lastPlatformProfile) + context.recentProfiles).distinctBy { it.username to it.platform }
            )
        }

        val continuityResult = com.lichiai.context.engine.UniversalContextContinuityEngine.getInstance().resolveReference(
            rawInput = normalizedText,
            conversationId = context.conversationId ?: "default_session"
        )

        if (continuityResult.confidence >= 0.85f || continuityResult.isAmbiguous) {
            // Case A: Genuine multi-entity ambiguity
            if (continuityResult.isAmbiguous) {
                return Pair(
                    ResolvedIntent.Clarification(
                        question = continuityResult.clarificationQuestion ?: "Aap kis account ki baat kar rahe hain?",
                        options = continuityResult.ambiguityCandidates.map { it.name },
                        naturalAcknowledgment = continuityResult.clarificationQuestion ?: "Please clarify."
                    ),
                    0.96f
                )
            }

            val entity = continuityResult.resolvedEntity
            if (entity != null && entity.type == com.lichiai.context.model.EntityType.PROFILE) {
                // Cross-capability: Website exploration
                if (lower.contains("website") || lower.contains("site")) {
                    val webUrl = entity.website ?: entity.url
                    if (!webUrl.isNullOrBlank()) {
                        val isBrowser = lower.contains("kholo") || lower.contains("open") || lower.contains("browser")
                        return if (isBrowser) {
                            Pair(
                                ResolvedIntent.BrowserTask(
                                    action = BrowserActionType.NAVIGATE,
                                    url = webUrl,
                                    rawPrompt = normalizedText,
                                    naturalAcknowledgment = "${entity.displayName.ifBlank { entity.name }} ki website open kar rahi hoon..."
                                ),
                                0.98f
                            )
                        } else {
                            Pair(
                                ResolvedIntent.WebSearchTask(
                                    query = "$webUrl official information review",
                                    naturalAcknowledgment = "${entity.displayName.ifBlank { entity.name }} ki website analyze kar rahi hoon..."
                                ),
                                0.95f
                            )
                        }
                    }
                }

                // Cross-capability: Browser navigation
                if (lower.contains("browser") && (lower.contains("kholo") || lower.contains("open"))) {
                    val targetUrl = entity.website ?: entity.url ?: "https://instagram.com/${entity.name}"
                    return Pair(
                        ResolvedIntent.BrowserTask(
                            action = BrowserActionType.NAVIGATE,
                            url = targetUrl,
                            rawPrompt = normalizedText,
                            naturalAcknowledgment = "${entity.name} browser mein open kar rahi hoon..."
                        ),
                        0.98f
                    )
                }

                // Entity attribute inquiry (followers, bio, specifics, info, email, phone) or entity correction
                // Solves the core bug: Answers with verified profile data WITHOUT re-scraping Apify or asking "Kaunse account?"!
                if (continuityResult.isCorrection || isProfileAttributeOrSpecificsQuery(lower) || lower.contains("baat")) {
                    val compactSummary = entity.toCompactSummary()
                    return Pair(
                        ResolvedIntent.ContextualQuestion(
                            question = normalizedText,
                            referenceContext = compactSummary,
                            naturalAcknowledgment = if (continuityResult.isCorrection) "Theek hai, ${entity.displayName.ifBlank { entity.name }} ki baat karte hain." else ""
                        ),
                        0.98f
                    )
                }

                // Posts or Media search
                if (lower.contains("post") || lower.contains("posts") || lower.contains("reel") || lower.contains("video")) {
                    return Pair(
                        ResolvedIntent.WebSearchTask(
                            query = "${entity.platform ?: "Instagram"} @${entity.name} posts",
                            naturalAcknowledgment = "${entity.name} ke posts dhoondh rahi hoon..."
                        ),
                        0.95f
                    )
                }
            }

            // Cross-capability browser suggestion
            if (continuityResult.suggestedCapability == LichiCapability.BROWSER && !continuityResult.targetUrl.isNullOrBlank()) {
                return Pair(
                    ResolvedIntent.BrowserTask(
                        action = BrowserActionType.NAVIGATE,
                        url = continuityResult.targetUrl,
                        rawPrompt = normalizedText,
                        naturalAcknowledgment = "Browser mein open kar rahi hoon..."
                    ),
                    continuityResult.confidence
                )
            }

            // Cross-capability web search suggestion
            if (continuityResult.suggestedCapability == LichiCapability.WEB_SEARCH && !continuityResult.targetText.isNullOrBlank()) {
                return Pair(
                    ResolvedIntent.WebSearchTask(
                        query = continuityResult.targetText,
                        naturalAcknowledgment = "Dhoondh rahi hoon..."
                    ),
                    continuityResult.confidence
                )
            }
        }

        return null
    }

    private fun isProfileAttributeOrSpecificsQuery(lower: String): Boolean {
        val triggers = listOf(
            "follower", "followers", "following", "bio", "biography", "detail", "details",
            "specific", "cheez", "cheezein", "cheeze", "baatein", "info", "information",
            "email", "mail", "phone", "number", "kya karta", "kaun hai", "posts", "post count",
            "subscribers", "views", "account", "profile", "data"
        )
        return triggers.any { lower.contains(it) }
    }

    private fun resolveCorrection(lower: String, rawText: String, context: IntentContext): ResolvedIntent? {
        val lastQuery = context.lastSearchQuery ?: context.lastUserGoal
        val previousContact = context.recentEntities["contact"] ?: context.recentEntities["recipient"]

        // 1. Platform correction for messaging/agent (e.g. "Nahi Instagram pe message bhejo", "Nahi WhatsApp pe bhejo")
        val isProfileOrConversationalContext = context.lastPlatformProfile != null ||
                context.recentProfiles.isNotEmpty() ||
                lower.contains("wal") || lower.contains("baat") || lower.contains("account") || lower.contains("profile")
        if (!isProfileOrConversationalContext && (lower.contains("instagram") || lower.contains("whatsapp") || lower.contains("telegram") || lower.contains("snapchat"))) {
            val targetApp = when {
                lower.contains("instagram") -> "instagram"
                lower.contains("whatsapp") -> "whatsapp"
                lower.contains("snapchat") -> "snapchat"
                else -> "telegram"
            }
            val recipient = previousContact ?: "Aditya"
            val goal = "$targetApp par $recipient ko message bhejo"
            return ResolvedIntent.AndroidAgentTask(
                goal = goal,
                targetApp = targetApp,
                naturalAcknowledgment = "Theek hai, $targetApp par $recipient ko message bhej rahi hoon..."
            )
        }

        // 2. Mode correction to BROWSER (e.g. "Nahi browser mein karo", "Google pe karo", "Nahi Chrome mein")
        if (lower.contains("browser") || lower.contains("google pe") || lower.contains("google par") || lower.contains("chrome") || lower.contains("incognito")) {
            val queryToUse = extractCorrectedQuery(lower) ?: lastQuery ?: "Google"
            val targetEngine = if (lower.contains("youtube")) "youtube" else "google"
            return ResolvedIntent.BrowserTask(
                action = BrowserActionType.SEARCH,
                query = queryToUse,
                searchEngine = targetEngine,
                rawPrompt = lower,
                naturalAcknowledgment = "Theek hai, browser mein $targetEngine par $queryToUse search kar rahi hoon."
            )
        }

        // 2.5 Mode correction to TERMINAL (e.g. "Nahi terminal mein", "Actually use terminal", "Nahi terminal se karo")
        if (lower.contains("terminal") || lower.contains("command line") || lower.contains("termux")) {
            val cmd = extractCorrectedQuery(lower) ?: context.lastUserGoal ?: ""
            return ResolvedIntent.TerminalTask(
                command = cmd,
                action = if (cmd.isNotBlank()) "EXECUTE" else "OPEN",
                rawPrompt = rawText,
                naturalAcknowledgment = "Theek hai, Terminal par execute kar rahi hoon."
            )
        }

        // 3. Mode correction to WEB_SEARCH (e.g. "Nahi web search karo")
        if (lower.contains("web search") || lower.contains("search api") || lower.contains("web pe")) {
            val queryToUse = extractCorrectedQuery(lower) ?: lastQuery ?: ""
            if (queryToUse.isNotBlank()) {
                return ResolvedIntent.WebSearchTask(
                    query = queryToUse,
                    naturalAcknowledgment = "Theek hai, web search se $queryToUse dhoondh rahi hoon."
                )
            }
        }

        // 4. Action correction to CALL (e.g. "Nahi call karo", "Nahi call lagao")
        if (lower.contains("call karo") || lower.contains("call kar do") || lower.contains("call lagao")) {
            val contactToCall = extractCorrectedName(lower) ?: previousContact
            if (!contactToCall.isNullOrBlank()) {
                return ResolvedIntent.CallTask(
                    callIntent = CallIntent(
                        action = CallAction.CALL_CONTACT,
                        targetText = contactToCall,
                        originalText = rawText
                    ),
                    naturalAcknowledgment = "Theek hai, $contactToCall ko call mila rahi hoon..."
                )
            }
        }

        // 5. Entity / Target correction for CALLS (e.g., previous: "Call Rahul", user: "Nahi, Rohit ko" or "Nahi Rohit")
        val isCallContext = context.lastExecutedCapability == LichiCapability.CALLS ||
                context.lastActionType == "CALL" ||
                context.recentEntities.containsKey("contact")
        if (isCallContext && (lower.contains("ko") || lower.contains("call") || lower.startsWith("nahi "))) {
            val correctedName = extractCorrectedName(lower)
            if (!correctedName.isNullOrBlank() && !isEngineOrModeWord(correctedName)) {
                return ResolvedIntent.CallTask(
                    callIntent = CallIntent(
                        action = CallAction.CALL_CONTACT,
                        targetText = correctedName,
                        originalText = rawText
                    ),
                    naturalAcknowledgment = "Theek hai, $correctedName ko call mila rahi hoon..."
                )
            }
        }

        return null
    }

    private fun extractCorrectedName(lower: String): String? {
        // e.g. "nahi, rohit ko", "nahi rohit ko", "nahi rohit"
        var s = lower
        for (prefix in listOf("arre nahi", "are nahi", "nahi", "no", "actually")) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length).trim()
            }
        }
        s = s.replace(",", " ").trim()
        if (s.endsWith(" ko")) {
            s = s.substring(0, s.length - 3).trim()
        }
        if (s.endsWith(" ko call karo") || s.endsWith(" ko call kar do")) {
            s = s.replace(Regex("\\bko call kar(?:o| do)\\b"), "").trim()
        }
        return s.trim().takeIf { it.isNotBlank() && it.length <= 30 }
    }

    private fun isEngineOrModeWord(word: String): Boolean {
        return listOf(
            "browser", "chrome", "google", "web", "search", "call", "message", "nahi",
            "instagram", "whatsapp", "telegram", "snapchat", "youtube", "facebook", "reddit", "settings"
        ).any {
            word.contains(it)
        }
    }

    private fun extractCorrectedQuery(lower: String): String? {
        val patterns = listOf(
            Regex("karo\\s+(?:mein\\s+)?(.+)", RegexOption.IGNORE_CASE),
            Regex("par\\s+(.+?)(?:\\s+karo)?$", RegexOption.IGNORE_CASE),
            Regex("pe\\s+(.+?)(?:\\s+karo)?$", RegexOption.IGNORE_CASE)
        )
        for (pat in patterns) {
            val match = pat.find(lower)
            if (match != null) {
                val candidate = match.groupValues[1].trim()
                if (candidate.isNotBlank() && !candidate.contains("browser") && !candidate.contains("google")) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun matchCandidateSelection(lower: String, rawText: String, context: IntentContext): ResolvedIntent? {
        val hasBrowserContext = context.lastExecutedCapability == LichiCapability.BROWSER ||
                context.browserCandidates.isNotEmpty() ||
                context.currentBrowserUrl != null
        val hasClickVerb = lower.contains("kholo") || lower.contains("open") || lower.contains("click") ||
                lower.contains("link") || lower.contains("result")

        // If this is an entity inquiry (phone number, email, followers, detail, info, bio) without browser click verbs, skip
        val isEntityInquiry = lower.contains("phone") || lower.contains("email") || lower.contains("batao") ||
                lower.contains("detail") || lower.contains("follower") || lower.contains("bio")
        if (isEntityInquiry && !lower.contains("kholo") && !lower.contains("open") && !lower.contains("click") && !lower.contains("result")) {
            return null
        }

        if (!hasBrowserContext && !hasClickVerb) {
            return null
        }

        // Ordinals: "doosra result kholo", "jo doosra result hai usko kholo", "second link open karo",
        // "teesra result", "pehla wala kholo", "usi result ko kholo", "pehle wala kholo"
        for ((word, index) in CANDIDATE_INDEX_MAP) {
            val hasWordNumber = Regex("\\b${Regex.escape(word)} number\\b", RegexOption.IGNORE_CASE).containsMatchIn(lower)
            if (lower.contains("$word result") || lower.contains("$word wala") ||
                lower.contains("$word link") || hasWordNumber ||
                lower.contains("$word option") || lower.startsWith("$word ") ||
                lower == word || lower == "$word result") {
                return ResolvedIntent.BrowserTask(
                    action = BrowserActionType.CLICK_CANDIDATE,
                    candidateIndex = index,
                    rawPrompt = rawText,
                    naturalAcknowledgment = "${word.replaceFirstChar { it.uppercase() }} result open kar rahi hoon..."
                )
            }
        }

        // Positional: "upar wala" / "top wala" -> 0, "neeche wala" -> last index
        if (lower.contains("upar wala") || lower.contains("top wala") || lower.contains("pehla")) {
            return ResolvedIntent.BrowserTask(
                action = BrowserActionType.CLICK_CANDIDATE,
                candidateIndex = 0,
                rawPrompt = rawText,
                naturalAcknowledgment = "Upar wala result open kar rahi hoon..."
            )
        }
        if (lower.contains("neeche wala") || lower.contains("last wala") || lower.contains("aakhri wala")) {
            val lastIdx = (context.browserCandidates.size - 1).coerceAtLeast(0)
            return ResolvedIntent.BrowserTask(
                action = BrowserActionType.CLICK_CANDIDATE,
                candidateIndex = lastIdx,
                rawPrompt = rawText,
                naturalAcknowledgment = "Neeche wala result open kar rahi hoon..."
            )
        }

        // References: "yeh wala", "ye wala", "usi result", "usko kholo", "wahi wala", "same wala", "jo pehle tha"
        if (lower.contains("yeh wala") || lower.contains("ye wala") || lower.contains("usi result") ||
            lower.contains("usko kholo") || lower.contains("wahi wala") || lower.contains("same wala") ||
            lower.contains("jo pehle tha") || lower.contains("wohi wala")) {
            return ResolvedIntent.BrowserTask(
                action = BrowserActionType.CLICK_CANDIDATE,
                candidateIndex = 0,
                rawPrompt = rawText,
                naturalAcknowledgment = "Result open kar rahi hoon..."
            )
        }

        return null
    }

    private fun matchInPageFind(lower: String, rawText: String): ResolvedIntent? {
        val findKeywords = listOf("dhundo", "dhundho", "search karo", "find", "hai kya", "kahan hai", "option hai", "option", "hai?")
        val isFindIntent = (findKeywords.any { lower.contains(it) } || lower.endsWith(" hai?")) &&
                (lower.contains("ismein") || lower.contains("is page") || lower.contains("page mein") || lower.contains("is website"))

        if (isFindIntent) {
            var target = lower
            for (kw in listOf("ab ismein", "ismein", "is page mein", "is page pe", "page mein", "is website mein", "option hai", "option", "hai kya", "kahan hai", "dhundo", "dhundho", "search karo", "find", "zara", "yaar", "hai?", "hai", "?")) {
                target = target.replace(kw, "")
            }
            val cleanTarget = target.trim()
            if (cleanTarget.isNotBlank()) {
                return ResolvedIntent.BrowserTask(
                    action = BrowserActionType.FIND_ON_PAGE,
                    findTarget = cleanTarget,
                    rawPrompt = rawText,
                    naturalAcknowledgment = "Page par '$cleanTarget' dhoondh rahi hoon..."
                )
            }
        }
        return null
    }
}

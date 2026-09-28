package com.lichiai.web.planner

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Universal Query Planner for Lichi Web Intelligence V2.
 * Converts user natural language requests (English, Hindi, Hinglish, multilingual)
 * into a structured, executable SearchPlan with domain-specific, freshness-aware queries.
 */
object QueryPlanner {

    private val MONTH_NAMES = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    private fun getCurrentDateContext(): Pair<String, String> {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR).toString()
        val month = MONTH_NAMES[cal.get(Calendar.MONTH)]
        return Pair(month, year) // e.g. ("September", "2026")
    }

    /**
     * Builds a comprehensive SearchPlan from any user prompt.
     */
    fun plan(userPrompt: String): SearchPlan {
        val raw = userPrompt.trim()
        val lower = raw.lowercase(Locale.ROOT)
        val (currentMonth, currentYear) = getCurrentDateContext()
        val currentDateLabel = "$currentMonth $currentYear"

        // 1. Detect User Custom Constraints
        var explicitSite: String? = null
        var onlyOfficial = false
        var multiSourceRequested = false
        var deepResearchRequested = false

        if (lower.contains("sirf apple") || lower.contains("only apple") || lower.contains("apple.com")) {
            explicitSite = "apple.com/in"
        } else if (lower.contains("sirf samsung") || lower.contains("samsung.com")) {
            explicitSite = "samsung.com/in"
        } else if (lower.contains("sirf amazon") || lower.contains("amazon se")) {
            explicitSite = "amazon.in"
        } else if (lower.contains("sirf flipkart") || lower.contains("flipkart se")) {
            explicitSite = "flipkart.com"
        }

        if (lower.contains("official website") || lower.contains("official site") || lower.contains("sirf official")) {
            onlyOfficial = true
        }
        if (lower.contains("multiple source") || lower.contains("cross check") || lower.contains("verify karo") || lower.contains("dono check")) {
            multiSourceRequested = true
        }
        if (lower.contains("deep research") || lower.contains("detail mein") || lower.contains("deeply search")) {
            deepResearchRequested = true
        }

        // 2. Classify Intent
        val intent = classifyIntent(lower)

        // 3. Extract Entities (Product, Brand, Variant, Storage, Location)
        val entities = extractEntities(lower, raw)

        // 4. Determine Location & Currency
        val location = entities.location ?: if (lower.contains("₹") || lower.contains("rupee") || lower.contains("inr") ||
            lower.contains("lakh") || lower.contains("crore") || lower.contains("delhi") || lower.contains("mumbai") ||
            lower.contains("noida") || lower.contains("bengaluru") || lower.contains("mein") || lower.contains("hai")) {
            "India"
        } else null

        // 5. Ambiguity Check for Current Price
        var isAmbiguous = false
        var ambiguityReason: String? = null

        if (intent == WebSearchIntent.CURRENT_PRICE) {
            val prod = entities.product?.lowercase(Locale.ROOT) ?: ""
            if (prod == "iphone" && entities.modelVariant == null && !lower.contains("17") && !lower.contains("16") && !lower.contains("15") && !lower.contains("14")) {
                isAmbiguous = true
                ambiguityReason = "Specific iPhone model (e.g. iPhone 17, iPhone 16) and storage variant (e.g. 128GB, 256GB) not specified in query."
            } else if (prod.contains("galaxy") && !prod.contains("s26") && !prod.contains("s25") && !prod.contains("s24")) {
                isAmbiguous = true
                ambiguityReason = "Specific Samsung Galaxy model not specified."
            }
        }

        // 6. Generate Optimized Search Queries
        val plannedQueries = generateQueries(
            intent = intent,
            entities = entities,
            rawQuery = raw,
            location = location,
            currentDateLabel = currentDateLabel,
            explicitSite = explicitSite,
            onlyOfficial = onlyOfficial
        )

        return SearchPlan(
            intent = intent.name,
            originalQuery = raw,
            normalizedQuery = cleanNormalizedQuery(raw),
            entities = entities,
            location = location,
            freshness = currentDateLabel,
            requiredEvidence = when (intent) {
                WebSearchIntent.CURRENT_PRICE -> "EXACT_PRICE"
                WebSearchIntent.SPECIFICATIONS -> "OFFICIAL_SPECS"
                WebSearchIntent.LATEST_NEWS, WebSearchIntent.NEWS -> "TIMESTAMPS_AND_HEADLINES"
                WebSearchIntent.FACT_CHECK -> "CLAIM_VERDICT"
                WebSearchIntent.IMAGE_SEARCH -> "IMAGE_GALLERY"
                else -> "FACTUAL_SUMMARY"
            },
            preferredDomains = determinePreferredDomains(entities, explicitSite, onlyOfficial),
            exactFactRequired = intent == WebSearchIntent.CURRENT_PRICE || intent == WebSearchIntent.FACT_CHECK,
            verificationRequired = intent == WebSearchIntent.CURRENT_PRICE || multiSourceRequested || intent == WebSearchIntent.FACT_CHECK,
            multiSourceRequired = multiSourceRequested || intent == WebSearchIntent.CURRENT_PRICE,
            imageRequired = intent == WebSearchIntent.IMAGE_SEARCH,
            newsRequired = intent == WebSearchIntent.NEWS || intent == WebSearchIntent.LATEST_NEWS,
            deepResearch = deepResearchRequested || intent == WebSearchIntent.DEEP_RESEARCH,
            userExplicitSiteConstraint = explicitSite,
            queries = plannedQueries,
            isAmbiguous = isAmbiguous,
            ambiguityReason = ambiguityReason
        )
    }

    private fun classifyIntent(lower: String): WebSearchIntent {
        // Price detection keywords (English, Hindi, Hinglish)
        val priceKeywords = listOf(
            "current price", "abhi rate", "aaj ki price", "latest price", "kitne ka hai",
            "current rate", "kimat", "keemat", "price kya hai", "rate batao", "kitna kharcha",
            "cost", "sale price", "kitne mein milega", "mrp", "price of", "rate of",
            "कीमत", "कितना है", "रेट", "भाव", "rate kya"
        )
        if (priceKeywords.any { lower.contains(it) }) {
            return if (lower.contains(" vs ") || lower.contains("compare")) {
                WebSearchIntent.PRICE_COMPARISON
            } else {
                WebSearchIntent.CURRENT_PRICE
            }
        }

        // Image search
        val imageKeywords = listOf("image", "photo", "picture", "tasveer", "wallpaper", "photos", "dikhao", "फोटो", "तस्वीर")
        if (imageKeywords.any { lower.contains(it) } && (lower.contains("dikhao") || lower.contains("show") || lower.contains("search") || lower.contains("ke photo") || lower.contains("ki photo"))) {
            return WebSearchIntent.IMAGE_SEARCH
        }

        // News search
        val newsKeywords = listOf(
            "latest news", "today news", "abhi kya hua", "recent update", "taaza khabar",
            "breaking news", "aaj ka samachar", "खबर", "समाचार", "news today", "kya chal raha",
            "duniya mein", "world mein", "happening in the world", "world news", "global news"
        )
        if (newsKeywords.any { lower.contains(it) }) {
            return WebSearchIntent.LATEST_NEWS
        }
        if (lower.contains("news") || lower.contains("khabar")) {
            return WebSearchIntent.NEWS
        }

        // Fact Check
        val factCheckKeywords = listOf("sach hai", "fact check", "claim verify", "is it true", "real hai ya fake", "afwah", "rumor hai kya", "verify karo ye")
        if (factCheckKeywords.any { lower.contains(it) }) {
            return WebSearchIntent.FACT_CHECK
        }

        // Website Reading / Article Summary
        if ((lower.contains("padh kar") || lower.contains("summarize") || lower.contains("summary do") || lower.contains("read this")) &&
            (lower.contains("http") || lower.contains(".com") || lower.contains(".org") || lower.contains(".in") || lower.contains("website") || lower.contains("link"))) {
            return WebSearchIntent.WEBSITE_READING
        }

        // Comparison
        if (lower.contains(" vs ") || lower.contains("compare") || lower.contains("kaunsa better") || lower.contains("difference between") || lower.contains("dono mein se")) {
            return WebSearchIntent.PRODUCT_COMPARISON
        }

        // Deep Research
        if (lower.contains("deep research") || lower.contains("detail mein") || lower.contains("deeply search") || lower.contains("thorough research")) {
            return WebSearchIntent.DEEP_RESEARCH
        }

        // Specifications
        val specKeywords = listOf("specs", "specification", "features", "details", "kya kya features", "battery kitni", "camera kitna")
        if (specKeywords.any { lower.contains(it) }) {
            return WebSearchIntent.SPECIFICATIONS
        }

        // Availability
        val availKeywords = listOf("available", "in stock", "stock mein", "kaha milega", "out of stock", "availability")
        if (availKeywords.any { lower.contains(it) }) {
            return WebSearchIntent.AVAILABILITY
        }

        // Time sensitive / Current info
        if (lower.contains("current") || lower.contains("abhi") || lower.contains("today") || lower.contains("aaj") || lower.contains("latest") || lower.contains("now")) {
            return WebSearchIntent.CURRENT_INFORMATION
        }

        return WebSearchIntent.GENERAL_WEB_SEARCH
    }

    private fun extractEntities(lower: String, raw: String): ExtractedEntities {
        var brand: String? = null
        var product: String? = null
        var modelVariant: String? = null
        var storage: String? = null
        var color: String? = null
        var location: String? = null

        // Storage matching
        val storageRegex = Regex("""\b(32|64|128|256|512)\s*(?:gb|gigabytes?)\b|\b(1|2)\s*(?:tb|terabytes?)\b""", RegexOption.IGNORE_CASE)
        val storageMatch = storageRegex.find(lower)
        if (storageMatch != null) {
            storage = storageMatch.value.uppercase().replace(" ", "")
        }

        // Brand & Product identification
        when {
            lower.contains("iphone") -> {
                brand = "Apple"
                product = when {
                    lower.contains("17 pro max") -> "iPhone 17 Pro Max"
                    lower.contains("17 pro") -> "iPhone 17 Pro"
                    lower.contains("17 plus") || lower.contains("17 air") -> "iPhone 17 Plus"
                    lower.contains("17") -> "iPhone 17"
                    lower.contains("16 pro max") -> "iPhone 16 Pro Max"
                    lower.contains("16 pro") -> "iPhone 16 Pro"
                    lower.contains("16 plus") -> "iPhone 16 Plus"
                    lower.contains("16") -> "iPhone 16"
                    lower.contains("15 pro max") -> "iPhone 15 Pro Max"
                    lower.contains("15 pro") -> "iPhone 15 Pro"
                    lower.contains("15") -> "iPhone 15"
                    lower.contains("14") -> "iPhone 14"
                    lower.contains("13") -> "iPhone 13"
                    lower.contains("se") -> "iPhone SE"
                    else -> "iPhone"
                }
                if (lower.contains("pro max")) modelVariant = "Pro Max"
                else if (lower.contains("pro")) modelVariant = "Pro"
                else if (lower.contains("plus")) modelVariant = "Plus"
            }
            lower.contains("samsung") || lower.contains("galaxy") || lower.contains("s26") || lower.contains("s25") || lower.contains("s24") -> {
                brand = "Samsung"
                product = when {
                    lower.contains("s26 ultra") -> "Galaxy S26 Ultra"
                    lower.contains("s26 plus") -> "Galaxy S26 Plus"
                    lower.contains("s26") -> "Galaxy S26"
                    lower.contains("s25 ultra") -> "Galaxy S25 Ultra"
                    lower.contains("s25") -> "Galaxy S25"
                    lower.contains("s24 ultra") -> "Galaxy S24 Ultra"
                    lower.contains("s24") -> "Galaxy S24"
                    lower.contains("fold") -> "Galaxy Z Fold"
                    lower.contains("flip") -> "Galaxy Z Flip"
                    else -> "Samsung Galaxy"
                }
                if (lower.contains("ultra")) modelVariant = "Ultra"
            }
            lower.contains("pixel") -> {
                brand = "Google"
                product = when {
                    lower.contains("10 pro") -> "Pixel 10 Pro"
                    lower.contains("10") -> "Pixel 10"
                    lower.contains("9 pro") -> "Pixel 9 Pro"
                    lower.contains("9") -> "Pixel 9"
                    lower.contains("8") -> "Pixel 8"
                    else -> "Google Pixel"
                }
                if (lower.contains("pro")) modelVariant = "Pro"
            }
            lower.contains("petrol") -> {
                product = "Petrol"
            }
            lower.contains("diesel") -> {
                product = "Diesel"
            }
            lower.contains("gold") -> {
                product = "Gold"
            }
            lower.contains("macbook") -> {
                brand = "Apple"
                product = if (lower.contains("pro")) "MacBook Pro" else "MacBook Air"
            }
        }

        // Location identification
        val locKeywords = mapOf(
            "delhi" to "Delhi, India",
            "mumbai" to "Mumbai, India",
            "noida" to "Noida, India",
            "bengaluru" to "Bengaluru, India",
            "bangalore" to "Bengaluru, India",
            "kolkata" to "Kolkata, India",
            "chennai" to "Chennai, India",
            "hyderabad" to "Hyderabad, India",
            "pune" to "Pune, India",
            "india" to "India",
            "usa" to "United States",
            "us" to "United States",
            "uk" to "United Kingdom"
        )
        for ((k, v) in locKeywords) {
            if (lower.contains(Regex("""\b$k\b"""))) {
                location = v
                break
            }
        }

        return ExtractedEntities(
            brand = brand,
            product = product,
            modelVariant = modelVariant,
            storage = storage,
            color = color,
            location = location,
            rawTopic = raw
        )
    }

    private fun generateQueries(
        intent: WebSearchIntent,
        entities: ExtractedEntities,
        rawQuery: String,
        location: String?,
        currentDateLabel: String,
        explicitSite: String?,
        onlyOfficial: Boolean
    ): List<PlannedQuery> {
        val list = mutableListOf<PlannedQuery>()
        val prod = entities.product ?: cleanSearchTopic(rawQuery)
        val storage = entities.storage ?: ""
        val loc = location ?: "India"

        if (explicitSite != null) {
            list.add(
                PlannedQuery(
                    query = "site:$explicitSite $prod $storage price $currentDateLabel".trim(),
                    category = QueryCategory.SPECIFIC_SITE.name,
                    siteConstraint = explicitSite
                )
            )
            list.add(
                PlannedQuery(
                    query = "site:$explicitSite $prod $storage".trim(),
                    category = QueryCategory.SPECIFIC_SITE.name,
                    siteConstraint = explicitSite
                )
            )
            return list
        }

        when (intent) {
            WebSearchIntent.CURRENT_PRICE -> {
                // 1. Primary Freshness Query
                val primaryQ = "$prod $storage current price $loc $currentDateLabel".replace("  ", " ").trim()
                list.add(PlannedQuery(primaryQ, QueryCategory.PRIMARY.name))

                // 2. Official Domain Query
                if (entities.brand.equals("Apple", ignoreCase = true) || prod.lowercase().contains("iphone")) {
                    list.add(
                        PlannedQuery(
                            query = "site:apple.com/in $prod $storage price".trim(),
                            category = QueryCategory.OFFICIAL_DOMAIN.name,
                            siteConstraint = "apple.com/in"
                        )
                    )
                } else if (entities.brand.equals("Samsung", ignoreCase = true) || prod.lowercase().contains("galaxy")) {
                    list.add(
                        PlannedQuery(
                            query = "site:samsung.com/in $prod price".trim(),
                            category = QueryCategory.OFFICIAL_DOMAIN.name,
                            siteConstraint = "samsung.com/in"
                        )
                    )
                }

                // 3. Retailer Query (Flipkart/Amazon/Croma)
                if (!onlyOfficial) {
                    list.add(
                        PlannedQuery(
                            query = "$prod $storage price Flipkart Amazon $loc".replace("  ", " ").trim(),
                            category = QueryCategory.RETAILER.name
                        )
                    )
                }

                // 4. Verification Query
                list.add(
                    PlannedQuery(
                        query = "$prod $storage official price list $loc $currentDateLabel".replace("  ", " ").trim(),
                        category = QueryCategory.VERIFICATION.name
                    )
                )
            }

            WebSearchIntent.LATEST_NEWS, WebSearchIntent.NEWS -> {
                val lower = rawQuery.lowercase(Locale.ROOT)
                if (lower.contains("duniya") || lower.contains("world") || lower.contains("global")) {
                    list.add(PlannedQuery("world news major headlines $currentDateLabel", QueryCategory.PRIMARY.name))
                    list.add(PlannedQuery("global breaking news current developments $currentDateLabel", QueryCategory.FRESHNESS.name))
                } else {
                    val topic = if (entities.product != null) entities.product else cleanSearchTopic(rawQuery)
                    list.add(PlannedQuery("$topic latest news $currentDateLabel", QueryCategory.PRIMARY.name))
                    list.add(PlannedQuery("$topic breaking updates today $loc", QueryCategory.FRESHNESS.name))
                }
            }

            WebSearchIntent.IMAGE_SEARCH -> {
                list.add(PlannedQuery("$prod high resolution photos official", QueryCategory.PRIMARY.name))
            }

            WebSearchIntent.FACT_CHECK -> {
                val claim = cleanSearchTopic(rawQuery)
                list.add(PlannedQuery("$claim fact check official verification", QueryCategory.PRIMARY.name))
                list.add(PlannedQuery("$claim true or false proof", QueryCategory.VERIFICATION.name))
            }

            WebSearchIntent.PRODUCT_COMPARISON -> {
                list.add(PlannedQuery("$rawQuery comparison specs price $currentDateLabel", QueryCategory.PRIMARY.name))
            }

            WebSearchIntent.SPECIFICATIONS -> {
                list.add(PlannedQuery("$prod $storage official specifications features", QueryCategory.PRIMARY.name))
            }

            else -> {
                // General fallback
                list.add(PlannedQuery(cleanSearchTopic(rawQuery), QueryCategory.PRIMARY.name))
                if (location != null && !rawQuery.contains(location, ignoreCase = true)) {
                    list.add(PlannedQuery("${cleanSearchTopic(rawQuery)} $location", QueryCategory.PRIMARY.name))
                }
            }
        }

        return list.take(4)
    }

    private fun determinePreferredDomains(entities: ExtractedEntities, explicitSite: String?, onlyOfficial: Boolean): List<String> {
        if (explicitSite != null) return listOf(explicitSite)
        val domains = mutableListOf<String>()
        val prod = (entities.product ?: "").lowercase()
        if (prod.contains("iphone") || entities.brand == "Apple") {
            domains.add("apple.com/in")
            domains.add("apple.com")
        } else if (prod.contains("galaxy") || entities.brand == "Samsung") {
            domains.add("samsung.com/in")
            domains.add("samsung.com")
        }
        if (!onlyOfficial) {
            domains.add("flipkart.com")
            domains.add("amazon.in")
            domains.add("croma.com")
            domains.add("reliancedigital.in")
        }
        return domains
    }

    private fun cleanSearchTopic(input: String): String {
        return input.replace(Regex("""(?i)\b(abhi|internet par|dekh kar|batao|kya hai|kitne ka hai|please|search karo|dhoondo|tell me|find|lookup|show me)\b"""), "")
            .trim()
            .ifBlank { input }
    }

    private fun cleanNormalizedQuery(input: String): String {
        return input.replace(Regex("""[?!\r\n]"""), " ").trim()
    }
}

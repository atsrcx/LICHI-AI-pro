package com.lichiai.calling.contacts

class ContactIndex(
    private val contacts: List<ResolvedContact>,
    private val aliases: Map<String, String> = emptyMap()
) {
    private val exactNameMap = mutableMapOf<String, MutableList<ResolvedContact>>()
    private val tokenMap = mutableMapOf<String, MutableSet<ResolvedContact>>()
    private val phoneMap = mutableMapOf<String, MutableList<Pair<ResolvedContact, ContactPhoneNumber>>>()

    init {
        for (contact in contacts) {
            val normName = contact.normalizedName
            if (normName.isNotBlank()) {
                exactNameMap.getOrPut(normName) { mutableListOf() }.add(contact)
                val tokens = ContactNormalizer.extractSearchTokens(contact.displayName)
                for (token in tokens) {
                    tokenMap.getOrPut(token) { mutableSetOf() }.add(contact)
                }
            }

            for (phone in contact.phoneNumbers) {
                val normNum = phone.normalizedNumber
                if (normNum.isNotBlank()) {
                    phoneMap.getOrPut(normNum) { mutableListOf() }.add(contact to phone)
                    val digits = normNum.filter { it.isDigit() }
                    if (digits.length >= 10) {
                        phoneMap.getOrPut(digits.takeLast(10)) { mutableListOf() }.add(contact to phone)
                    }
                }
            }
        }
    }

    fun search(query: String): List<ContactCandidate> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return emptyList()

        val normalizedQuery = ContactNormalizer.normalize(trimmedQuery)
        val candidateScores = mutableMapOf<String, ContactCandidate>()

        // 1. Direct Phone Number check
        if (PhoneNumberNormalizer.isDirectPhoneNumber(trimmedQuery)) {
            val normQueryNum = PhoneNumberNormalizer.normalize(trimmedQuery)
            val digits = normQueryNum.filter { it.isDigit() }
            val matchedPhoneEntries = phoneMap[normQueryNum] ?: phoneMap[digits.takeLast(10)]
            if (matchedPhoneEntries != null) {
                for ((contact, phone) in matchedPhoneEntries) {
                    val candidate = ContactCandidate(
                        contact = contact,
                        matchedNumber = phone,
                        score = 1000,
                        matchReason = MatchReason.EXACT_PHONE,
                        matchedTerm = phone.rawNumber
                    )
                    candidateScores[contact.id] = candidate
                }
            }
        }

        // 2. User defined relation / alias resolution
        val aliasTargetName = aliases[normalizedQuery]
        if (aliasTargetName != null && aliasTargetName != normalizedQuery) {
            val aliasMatches = search(aliasTargetName)
            for (match in aliasMatches) {
                val boostedScore = (match.score * 0.95).toInt()
                val candidate = match.copy(
                    score = boostedScore,
                    matchReason = MatchReason.USER_ALIAS,
                    matchedTerm = "Alias: $normalizedQuery -> $aliasTargetName"
                )
                val existing = candidateScores[match.contact.id]
                if (existing == null || existing.score < boostedScore) {
                    candidateScores[match.contact.id] = candidate
                }
            }
        }

        // 3. Exact Normalized Name Match
        val exactMatches = exactNameMap[normalizedQuery]
        if (exactMatches != null) {
            for (contact in exactMatches) {
                val primaryPhone = contact.phoneNumbers.firstOrNull { it.isPrimary }
                    ?: contact.phoneNumbers.firstOrNull()
                val candidate = ContactCandidate(
                    contact = contact,
                    matchedNumber = primaryPhone,
                    score = 900,
                    matchReason = MatchReason.NORMALIZED_NAME_EXACT,
                    matchedTerm = contact.displayName
                )
                val existing = candidateScores[contact.id]
                if (existing == null || existing.score < 900) {
                    candidateScores[contact.id] = candidate
                }
            }
        }

        // 4. Token & Word Matches
        val queryTokens = ContactNormalizer.extractSearchTokens(trimmedQuery)
        for (qToken in queryTokens) {
            // Direct token hit
            val tokenContacts = tokenMap[qToken]
            if (tokenContacts != null) {
                for (contact in tokenContacts) {
                    val primaryPhone = contact.phoneNumbers.firstOrNull { it.isPrimary }
                        ?: contact.phoneNumbers.firstOrNull()
                    val isPrefix = contact.normalizedName.startsWith(qToken)
                    val score = if (isPrefix) 750 else 600
                    val reason = if (isPrefix) MatchReason.PREFIX_MATCH else MatchReason.WORD_MATCH

                    val candidate = ContactCandidate(
                        contact = contact,
                        matchedNumber = primaryPhone,
                        score = score,
                        matchReason = reason,
                        matchedTerm = qToken
                    )
                    val existing = candidateScores[contact.id]
                    if (existing == null || existing.score < score) {
                        candidateScores[contact.id] = candidate
                    }
                }
            }
        }

        // 5. Substring & Prefix across full contact list
        for (contact in contacts) {
            val cNorm = contact.normalizedName
            if (cNorm.startsWith(normalizedQuery) && normalizedQuery.length >= 3) {
                val primaryPhone = contact.phoneNumbers.firstOrNull { it.isPrimary }
                    ?: contact.phoneNumbers.firstOrNull()
                val candidate = ContactCandidate(
                    contact = contact,
                    matchedNumber = primaryPhone,
                    score = 700,
                    matchReason = MatchReason.PREFIX_MATCH,
                    matchedTerm = contact.displayName
                )
                val existing = candidateScores[contact.id]
                if (existing == null || existing.score < 700) {
                    candidateScores[contact.id] = candidate
                }
            } else if (cNorm.contains(normalizedQuery) && normalizedQuery.length >= 4) {
                val primaryPhone = contact.phoneNumbers.firstOrNull { it.isPrimary }
                    ?: contact.phoneNumbers.firstOrNull()
                val candidate = ContactCandidate(
                    contact = contact,
                    matchedNumber = primaryPhone,
                    score = 450,
                    matchReason = MatchReason.SUBSTRING_MATCH,
                    matchedTerm = contact.displayName
                )
                val existing = candidateScores[contact.id]
                if (existing == null || existing.score < 450) {
                    candidateScores[contact.id] = candidate
                }
            }
        }

        // 6. Controlled Fuzzy match for minor speech recognition errors (STT variations)
        if (candidateScores.isEmpty() && normalizedQuery.length >= 4) {
            for (contact in contacts) {
                val cTokens = ContactNormalizer.extractSearchTokens(contact.displayName)
                for (cToken in cTokens) {
                    if (ContactNormalizer.isFuzzyMatch(normalizedQuery, cToken)) {
                        val primaryPhone = contact.phoneNumbers.firstOrNull { it.isPrimary }
                            ?: contact.phoneNumbers.firstOrNull()
                        val candidate = ContactCandidate(
                            contact = contact,
                            matchedNumber = primaryPhone,
                            score = 350,
                            matchReason = MatchReason.FUZZY_NAME,
                            matchedTerm = contact.displayName
                        )
                        val existing = candidateScores[contact.id]
                        if (existing == null || existing.score < 350) {
                            candidateScores[contact.id] = candidate
                        }
                    }
                }
            }
        }

        return candidateScores.values.sortedByDescending { it.score }
    }
}

package com.lichiai.calling.contacts

import kotlinx.serialization.Serializable

@Serializable
data class ContactSource(
    val accountName: String? = null,
    val accountType: String? = null,
    val isSim: Boolean = false,
    val isCloud: Boolean = false,
    val isLocal: Boolean = false
)

@Serializable
data class ContactPhoneNumber(
    val rawNumber: String,
    val normalizedNumber: String,
    val type: Int = 0,
    val label: String? = null,
    val isPrimary: Boolean = false,
    val source: String? = null
)

@Serializable
data class ResolvedContact(
    val id: String,
    val aggregateContactId: Long? = null,
    val rawContactIds: List<Long> = emptyList(),
    val displayName: String,
    val normalizedName: String,
    val phoneNumbers: List<ContactPhoneNumber> = emptyList(),
    val aliases: List<String> = emptyList(),
    val accountSources: List<ContactSource> = emptyList(),
    val isSimContact: Boolean = false,
    val isLocalContact: Boolean = false,
    val isCloudContact: Boolean = false,
    val photoUri: String? = null,
    val lookupKey: String? = null
)

enum class MatchReason {
    EXACT_NAME,
    EXACT_PHONE,
    USER_ALIAS,
    NORMALIZED_NAME_EXACT,
    PREFIX_MATCH,
    WORD_MATCH,
    SUBSTRING_MATCH,
    RELATION_MATCH,
    FUZZY_NAME
}

data class ContactCandidate(
    val contact: ResolvedContact,
    val matchedNumber: ContactPhoneNumber?,
    val score: Int,
    val matchReason: MatchReason,
    val matchedTerm: String
)

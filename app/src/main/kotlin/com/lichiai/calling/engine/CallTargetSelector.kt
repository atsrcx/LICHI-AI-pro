package com.lichiai.calling.engine

import android.provider.ContactsContract
import com.lichiai.calling.contacts.ContactCandidate
import com.lichiai.calling.contacts.ContactPhoneNumber
import com.lichiai.calling.contacts.PhoneNumberNormalizer
import com.lichiai.calling.intent.CallResult
import com.lichiai.calling.intent.CallResultStatus

sealed class TargetSelectionOutcome {
    data class Selected(
        val contactName: String,
        val phoneNumber: ContactPhoneNumber,
        val candidate: ContactCandidate
    ) : TargetSelectionOutcome()

    data class AmbiguousContacts(
        val query: String,
        val candidates: List<ContactCandidate>,
        val message: String
    ) : TargetSelectionOutcome()

    data class AmbiguousNumbers(
        val contactName: String,
        val phoneNumbers: List<ContactPhoneNumber>,
        val message: String
    ) : TargetSelectionOutcome()

    data class NotFound(
        val query: String,
        val message: String
    ) : TargetSelectionOutcome()

    data class NoPhoneNumber(
        val contactName: String,
        val message: String
    ) : TargetSelectionOutcome()
}

class CallTargetSelector {

    fun selectTarget(query: String, candidates: List<ContactCandidate>): TargetSelectionOutcome {
        if (candidates.isEmpty()) {
            return TargetSelectionOutcome.NotFound(
                query = query,
                message = "I couldn't find \"$query\" in your contacts."
            )
        }

        val topCandidate = candidates.first()
        val secondCandidate = candidates.getOrNull(1)

        // If top candidate has a clear lead or is the only candidate
        val isClearWinner = candidates.size == 1 ||
                (secondCandidate != null && (topCandidate.score - secondCandidate.score >= 150)) ||
                (topCandidate.score >= 900 && (secondCandidate?.score ?: 0) < 900)

        if (!isClearWinner && secondCandidate != null) {
            // Filter top candidates with similar names
            val closeCandidates = candidates.take(3).filter { topCandidate.score - it.score < 150 }
            val namesList = closeCandidates.map { it.contact.displayName }
            val formattedNames = namesList.joinToString(" or ")
            return TargetSelectionOutcome.AmbiguousContacts(
                query = query,
                candidates = closeCandidates,
                message = "I found multiple contacts matching \"$query\": $formattedNames. Which one should I call?"
            )
        }

        // We have an identified contact
        val contact = topCandidate.contact
        val phones = contact.phoneNumbers

        if (phones.isEmpty()) {
            return TargetSelectionOutcome.NoPhoneNumber(
                contactName = contact.displayName,
                message = "${contact.displayName} doesn't have a phone number saved."
            )
        }

        if (phones.size == 1) {
            return TargetSelectionOutcome.Selected(
                contactName = contact.displayName,
                phoneNumber = phones.first(),
                candidate = topCandidate
            )
        }

        // Multiple numbers for this contact
        // If one is matched directly by phone search
        if (topCandidate.matchedNumber != null) {
            return TargetSelectionOutcome.Selected(
                contactName = contact.displayName,
                phoneNumber = topCandidate.matchedNumber,
                candidate = topCandidate
            )
        }

        // Priority: Primary -> Mobile -> First
        val primaryPhone = phones.firstOrNull { it.isPrimary }
        val mobilePhone = phones.firstOrNull { it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE }

        val bestPhone = primaryPhone ?: mobilePhone ?: phones.first()

        // If user didn't specify and there are multiple significantly different numbers, we can use bestPhone or prompt
        return TargetSelectionOutcome.Selected(
            contactName = contact.displayName,
            phoneNumber = bestPhone,
            candidate = topCandidate
        )
    }
}

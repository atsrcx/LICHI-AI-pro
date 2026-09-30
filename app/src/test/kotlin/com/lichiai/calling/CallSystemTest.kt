package com.lichiai.calling

import com.lichiai.calling.contacts.ContactCandidate
import com.lichiai.calling.contacts.ContactNormalizer
import com.lichiai.calling.contacts.ContactPhoneNumber
import com.lichiai.calling.contacts.MatchReason
import com.lichiai.calling.contacts.PhoneNumberNormalizer
import com.lichiai.calling.contacts.ResolvedContact
import com.lichiai.calling.engine.CallTargetSelector
import com.lichiai.calling.engine.TargetSelectionOutcome
import com.lichiai.calling.intent.CallActionIntentResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSystemTest {

    private val selector = CallTargetSelector()

    @Test
    fun testContactNormalizerAndFuzzyMatching() {
        val dist1 = ContactNormalizer.levenshteinDistance("rahul", "rahul")
        assertEquals(0, dist1)

        val normPhone = PhoneNumberNormalizer.normalize("+91 (987) 654-3210")
        assertEquals("+919876543210", normPhone)
        assertTrue(PhoneNumberNormalizer.isDirectPhoneNumber(normPhone))
    }

    @Test
    fun testTargetSelectorSingleMatch() {
        val phone = ContactPhoneNumber(rawNumber = "9876543210", normalizedNumber = "+919876543210", isPrimary = true)
        val candidate = ContactCandidate(
            contact = ResolvedContact(
                id = "1",
                displayName = "Rahul Sharma",
                normalizedName = "rahul sharma",
                phoneNumbers = listOf(phone)
            ),
            matchedNumber = phone,
            score = 900,
            matchReason = MatchReason.EXACT_NAME,
            matchedTerm = "Rahul"
        )

        val outcome = selector.selectTarget("Rahul", listOf(candidate))
        assertTrue(outcome is TargetSelectionOutcome.Selected)
        val selected = outcome as TargetSelectionOutcome.Selected
        assertEquals("Rahul Sharma", selected.contactName)
        assertEquals("9876543210", selected.phoneNumber.rawNumber)
    }

    @Test
    fun testCallDecisionIntentResolution() {
        val actionResolver = CallActionIntentResolver()
        val ringingSession = com.lichiai.calling.state.CallSessionInfo(
            callState = com.lichiai.calling.state.CallState.INCOMING_KNOWN,
            callerName = "Rahul Sharma",
            callerNumber = "+919876543210",
            decisionQuestion = "Rahul Sharma ka call aa raha hai. Uthaun ya reject karun?",
            isWaitingForDecision = true
        )

        // 1. Answer commands
        val ans1 = actionResolver.resolve("utha lo", ringingSession)
        assertTrue(ans1 is com.lichiai.calling.action.StructuredCallAction.AnswerCall)
        assertEquals(false, (ans1 as com.lichiai.calling.action.StructuredCallAction.AnswerCall).enableSpeaker)

        val ansSpeaker = actionResolver.resolve("speaker pe uthao", ringingSession)
        assertTrue(ansSpeaker is com.lichiai.calling.action.StructuredCallAction.AnswerCall)
        assertEquals(true, (ansSpeaker as com.lichiai.calling.action.StructuredCallAction.AnswerCall).enableSpeaker)

        val ansHaan = actionResolver.resolve("haan", ringingSession)
        assertTrue(ansHaan is com.lichiai.calling.action.StructuredCallAction.AnswerCall)

        // 2. Reject commands
        val rej1 = actionResolver.resolve("kaat do", ringingSession)
        assertTrue(rej1 is com.lichiai.calling.action.StructuredCallAction.RejectCall)

        val rej2 = actionResolver.resolve("reject kar do", ringingSession)
        assertTrue(rej2 is com.lichiai.calling.action.StructuredCallAction.RejectCall)

        // 3. Ambiguous Negative clarification
        val neg = actionResolver.resolve("nahi", ringingSession)
        assertTrue(neg is com.lichiai.calling.action.StructuredCallAction.ClarifyNegativeDecision)

        // 4. Queries
        val queryCaller = actionResolver.resolve("kaun hai?", ringingSession)
        assertTrue(queryCaller is com.lichiai.calling.action.StructuredCallAction.QueryCallerIdentity)

        val queryNumber = actionResolver.resolve("number kya hai?", ringingSession)
        assertTrue(queryNumber is com.lichiai.calling.action.StructuredCallAction.QueryCallerNumber)

        // 5. Cancel
        val cancel = actionResolver.resolve("cancel", ringingSession)
        assertTrue(cancel is com.lichiai.calling.action.StructuredCallAction.CancelDecision)
    }
}

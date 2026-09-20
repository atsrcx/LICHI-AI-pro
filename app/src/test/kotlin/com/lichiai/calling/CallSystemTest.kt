package com.lichiai.calling

import com.lichiai.calling.contacts.ContactCandidate
import com.lichiai.calling.contacts.ContactNormalizer
import com.lichiai.calling.contacts.ContactPhoneNumber
import com.lichiai.calling.contacts.MatchReason
import com.lichiai.calling.contacts.PhoneNumberNormalizer
import com.lichiai.calling.contacts.ResolvedContact
import com.lichiai.calling.engine.CallTargetSelector
import com.lichiai.calling.engine.TargetSelectionOutcome
import com.lichiai.calling.intent.CallAction
import com.lichiai.calling.intent.CallIntentResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSystemTest {

    private val resolver = CallIntentResolver()
    private val selector = CallTargetSelector()

    @Test
    fun testEnglishCallPhrases() {
        val intent1 = resolver.resolve("Call Rahul")
        assertEquals(CallAction.CALL_CONTACT, intent1.action)
        assertEquals("Rahul", intent1.targetText)

        val intent2 = resolver.resolve("Please call Rahul")
        assertEquals(CallAction.CALL_CONTACT, intent2.action)
        assertEquals("Rahul", intent2.targetText)

        val intent3 = resolver.resolve("Call my brother")
        assertEquals(CallAction.CALL_CONTACT, intent3.action)
        assertEquals("brother", intent3.targetText)

        val intent4 = resolver.resolve("Call mom")
        assertEquals(CallAction.CALL_CONTACT, intent4.action)
        assertEquals("mom", intent4.targetText)
    }

    @Test
    fun testHindiAndHinglishCallPhrases() {
        val intent1 = resolver.resolve("Rahul ko call lagao")
        assertEquals(CallAction.CALL_CONTACT, intent1.action)
        assertEquals("Rahul", intent1.targetText)

        val intent2 = resolver.resolve("Rahul ko phone karo")
        assertEquals(CallAction.CALL_CONTACT, intent2.action)
        assertEquals("Rahul", intent2.targetText)

        val intent3 = resolver.resolve("Rahul ko call karo")
        assertEquals(CallAction.CALL_CONTACT, intent3.action)
        assertEquals("Rahul", intent3.targetText)

        val intent4 = resolver.resolve("Bhaiya ko call lagao")
        assertEquals(CallAction.CALL_CONTACT, intent4.action)
        assertEquals("Bhaiya", intent4.targetText)

        val intent5 = resolver.resolve("Mummy ko phone karo")
        assertEquals(CallAction.CALL_CONTACT, intent5.action)
        assertEquals("Mummy", intent5.targetText)

        val intent6 = resolver.resolve("Papa ko call karo")
        assertEquals(CallAction.CALL_CONTACT, intent6.action)
        assertEquals("Papa", intent6.targetText)

        val intent7 = resolver.resolve("Rahul ko phone mila do")
        assertEquals(CallAction.CALL_CONTACT, intent7.action)
        assertEquals("Rahul", intent7.targetText)
    }

    @Test
    fun testDirectPhoneNumberCalling() {
        val intent1 = resolver.resolve("Call 9876543210")
        assertEquals(CallAction.CALL_NUMBER, intent1.action)
        assertEquals("9876543210", intent1.phoneNumber)

        val intent2 = resolver.resolve("Dial +91 98765 43210")
        assertEquals(CallAction.CALL_NUMBER, intent2.action)
        assertNotNull(intent2.phoneNumber)

        val intent3 = resolver.resolve("Is number par call karo 9876543210")
        assertEquals(CallAction.CALL_NUMBER, intent3.action)
        assertEquals("9876543210", intent3.phoneNumber)
    }

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
}

package com.lichiai.context

import com.lichiai.context.engine.UniversalContextContinuityEngine
import com.lichiai.context.model.EntityType
import com.lichiai.context.model.SalienceLevel
import com.lichiai.intent.context.ContextBuilder
import com.lichiai.intent.model.BrowserActionType
import com.lichiai.intent.model.IntentContext
import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.ResolvedIntent
import com.lichiai.intent.router.ReferenceAndCorrectionResolver
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.model.PlatformProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UniversalContextContinuityTest {

    private lateinit var engine: UniversalContextContinuityEngine

    @Before
    fun setUp() {
        engine = UniversalContextContinuityEngine.getInstance()
        engine.clearContext("test_conv_1")
        engine.clearContext("test_conv_2")
    }

    @Test
    fun testCriticalRealDeviceTest_AxeelDubinFollowUp() {
        val convId = "test_conv_1"

        // Step 1: #Spy Instagram @axeel_dubin ka profile batao completes
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            displayName = "Axel Ariel Dubin",
            followers = "283.6K",
            following = "273",
            postCount = "4.3K",
            bio = "Tech Innovator, AI Architect & Builder. Contact: business@axeeldubin.com",
            website = "https://axeeldubin.com",
            publicEmail = "business@axeeldubin.com",
            publicPhone = "+12025550199",
            isVerified = true
        )

        engine.recordSpyExecution(
            conversationId = convId,
            primaryProfile = profile,
            allProfiles = listOf(profile),
            userGoal = "#Spy Instagram @axeel_dubin ka profile batao",
            assistantResponse = "Found Instagram profile for @axeel_dubin: 283.6K followers, bio: Tech Innovator"
        )

        // Step 2: User says: "Achcha ab is account ki specific cheezein batao"
        val query = "Achcha ab is account ki specific cheezein batao"
        val context = IntentContext(
            conversationId = convId,
            lastPlatformProfile = profile,
            recentProfiles = listOf(profile)
        )

        val resolved = ReferenceAndCorrectionResolver.resolve(query, context)
        assertNotNull("Expected resolved intent for follow-up query", resolved)
        assertTrue("Expected ContextualQuestion instead of re-scraping Apify", resolved!!.first is ResolvedIntent.ContextualQuestion)

        val q = resolved.first as ResolvedIntent.ContextualQuestion
        assertNotNull("Reference context must not be null", q.referenceContext)
        assertTrue(q.referenceContext!!.contains("axeel_dubin"))
        assertTrue(q.referenceContext!!.contains("283.6K"))
        assertTrue(q.referenceContext!!.contains("business@axeeldubin.com"))
    }

    @Test
    fun testProfileAttributeInquiries_FollowersBioEmailPhone() {
        val convId = "test_conv_1"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            displayName = "Axel Ariel Dubin",
            followers = "283.6K",
            bio = "Tech Innovator & Architect",
            website = "https://axeeldubin.com",
            publicEmail = "business@axeeldubin.com",
            publicPhone = "+12025550199"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "Lookup profile", "Done")

        val intentCtx = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        // Test "Iske followers kitne hain?"
        val resFollowers = ReferenceAndCorrectionResolver.resolve("Iske followers kitne hain?", intentCtx)
        assertNotNull(resFollowers)
        assertTrue(resFollowers!!.first is ResolvedIntent.ContextualQuestion)
        assertTrue((resFollowers.first as ResolvedIntent.ContextualQuestion).referenceContext!!.contains("283.6K"))

        // Test "Iski bio explain karo"
        val resBio = ReferenceAndCorrectionResolver.resolve("Iski bio explain karo", intentCtx)
        assertNotNull(resBio)
        assertTrue(resBio!!.first is ResolvedIntent.ContextualQuestion)
        assertTrue((resBio.first as ResolvedIntent.ContextualQuestion).referenceContext!!.contains("Tech Innovator"))

        // Test "Iska public email batao"
        val resEmail = ReferenceAndCorrectionResolver.resolve("Iska public email batao", intentCtx)
        assertNotNull(resEmail)
        assertTrue(resEmail!!.first is ResolvedIntent.ContextualQuestion)
        assertTrue((resEmail.first as ResolvedIntent.ContextualQuestion).referenceContext!!.contains("business@axeeldubin.com"))

        // Test "Iska public phone number batao"
        val resPhone = ReferenceAndCorrectionResolver.resolve("Iska public phone number batao", intentCtx)
        assertNotNull(resPhone)
        assertTrue(resPhone!!.first is ResolvedIntent.ContextualQuestion)
        assertTrue((resPhone.first as ResolvedIntent.ContextualQuestion).referenceContext!!.contains("+12025550199"))
    }

    @Test
    fun testCrossCapabilityTransitions_WebsiteAndBrowser() {
        val convId = "test_conv_1"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            displayName = "Axel Ariel Dubin",
            website = "https://axeeldubin.com"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "Lookup profile", "Done")
        val intentCtx = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        // "Iski website analyze karo" -> transitions to WebSearchTask with website URL
        val resWeb = ReferenceAndCorrectionResolver.resolve("Iski website analyze karo", intentCtx)
        assertNotNull(resWeb)
        assertTrue(resWeb!!.first is ResolvedIntent.WebSearchTask)
        val webTask = resWeb.first as ResolvedIntent.WebSearchTask
        assertTrue(webTask.query.contains("https://axeeldubin.com"))

        // "Ab browser mein website kholo" -> transitions to BrowserTask
        val resBrowser = ReferenceAndCorrectionResolver.resolve("Ab browser mein website kholo", intentCtx)
        assertNotNull(resBrowser)
        assertTrue(resBrowser!!.first is ResolvedIntent.BrowserTask)
        val browserTask = resBrowser.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.NAVIGATE, browserTask.action)
        assertEquals("https://axeeldubin.com", browserTask.url)
    }

    @Test
    fun testHardSafetyBoundary_PhoneLookupNeverRoutesToCalls() {
        val convId = "test_conv_1"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            publicPhone = "+919876543210"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "Lookup profile", "Done")
        val intentCtx = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        // "Is number ka information batao" -> Must NOT route to CallTask!
        val resNumberInfo = ReferenceAndCorrectionResolver.resolve("Is number ka information batao", intentCtx)
        assertNotNull(resNumberInfo)
        assertFalse("Safety boundary violation: Public phone info lookup must NEVER route to CallTask!",
            resNumberInfo!!.first is ResolvedIntent.CallTask
        )

        // Explicit call intent "Rahul ko call karo" -> CAN route to CallTask
        val resCall = ReferenceAndCorrectionResolver.resolve("Nahi Rahul ko call karo", intentCtx)
        assertNotNull(resCall)
        assertTrue(resCall!!.first is ResolvedIntent.CallTask)
    }

    @Test
    fun testMultiEntityDisambiguationAndCorrection() {
        val convId = "test_conv_1"
        val instaProfile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            displayName = "Axel Ariel Dubin"
        )
        val ytProfile = PlatformProfile(
            platform = PlatformType.YOUTUBE,
            username = "mkbhd",
            displayName = "Marques Brownlee",
            subscriberCount = "19M"
        )

        engine.recordSpyExecution(convId, instaProfile, listOf(instaProfile, ytProfile), "Lookup", "Done")
        val intentCtx = IntentContext(
            conversationId = convId,
            lastPlatformProfile = instaProfile,
            recentProfiles = listOf(instaProfile, ytProfile)
        )

        // Specific platform qualifier: "YouTube wala" -> resolves to MKBHD
        val resYt = ReferenceAndCorrectionResolver.resolve("YouTube wale ke subscribers batao", intentCtx)
        assertNotNull(resYt)
        assertTrue(resYt!!.first is ResolvedIntent.ContextualQuestion)
        assertTrue((resYt.first as ResolvedIntent.ContextualQuestion).referenceContext!!.contains("mkbhd"))

        // Ordinal: "Doosra wala" -> resolves to MKBHD
        val resSecond = ReferenceAndCorrectionResolver.resolve("Doosre wale ka detail batao", intentCtx)
        assertNotNull(resSecond)
        assertTrue(resSecond!!.first is ResolvedIntent.ContextualQuestion)
        assertTrue((resSecond.first as ResolvedIntent.ContextualQuestion).referenceContext!!.contains("mkbhd"))

        // User correction: "Nahi, Instagram wale ki baat kar raha hoon" -> updates to Axeel Dubin
        val resCorrection = ReferenceAndCorrectionResolver.resolve("Nahi, Instagram wale ki baat kar raha hoon", intentCtx)
        assertNotNull(resCorrection)
        assertTrue(resCorrection!!.first is ResolvedIntent.ContextualQuestion)
        assertTrue((resCorrection.first as ResolvedIntent.ContextualQuestion).referenceContext!!.contains("axeel_dubin"))
    }

    @Test
    fun testConversationIsolation_ZeroBleedBetweenConversations() {
        val convA = "test_conv_1"
        val convB = "test_conv_2"

        val profileA = PlatformProfile(platform = PlatformType.INSTAGRAM, username = "alice")
        val profileB = PlatformProfile(platform = PlatformType.INSTAGRAM, username = "bob")

        engine.recordSpyExecution(convA, profileA, listOf(profileA), "User A", "Done")
        engine.recordSpyExecution(convB, profileB, listOf(profileB), "User B", "Done")

        val resA = engine.resolveReference("iska profile batao", convA)
        val resB = engine.resolveReference("iska profile batao", convB)

        assertEquals("alice", resA.resolvedEntity?.name)
        assertEquals("bob", resB.resolvedEntity?.name)
    }

    @Test
    fun testContextPersistenceAndReconstruction() {
        val convId = "test_conv_1"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            displayName = "Axel Ariel Dubin",
            followers = "283.6K",
            bio = "Architect"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "Lookup", "Done")

        // Export
        val jsonExport = engine.exportContext(convId)
        assertTrue(jsonExport.contains("axeel_dubin"))

        // Clear in-memory
        engine.clearContext(convId)
        val emptyRes = engine.getContext(convId)
        assertTrue(emptyRes.entities.isEmpty())

        // Import / restore
        engine.importContext(convId, jsonExport)
        val restoredCtx = engine.getContext(convId)
        assertEquals(1, restoredCtx.getProfiles().size)
        assertEquals("axeel_dubin", restoredCtx.getProfiles().first().name)

        val resolution = engine.resolveReference("is account ke followers batao", convId)
        assertEquals("axeel_dubin", resolution.resolvedEntity?.name)
    }

    @Test
    fun testEnglishHindiHinglishReferenceVariations() {
        val convId = "test_conv_1"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "tech_guru",
            displayName = "Tech Guru",
            followers = "500K"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "Lookup", "Done")
        val intentCtx = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        // English: "tell me this account's followers"
        val resEn = ReferenceAndCorrectionResolver.resolve("tell me this account's followers", intentCtx)
        assertNotNull(resEn)
        assertTrue(resEn!!.first is ResolvedIntent.ContextualQuestion)

        // Hindi / Hinglish: "is bande ke followers kitne hain"
        val resHinglish1 = ReferenceAndCorrectionResolver.resolve("is bande ke followers kitne hain", intentCtx)
        assertNotNull(resHinglish1)
        assertTrue(resHinglish1!!.first is ResolvedIntent.ContextualQuestion)

        // Hindi / Hinglish: "uska bio batao"
        val resHinglish2 = ReferenceAndCorrectionResolver.resolve("uska bio batao", intentCtx)
        assertNotNull(resHinglish2)
        assertTrue(resHinglish2!!.first is ResolvedIntent.ContextualQuestion)

        // Hindi / Hinglish: "yeh kaun hai"
        val resHinglish3 = ReferenceAndCorrectionResolver.resolve("yeh kaun hai", intentCtx)
        assertNotNull(resHinglish3)
        assertTrue(resHinglish3!!.first is ResolvedIntent.ContextualQuestion)
    }
}

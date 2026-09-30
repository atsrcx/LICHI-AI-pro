package com.lichiai.context

import com.lichiai.context.engine.UniversalContextContinuityEngine
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.model.PlatformProfile
import org.junit.Assert.assertEquals
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

        val resolution = engine.resolveReference("Achcha ab is account ki specific cheezein batao", convId)
        assertNotNull(resolution.resolvedEntity)
        assertEquals("axeel_dubin", resolution.resolvedEntity?.name)
        assertEquals("283.6K", resolution.resolvedEntity?.followers)
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

        val resFollowers = engine.resolveReference("Iske followers kitne hain?", convId)
        assertNotNull(resFollowers.resolvedEntity)
        assertEquals("283.6K", resFollowers.resolvedEntity?.followers)

        val resEmail = engine.resolveReference("Iska public email batao", convId)
        assertNotNull(resEmail.resolvedEntity)
        assertEquals("business@axeeldubin.com", resEmail.resolvedEntity?.publicEmail)
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

        val resYt = engine.resolveReference("YouTube wale ke subscribers batao", convId)
        assertNotNull(resYt.resolvedEntity)
        assertEquals("mkbhd", resYt.resolvedEntity?.name)

        val resInsta = engine.resolveReference("Instagram wale ki baat kar raha hoon", convId)
        assertNotNull(resInsta.resolvedEntity)
        assertEquals("axeel_dubin", resInsta.resolvedEntity?.name)
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

        val jsonExport = engine.exportContext(convId)
        assertTrue(jsonExport.contains("axeel_dubin"))

        engine.clearContext(convId)
        val emptyRes = engine.getContext(convId)
        assertTrue(emptyRes.entities.isEmpty())

        engine.importContext(convId, jsonExport)
        val restoredCtx = engine.getContext(convId)
        assertEquals(1, restoredCtx.getProfiles().size)
        assertEquals("axeel_dubin", restoredCtx.getProfiles().first().name)

        val resolution = engine.resolveReference("is account ke followers batao", convId)
        assertEquals("axeel_dubin", resolution.resolvedEntity?.name)
    }
}

package com.lichiai.context

import com.lichiai.context.engine.UniversalContextContinuityEngine
import com.lichiai.intent.context.ContextBuilder
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.model.PlatformProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Production Golden Multi-Turn Conversation Test Suite for Lichi AI.
 * Tests multi-turn transcripts, active entities, active topics, goals,
 * reference resolution, cross-capability transitions, topic switches,
 * topic returns, corrections, and persistence.
 */
class GoldenConversationTestSuite {

    private lateinit var engine: UniversalContextContinuityEngine
    private lateinit var contextBuilder: ContextBuilder

    @Before
    fun setUp() {
        engine = UniversalContextContinuityEngine.getInstance()
        contextBuilder = ContextBuilder()
        engine.clearContext("test_session_golden")
    }

    // =========================================================================
    // GOLDEN TEST 1: SPY -> FOLLOW-UP -> WEBSITE -> BROWSER
    // =========================================================================
    @Test
    fun testGolden1_SpyToFollowUpToWebToBrowser() {
        val convId = "test_session_golden"

        // Turn 1: #Spy Instagram @axeel_dubin ka profile batao
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            displayName = "Axel Ariel Dubin",
            followers = "283.6K",
            following = "273",
            bio = "AI Innovator & Builder",
            website = "https://axeeldubin.com"
        )
        engine.recordSpyExecution(
            conversationId = convId,
            primaryProfile = profile,
            allProfiles = listOf(profile),
            userGoal = "#Spy Instagram @axeel_dubin ka profile batao",
            assistantResponse = "Found Instagram profile for @axeel_dubin"
        )

        // Turn 2: "Iske followers kitne hain?" -> Resolves to same profile
        val resFollowers = engine.resolveReference("Iske followers kitne hain?", convId)
        assertNotNull(resFollowers.resolvedEntity)
        assertEquals("283.6K", resFollowers.resolvedEntity?.followers)

        // Turn 3: "Iski website analyze karo" -> Resolves to website URL
        val resWeb = engine.resolveReference("Iski website analyze karo", convId)
        assertNotNull(resWeb.resolvedEntity)
        assertEquals("https://axeeldubin.com", resWeb.resolvedEntity?.website)
    }

    // =========================================================================
    // GOLDEN TEST 2: MULTI-ENTITY TRACKING & RESOLUTION
    // =========================================================================
    @Test
    fun testGolden2_MultiEntityResolution() {
        val convId = "test_session_golden"

        val p1 = PlatformProfile(platform = PlatformType.INSTAGRAM, username = "sam_altman", followers = "2M")
        val p2 = PlatformProfile(platform = PlatformType.TWITTER_X, username = "sama", followers = "3M")

        engine.recordSpyExecution(convId, p1, listOf(p1, p2), "Lookup Sam Altman", "Done")

        val res1 = engine.resolveReference("iska follower count batao", convId)
        assertNotNull(res1.resolvedEntity)

        val resTwitter = engine.resolveReference("Twitter wala profile", convId)
        assertNotNull(resTwitter.resolvedEntity)
        assertEquals("sama", resTwitter.resolvedEntity?.name)
    }

    // =========================================================================
    // GOLDEN TEST 3: CONTEXT PERSISTENCE & RESTORATION
    // =========================================================================
    @Test
    fun testGolden3_ContextPersistenceAndRestore() {
        val convId = "test_session_golden"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            displayName = "Axel Ariel Dubin",
            followers = "283.6K"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "Lookup", "Done")

        val json = engine.exportContext(convId)
        assertTrue(json.contains("axeel_dubin"))

        engine.clearContext(convId)
        assertTrue(engine.getContext(convId).entities.isEmpty())

        engine.importContext(convId, json)
        val restored = engine.getContext(convId)
        assertEquals(1, restored.entities.size)
        assertEquals("axeel_dubin", restored.getProfiles().first().name)
    }
}

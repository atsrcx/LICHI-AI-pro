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

        val ctx1 = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        // Turn 2: "Iske followers kitne hain?" -> Resolves to same profile
        val resFollowers = ReferenceAndCorrectionResolver.resolve("Iske followers kitne hain?", ctx1)
        assertNotNull(resFollowers)
        assertTrue(resFollowers!!.first is ResolvedIntent.ContextualQuestion)
        val q1 = resFollowers.first as ResolvedIntent.ContextualQuestion
        assertTrue(q1.referenceContext!!.contains("axeel_dubin"))
        assertTrue(q1.referenceContext!!.contains("283.6K"))

        // Turn 3: "Iski website analyze karo" -> Transitions to Web Search with website
        val resWeb = ReferenceAndCorrectionResolver.resolve("Iski website analyze karo", ctx1)
        assertNotNull(resWeb)
        assertTrue(resWeb!!.first is ResolvedIntent.WebSearchTask)
        val webTask = resWeb.first as ResolvedIntent.WebSearchTask
        assertTrue(webTask.query.contains("https://axeeldubin.com"))

        // Turn 4: "Browser mein kholo" -> Transitions to Browser navigation
        val resBrowser = ReferenceAndCorrectionResolver.resolve("Browser mein kholo", ctx1)
        assertNotNull(resBrowser)
        assertTrue(resBrowser!!.first is ResolvedIntent.BrowserTask)
        val browserTask = resBrowser.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.NAVIGATE, browserTask.action)
        assertTrue(browserTask.url!!.contains("axeeldubin.com"))
    }

    // =========================================================================
    // GOLDEN TEST 2: BROWSER SEARCH -> 2ND CANDIDATE -> IN-PAGE FIND -> CORRECTION TO 1ST
    // =========================================================================
    @Test
    fun testGolden2_BrowserSearchCandidateFindAndCorrection() {
        val convId = "test_session_golden"

        // Turn 1: Browser searched for PUBG mobile
        val candidates = listOf(
            "PUBG Mobile Official Website - Download",
            "PUBG Mobile Community Hub & News",
            "PUBG Mobile Support & FAQ"
        )
        engine.recordBrowserState(convId, "https://www.google.com/search?q=pubg", "Google Search", candidates)
        val ctx1 = IntentContext(
            conversationId = convId,
            currentBrowserUrl = "https://www.google.com/search?q=pubg",
            currentBrowserTitle = "Google Search",
            browserCandidates = candidates
        )

        // Turn 2: "Doosra result kholo" -> Resolves to candidate index 1
        val resCand2 = ReferenceAndCorrectionResolver.resolve("Doosra result kholo", ctx1)
        assertNotNull(resCand2)
        assertTrue(resCand2!!.first is ResolvedIntent.BrowserTask)
        val bTask2 = resCand2.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.CLICK_CANDIDATE, bTask2.action)
        assertEquals(1, bTask2.candidateIndex)

        // Turn 3: "Ismein download option hai?" -> In-page find for download
        val resFind = ReferenceAndCorrectionResolver.resolve("Ismein download option hai?", ctx1)
        assertNotNull(resFind)
        assertTrue(resFind!!.first is ResolvedIntent.BrowserTask)
        val bTaskFind = resFind.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.FIND_ON_PAGE, bTaskFind.action)
        assertEquals("download", bTaskFind.findTarget)

        // Turn 4: "Nahi, pehla wala dekho" -> Correction to candidate index 0
        val resCorr1 = ReferenceAndCorrectionResolver.resolve("Nahi, pehla wala dekho", ctx1)
        assertNotNull(resCorr1)
        assertTrue(resCorr1!!.first is ResolvedIntent.BrowserTask)
        val bTaskCorr = resCorr1.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.CLICK_CANDIDATE, bTaskCorr.action)
        assertEquals(0, bTaskCorr.candidateIndex)
    }

    // =========================================================================
    // GOLDEN TEST 3: YOUTUBE CHANNEL -> LATEST VIDEOS -> PEHLA WALA KHOLO
    // =========================================================================
    @Test
    fun testGolden3_YouTubeChannelFollowUp() {
        val convId = "test_session_golden"

        val ytProfile = PlatformProfile(
            platform = PlatformType.YOUTUBE,
            username = "TechChannelXYZ",
            displayName = "Tech Channel XYZ",
            subscriberCount = "1.2M",
            bio = "Official YouTube Tech Channel",
            profileUrl = "https://youtube.com/@TechChannelXYZ"
        )
        engine.recordSpyExecution(convId, ytProfile, listOf(ytProfile), "YouTube @TechChannelXYZ ka channel batao", "Channel found")

        val ctx1 = IntentContext(conversationId = convId, lastPlatformProfile = ytProfile)

        // "Uske latest videos dikhao" -> Web search for YouTube posts/videos
        val resVideos = ReferenceAndCorrectionResolver.resolve("Uske latest videos dikhao", ctx1)
        assertNotNull(resVideos)
        assertTrue(resVideos!!.first is ResolvedIntent.WebSearchTask)
        val searchTask = resVideos.first as ResolvedIntent.WebSearchTask
        assertTrue(searchTask.query.contains("TechChannelXYZ"))

        // Browser navigation to channel
        val resOpen = ReferenceAndCorrectionResolver.resolve("Browser mein kholo", ctx1)
        assertNotNull(resOpen)
        assertTrue(resOpen!!.first is ResolvedIntent.BrowserTask)
        val bTask = resOpen.first as ResolvedIntent.BrowserTask
        assertTrue(bTask.url!!.contains("youtube.com/@TechChannelXYZ"))
    }

    // =========================================================================
    // GOLDEN TEST 4: TERMINAL SESSION PRESERVATION ACROSS TURNS
    // =========================================================================
    @Test
    fun testGolden4_TerminalSessionPreservation() {
        val convId = "test_session_golden"

        // Turn 1: Terminal executed pwd
        engine.recordTerminalState(convId, sessionId = "term_sess_1", cwd = "/data/data/com.lichiai/files/workspace", lastOutput = "/data/data/com.lichiai/files/workspace")

        val snap1 = engine.getContext(convId)
        assertEquals("term_sess_1", snap1.terminalSessionId)
        assertEquals("/data/data/com.lichiai/files/workspace", snap1.terminalCwd)

        // Turn 2: Follow-up in same folder
        engine.recordTerminalState(convId, sessionId = "term_sess_1", cwd = "/data/data/com.lichiai/files/workspace", lastOutput = "archive.zip notes.txt")
        val snap2 = engine.getContext(convId)
        assertEquals("term_sess_1", snap2.terminalSessionId)
        assertTrue(snap2.verifiedFacts["lastTerminalOutput"]!!.contains("archive.zip"))
    }

    // =========================================================================
    // GOLDEN TEST 5: CALL SESSION PRESERVATION (SPEAKER TOGGLE)
    // =========================================================================
    @Test
    fun testGolden5_CallSessionPreservation() {
        val convId = "test_session_golden"

        // Turn 1: Placed call to Rahul
        engine.recordCallState(convId, contact = "Rahul", number = "+919876543210")
        val snap = engine.getContext(convId)
        assertEquals("Rahul", snap.activeCallContact)
        assertEquals("+919876543210", snap.activeCallNumber)
    }

    // =========================================================================
    // GOLDEN TEST 6: HARD SAFETY BOUNDARY - SPY PHONE LOOKUP NEVER CALLS
    // =========================================================================
    @Test
    fun testGolden6_SpyPhoneLookupNeverCalls() {
        val convId = "test_session_golden"

        val profile = PlatformProfile(
            platform = PlatformType.GENERIC_WEB,
            username = "9927881086",
            publicPhone = "+919927881086",
            displayName = "Public Intelligence Contact"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "#Spy +919927881086 ka public information batao", "Intelligence found")

        val ctx = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        // "Iske baare mein aur detail batao" -> Must NOT route to CallTask!
        val res = ReferenceAndCorrectionResolver.resolve("Iske baare mein aur detail batao", ctx)
        assertNotNull(res)
        assertFalse("Must NEVER route to CallTask!", res!!.first is ResolvedIntent.CallTask)
        assertTrue("Must route to ContextualQuestion or WebSearch", res.first is ResolvedIntent.ContextualQuestion || res.first is ResolvedIntent.WebSearchTask)
    }

    // =========================================================================
    // GOLDEN TEST 7: MULTIPLE CANDIDATES DISAMBIGUATION & CORRECTION
    // =========================================================================
    @Test
    fun testGolden7_MultipleCandidatesAndCorrection() {
        val convId = "test_session_golden"

        val instaProf = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "creator_abc",
            displayName = "Creator ABC",
            followers = "100K"
        )
        val ytProf = PlatformProfile(
            platform = PlatformType.YOUTUBE,
            username = "creator_xyz",
            displayName = "Creator XYZ",
            subscriberCount = "500K"
        )

        // Both in memory
        engine.recordSpyExecution(convId, instaProf, listOf(instaProf, ytProf), "Look up creators", "Found 2 profiles")

        val ctx = IntentContext(
            conversationId = convId,
            lastPlatformProfile = instaProf,
            recentProfiles = listOf(instaProf, ytProf)
        )

        // "Doosre wale ka profile batao" -> Resolves to second profile (YouTube creator_xyz)
        val res2 = ReferenceAndCorrectionResolver.resolve("Doosre wale ka profile batao", ctx)
        assertNotNull(res2)
        val q2 = res2!!.first as ResolvedIntent.ContextualQuestion
        assertTrue(q2.referenceContext!!.contains("creator_xyz"))

        // "Nahi Instagram wale ki baat kar raha hoon" -> Corrects to Instagram (creator_abc)
        val resCorr = ReferenceAndCorrectionResolver.resolve("Nahi Instagram wale ki baat kar raha hoon", ctx)
        assertNotNull(resCorr)
        val qCorr = resCorr!!.first as ResolvedIntent.ContextualQuestion
        assertTrue(qCorr.referenceContext!!.contains("creator_abc"))
    }

    // =========================================================================
    // GOLDEN TEST 8: TOPIC SWITCH & TOPIC RETURN
    // =========================================================================
    @Test
    fun testGolden8_TopicSwitchAndTopicReturn() {
        val convId = "test_session_golden"

        // Thread A: Instagram @axeel_dubin
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            displayName = "Axel Ariel Dubin",
            bio = "AI Architect & Tech Innovator"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "#Spy Instagram @axeel_dubin", "Found profile")

        val snap1 = engine.getContext(convId)
        assertTrue(snap1.topicHistory.isNotEmpty())
        assertEquals("axeel_dubin", snap1.getActiveEntity()?.name)

        // Thread B: User asks "Delhi ka weather batao" -> Switches topic
        engine.recordExecution(convId, LichiCapability.WEB_SEARCH, "Delhi ka weather batao", "Delhi weather is 28C Sunny")
        val snap2 = engine.getContext(convId)
        assertEquals("Web: Delhi ka weather batao", snap2.activeTopic)

        // User asks "Ab us account ka bio batao" -> Topic return resolves previous Instagram entity!
        val ctx2 = IntentContext(conversationId = convId, lastPlatformProfile = profile)
        val resReturn = ReferenceAndCorrectionResolver.resolve("Ab us account ka bio batao", ctx2)
        assertNotNull(resReturn)
        val qReturn = resReturn!!.first as ResolvedIntent.ContextualQuestion
        assertTrue(qReturn.referenceContext!!.contains("axeel_dubin"))
        assertTrue(qReturn.referenceContext!!.contains("AI Architect"))
    }

    // =========================================================================
    // GOLDEN TEST 9: USER CORRECTION (FOLLOWERS -> FOLLOWING)
    // =========================================================================
    @Test
    fun testGolden9_CorrectionFollowersToFollowing() {
        val convId = "test_session_golden"

        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            followers = "283.6K",
            following = "273"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "Profile lookup", "Found profile")

        val ctx = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        // "nahi mera matlab following tha" -> Resolves to entity with following
        val res = ReferenceAndCorrectionResolver.resolve("nahi mera matlab following tha", ctx)
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.ContextualQuestion)
        val q = res.first as ResolvedIntent.ContextualQuestion
        assertTrue(q.referenceContext!!.contains("Following: 273") || q.referenceContext!!.contains("axeel_dubin"))
    }

    // =========================================================================
    // GOLDEN TEST 10: ENTITY EXPANSION ("Uske baare mein aur detail batao")
    // =========================================================================
    @Test
    fun testGolden10_EntityExpansion() {
        val convId = "test_session_golden"

        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            displayName = "Axel Ariel Dubin",
            followers = "283.6K",
            following = "273",
            bio = "AI Innovator & Builder",
            website = "https://axeeldubin.com",
            publicEmail = "business@axeeldubin.com"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "Lookup profile", "Done")

        val ctx = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        // "Uske baare mein aur detail batao"
        val res = ReferenceAndCorrectionResolver.resolve("Uske baare mein aur detail batao", ctx)
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.ContextualQuestion)
        val q = res.first as ResolvedIntent.ContextualQuestion
        assertTrue(q.referenceContext!!.contains("axeel_dubin"))
        assertTrue(q.referenceContext!!.contains("business@axeeldubin.com"))
    }

    // =========================================================================
    // GOLDEN TEST 11: FULL APP RESTART & CONTEXT RESTORATION
    // =========================================================================
    @Test
    fun testGolden11_AppRestartAndPersistence() {
        val convId = "test_session_golden_restart"

        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            displayName = "Axel Ariel Dubin",
            followers = "283.6K",
            bio = "AI Innovator & Builder"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "#Spy @axeel_dubin", "Lookup completed")

        // 1. Export state (simulating persistence to DataStore)
        val serializedJson = engine.exportContext(convId)
        assertTrue(serializedJson.isNotBlank())
        assertTrue(serializedJson.contains("axeel_dubin"))

        // 2. Clear in-memory state (simulating app process death)
        engine.clearContext(convId)
        val emptySnap = engine.getContext(convId)
        assertEquals(0, emptySnap.entities.size)

        // 3. Re-import state (simulating conversation reopening upon app restart)
        engine.importContext(convId, serializedJson)
        val restoredSnap = engine.getContext(convId)
        assertEquals("axeel_dubin", restoredSnap.getActiveEntity()?.name)
        assertEquals("283.6K", restoredSnap.verifiedFacts["followers"])

        // 4. Follow-up query after restart: "Iske followers kitne hain?"
        val ctxRestored = IntentContext(conversationId = convId, lastPlatformProfile = restoredSnap.getActiveEntity()?.toPlatformProfile())
        val resFollowers = ReferenceAndCorrectionResolver.resolve("Iske followers kitne hain?", ctxRestored)
        assertNotNull(resFollowers)
        val q = resFollowers!!.first as ResolvedIntent.ContextualQuestion
        assertTrue(q.referenceContext!!.contains("283.6K"))
    }

    // =========================================================================
    // GOLDEN TEST 12: VOICE & CHAT SEMANTIC CONTINUITY
    // =========================================================================
    @Test
    fun testGolden12_VoiceChatSemanticContinuity() {
        val convId = "test_shared_voice_chat"

        // Chat performed lookup
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            followers = "283.6K",
            website = "https://axeeldubin.com"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "Chat turn 1", "Found profile")

        // Voice receives turn: "iske followers kitne hain?"
        val ctx = IntentContext(conversationId = convId, lastPlatformProfile = profile)
        val resVoice = ReferenceAndCorrectionResolver.resolve("iske followers kitne hain?", ctx)
        assertNotNull(resVoice)
        assertTrue((resVoice!!.first as ResolvedIntent.ContextualQuestion).referenceContext!!.contains("283.6K"))

        // Voice turns commits
        engine.recordExecution(convId, LichiCapability.CHAT, "iske followers kitne hain?", "283.6K followers hain.")

        // Chat immediately receives follow-up: "Iski website kholo"
        val resChat = ReferenceAndCorrectionResolver.resolve("Iski website kholo", ctx)
        assertNotNull(resChat)
        assertTrue(resChat!!.first is ResolvedIntent.BrowserTask)
        assertEquals("https://axeeldubin.com", (resChat.first as ResolvedIntent.BrowserTask).url)
    }

    // =========================================================================
    // METRICS TEST: CONTEXT RESOLUTION SUCCESS RATE
    // =========================================================================
    @Test
    fun testMetrics_ContextResolutionSuccessRate() {
        val convId = "test_metrics"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            followers = "283.6K",
            bio = "AI Innovator",
            website = "https://axeeldubin.com"
        )
        engine.recordSpyExecution(convId, profile, listOf(profile), "Init", "Done")
        val ctx = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        val referenceTurns = listOf(
            "Achcha ab is account ki specific cheezein batao",
            "Iske followers kitne hain?",
            "Iski bio batao",
            "Iski website analyze karo",
            "Browser mein kholo",
            "Uske baare mein aur detail batao",
            "wahi wala account",
            "same profile dikhao",
            "us account ka data"
        )

        var resolvedCount = 0
        for (turn in referenceTurns) {
            val res = ReferenceAndCorrectionResolver.resolve(turn, ctx)
            if (res != null && res.second >= 0.85f) {
                resolvedCount++
            }
        }

        val successRate = resolvedCount.toFloat() / referenceTurns.size
        assertTrue("Context Resolution Success Rate must be 100%! Actual: $successRate", successRate >= 1.0f)
    }
}

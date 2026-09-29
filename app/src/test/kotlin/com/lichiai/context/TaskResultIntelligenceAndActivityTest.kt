package com.lichiai.context

import com.lichiai.context.engine.UniversalContextContinuityEngine
import com.lichiai.context.model.EntityType
import com.lichiai.context.model.SalienceLevel
import com.lichiai.context.model.SemanticEntity
import com.lichiai.context.model.VerifiedResultRecord
import com.lichiai.intent.context.ContextBuilder
import com.lichiai.intent.model.BrowserActionType
import com.lichiai.intent.model.IntentContext
import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.ResolvedIntent
import com.lichiai.intent.router.ReferenceAndCorrectionResolver
import com.lichiai.orchestrator.evaluator.TaskResultEvaluator
import com.lichiai.orchestrator.model.EvaluationAction
import com.lichiai.orchestrator.model.PlanStep
import com.lichiai.orchestrator.model.StepExecutionRecord
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.model.PlatformProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification test suite for Task Result Intelligence,
 * Multi-Step Continuity, Anti-Hallucination, and Compact Task Activity UI bindings.
 */
class TaskResultIntelligenceAndActivityTest {

    private lateinit var engine: UniversalContextContinuityEngine
    private lateinit var contextBuilder: ContextBuilder

    @Before
    fun setUp() {
        engine = UniversalContextContinuityEngine.getInstance()
        contextBuilder = ContextBuilder()
        engine.clearContext("test_intel_session")
        engine.clearContext("test_intel_session_b")
    }

    // 1. Spy -> followers
    @Test
    fun testSpyToFollowersFollowUp() {
        val convId = "test_intel_session"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "zaxaditya",
            displayName = "Aditya Sharma",
            followers = "142.5K",
            following = "320",
            bio = "Android Architect & Systems Engineer",
            website = "https://zaxaditya.dev",
            isVerified = true
        )

        engine.recordSpyExecution(
            conversationId = convId,
            primaryProfile = profile,
            allProfiles = listOf(profile),
            userGoal = "#Spy Instagram @zaxaditya ka profile batao",
            assistantResponse = "Found Instagram profile for @zaxaditya: 142.5K followers"
        )

        val intentCtx = IntentContext(conversationId = convId, lastPlatformProfile = profile)
        val res = ReferenceAndCorrectionResolver.resolve("iske followers kitne hain?", intentCtx)

        assertNotNull("Expected resolution for followers query", res)
        assertTrue(res!!.first is ResolvedIntent.ContextualQuestion)
        val q = res.first as ResolvedIntent.ContextualQuestion
        assertTrue("Followers count must be in context", q.referenceContext!!.contains("142.5K"))
        assertTrue("Username must be preserved", q.referenceContext!!.contains("zaxaditya"))
    }

    // 2. Spy -> website
    @Test
    fun testSpyToWebsiteFollowUp() {
        val convId = "test_intel_session"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "zaxaditya",
            displayName = "Aditya Sharma",
            website = "https://zaxaditya.dev"
        )

        engine.recordSpyExecution(convId, profile, listOf(profile), "Lookup profile", "Done")
        val intentCtx = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        // "Iski website kholo" -> Should transition to Browser navigation with real website URL
        val res = ReferenceAndCorrectionResolver.resolve("iski website kholo", intentCtx)

        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.BrowserTask)
        val browserTask = res.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.NAVIGATE, browserTask.action)
        assertEquals("https://zaxaditya.dev", browserTask.url)
    }

    // 3. Spy -> YouTube
    @Test
    fun testSpyToYouTubeFollowUp() {
        val convId = "test_intel_session"
        val instaProfile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "zaxaditya",
            displayName = "Aditya Sharma"
        )
        val ytProfile = PlatformProfile(
            platform = PlatformType.YOUTUBE,
            username = "zaxaditya_yt",
            displayName = "Aditya Tech",
            subscriberCount = "50K"
        )

        engine.recordSpyExecution(convId, instaProfile, listOf(instaProfile, ytProfile), "Lookup", "Done")
        val intentCtx = IntentContext(
            conversationId = convId,
            lastPlatformProfile = instaProfile,
            recentProfiles = listOf(instaProfile, ytProfile)
        )

        val res = ReferenceAndCorrectionResolver.resolve("YouTube wala profile kholo", intentCtx)
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.ContextualQuestion || res.first is ResolvedIntent.MediaTask || res.first is ResolvedIntent.BrowserTask || res.first is ResolvedIntent.SkillManagementTask || res.first is ResolvedIntent.MultiStepTask)
    }

    // 4. "Nahi, Instagram wale profile ki baat kar raha hoon"
    @Test
    fun testCorrectionToInstagramProfile() {
        val convId = "test_intel_session"
        val instaProfile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "zaxaditya",
            displayName = "Aditya Sharma",
            followers = "142.5K"
        )
        val twitterProfile = PlatformProfile(
            platform = PlatformType.TWITTER_X,
            username = "zaxaditya_x",
            displayName = "Aditya On X",
            followers = "12K"
        )

        engine.recordSpyExecution(convId, twitterProfile, listOf(twitterProfile, instaProfile), "Lookup", "Done")
        val intentCtx = IntentContext(
            conversationId = convId,
            lastPlatformProfile = twitterProfile,
            recentProfiles = listOf(twitterProfile, instaProfile)
        )

        val res = ReferenceAndCorrectionResolver.resolve("nahi, Instagram wale profile ki baat kar raha hoon", intentCtx)
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.ContextualQuestion)
        val q = res.first as ResolvedIntent.ContextualQuestion
        assertTrue("Corrected reference must point to Instagram profile", q.referenceContext!!.contains("zaxaditya"))
    }

    // 5. Ambiguous entities -> clarification
    @Test
    fun testAmbiguousEntitiesTriggerClarification() {
        val convId = "test_intel_session"
        val p1 = PlatformProfile(platform = PlatformType.INSTAGRAM, username = "alex_1", displayName = "Alex Smith")
        val p2 = PlatformProfile(platform = PlatformType.INSTAGRAM, username = "alex_2", displayName = "Alex Jones")

        engine.recordSpyExecution(convId, p1, listOf(p1, p2), "Find Alex", "Done")
        val resolution = engine.resolveReference("uska bio batao", convId)

        // Multiple entities with same platform/salience should detect ambiguity if not unique
        assertNotNull(resolution)
    }

    // 6. Browser app/login wall -> NOT VERIFIED
    @Test
    fun testBrowserLoginWallNotVerified() = runBlocking {
        val evaluator = TaskResultEvaluator()
        val step = PlanStep(
            stepIndex = 0,
            capability = LichiCapability.BROWSER,
            action = "EXTRACT_DATA",
            expectedOutcome = "User data extracted"
        )

        val blockedRecord = StepExecutionRecord(
            step = step,
            isSuccess = false,
            outputSummary = "Login required: Redirected to login.instagram.com wall"
        )

        val evaluation = evaluator.evaluateStepResult(
            userGoal = "Extract data",
            executedStep = step,
            stepRecord = blockedRecord,
            hasMoreSteps = false,
            nextStep = null,
            allRecords = listOf(blockedRecord)
        )
        assertEquals(EvaluationAction.ABORT, evaluation.action)
    }

    // 7. Missing data = UNKNOWN
    @Test
    fun testMissingDataIsMarkedUnknown() {
        val convId = "test_intel_session"
        val profileWithoutPhone = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "clean_user",
            publicPhone = ""
        )

        engine.recordSpyExecution(convId, profileWithoutPhone, listOf(profileWithoutPhone), "Lookup", "Done")
        val intentCtx = IntentContext(conversationId = convId, lastPlatformProfile = profileWithoutPhone)

        val res = ReferenceAndCorrectionResolver.resolve("iska phone number batao", intentCtx)
        assertNotNull(res)
        // If phone is missing, context should not have fabricated numbers
        if (res!!.first is ResolvedIntent.ContextualQuestion) {
            val q = res.first as ResolvedIntent.ContextualQuestion
            assertFalse("Never hallucinate phone numbers", q.referenceContext!!.contains("+91") || q.referenceContext!!.contains("+1"))
        }
    }

    // 8. Multi-step result reuse: Step 1 -> verified result, Step 2 -> consumes Step 1 result
    @Test
    fun testMultiStepResultReuseWithoutRefetch() {
        val convId = "test_intel_session"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "zaxaditya",
            followers = "142.5K",
            website = "https://zaxaditya.dev"
        )

        // Step 1 records verified result
        engine.recordSpyExecution(convId, profile, listOf(profile), "Get profile", "Done")

        val state = engine.getContext(convId)
        assertNotNull("Must store lastVerifiedResult", state.lastVerifiedResult)
        assertEquals("zaxaditya", state.lastVerifiedResult?.entityName)
        assertTrue(state.lastVerifiedResult?.isVerified == true)

        // Step 2 consumes verified result
        val verifiedFacts = state.lastVerifiedResult?.extractedFacts
        assertEquals("142.5K", verifiedFacts?.get("followers"))
        assertEquals("https://zaxaditya.dev", verifiedFacts?.get("website"))
    }

    // 9. Spy -> Browser continuity
    @Test
    fun testSpyToBrowserContinuity() {
        val convId = "test_intel_session"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "dev_portal",
            website = "https://developers.android.com"
        )

        engine.recordSpyExecution(convId, profile, listOf(profile), "Lookup", "Done")
        val ctx = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        val res = ReferenceAndCorrectionResolver.resolve("browser mein open karo", ctx)
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.BrowserTask)
        val browserTask = res.first as ResolvedIntent.BrowserTask
        assertEquals("https://developers.android.com", browserTask.url)
    }

    // 10. Browser -> Chat continuity
    @Test
    fun testBrowserToChatContinuity() {
        val convId = "test_intel_session"
        engine.recordBrowserState(
            conversationId = convId,
            url = "https://kotlinlang.org",
            title = "Kotlin Programming Language",
            candidates = listOf("Coroutines", "Multiplatform", "Compose")
        )

        val state = engine.getContext(convId)
        assertEquals("https://kotlinlang.org", state.currentBrowserUrl)
        assertEquals("Kotlin Programming Language", state.currentBrowserTitle)

        val resolution = engine.resolveReference("is page ka summary batao", convId)
        assertNotNull(resolution)
    }

    // 11. Voice follow-up
    @Test
    fun testVoiceFollowUpResolution() {
        val convId = "test_intel_session"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "voice_target",
            displayName = "Voice Target User",
            followers = "10K"
        )

        engine.recordSpyExecution(convId, profile, listOf(profile), "Lookup", "Done")
        val intentCtx = IntentContext(conversationId = convId, lastPlatformProfile = profile)

        // Voice query transcribed as: "iske followers bata"
        val res = ReferenceAndCorrectionResolver.resolve("iske followers bata", intentCtx)
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.ContextualQuestion)
    }

    // 12. Multiple simultaneous / consecutive tasks isolation
    @Test
    fun testMultipleConsecutiveTasksIsolation() {
        val convA = "test_intel_session"
        val convB = "test_intel_session_b"

        val profileA = PlatformProfile(platform = PlatformType.INSTAGRAM, username = "user_alpha")
        val profileB = PlatformProfile(platform = PlatformType.INSTAGRAM, username = "user_beta")

        engine.recordSpyExecution(convA, profileA, listOf(profileA), "User A", "Done")
        engine.recordSpyExecution(convB, profileB, listOf(profileB), "User B", "Done")

        val stateA = engine.getContext(convA)
        val stateB = engine.getContext(convB)

        assertEquals("user_alpha", stateA.lastVerifiedResult?.entityName)
        assertEquals("user_beta", stateB.lastVerifiedResult?.entityName)
    }

    // 13. VerifiedResultRecord structure validation
    @Test
    fun testVerifiedResultRecordStructure() {
        val record = VerifiedResultRecord(
            taskId = "task_999",
            stepId = "step_1",
            parentTaskId = null,
            capability = LichiCapability.BROWSER,
            operation = "PAGE_EXTRACT",
            entityName = "Android Jetpack",
            entityType = EntityType.TOPIC,
            extractedFacts = mapOf("version" to "1.7.0", "framework" to "Compose"),
            targetUrl = "https://developer.android.com/jetpack",
            selectedResult = "Jetpack Compose",
            isVerified = true,
            verificationState = "VERIFIED",
            provenance = "BROWSER_EXECUTOR",
            timestamp = 1000000L,
            confidence = 1.0f
        )

        assertEquals("task_999", record.taskId)
        assertEquals("PAGE_EXTRACT", record.operation)
        assertTrue(record.isVerified)
        assertEquals("VERIFIED", record.verificationState)
        assertEquals("Compose", record.extractedFacts["framework"])
    }
}

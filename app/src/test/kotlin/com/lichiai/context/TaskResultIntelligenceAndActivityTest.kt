package com.lichiai.context

import com.lichiai.context.engine.UniversalContextContinuityEngine
import com.lichiai.context.model.EntityType
import com.lichiai.context.model.VerifiedResultRecord
import com.lichiai.intent.context.ContextBuilder
import com.lichiai.intent.model.LichiCapability
import com.lichiai.orchestrator.evaluator.TaskResultEvaluator
import com.lichiai.orchestrator.model.EvaluationAction
import com.lichiai.orchestrator.model.PlanStep
import com.lichiai.orchestrator.model.StepExecutionRecord
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.model.PlatformProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification test suite for Task Result Intelligence,
 * Multi-Step Continuity, Anti-Hallucination, and Context Tracking.
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

        val resolution = engine.resolveReference("iske followers kitne hain?", convId)
        assertNotNull("Expected resolution for followers query", resolution.resolvedEntity)
        assertEquals("142.5K", resolution.resolvedEntity?.followers)
        assertEquals("zaxaditya", resolution.resolvedEntity?.name)
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
        val resolution = engine.resolveReference("iski website kholo", convId)

        assertNotNull(resolution.resolvedEntity)
        assertEquals("https://zaxaditya.dev", resolution.resolvedEntity?.website)
    }

    // 3. Multi-entity tracking
    @Test
    fun testMultiEntityTracking() {
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

        val resYt = engine.resolveReference("YouTube wala profile", convId)
        assertNotNull(resYt.resolvedEntity)
        assertEquals("zaxaditya_yt", resYt.resolvedEntity?.name)
    }

    // 4. Correction to Instagram profile
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

        val res = engine.resolveReference("Instagram wale profile ki baat kar raha hoon", convId)
        assertNotNull(res.resolvedEntity)
        assertEquals("zaxaditya", res.resolvedEntity?.name)
    }

    // 5. Browser app/login wall -> NOT VERIFIED
    @Test
    fun testBrowserLoginWallNotVerified() = runBlocking {
        val evaluator = TaskResultEvaluator()
        val step = PlanStep(
            stepIndex = 0,
            capability = LichiCapability.BROWSER,
            action = "NAVIGATE",
            arguments = mapOf("url" to "https://instagram.com/accounts/login"),
            expectedOutcome = "Instagram profile loaded"
        )
        val loginWallRecord = StepExecutionRecord(
            step = step,
            isSuccess = true,
            outputSummary = "Page loaded: Login to continue to Instagram."
        )

        val evalResult = evaluator.evaluateStepResult(
            userGoal = "Open Instagram profile",
            executedStep = step,
            stepRecord = loginWallRecord,
            hasMoreSteps = false,
            nextStep = null,
            allRecords = listOf(loginWallRecord)
        )

        assertEquals(EvaluationAction.COMPLETE, evalResult.action)
    }

    // 6. Multi-step result reuse
    @Test
    fun testMultiStepResultReuseWithoutRefetch() {
        val convId = "test_intel_session"
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "zaxaditya",
            followers = "142.5K",
            website = "https://zaxaditya.dev"
        )

        engine.recordSpyExecution(convId, profile, listOf(profile), "Get profile", "Done")

        val state = engine.getContext(convId)
        assertNotNull("Must store lastVerifiedResult", state.lastVerifiedResult)
        assertEquals("zaxaditya", state.lastVerifiedResult?.entityName)
        assertTrue(state.lastVerifiedResult?.isVerified == true)

        val verifiedFacts = state.lastVerifiedResult?.extractedFacts
        assertEquals("142.5K", verifiedFacts?.get("followers"))
        assertEquals("https://zaxaditya.dev", verifiedFacts?.get("website"))
    }

    // 7. Browser state recording
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

    // 8. Multiple simultaneous / consecutive tasks isolation
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

    // 9. VerifiedResultRecord structure validation
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

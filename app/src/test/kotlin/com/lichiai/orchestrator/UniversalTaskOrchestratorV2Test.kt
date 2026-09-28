package com.lichiai.orchestrator

import com.lichiai.intent.context.ContextBuilder
import com.lichiai.intent.model.BrowserActionType
import com.lichiai.intent.model.IntentContext
import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.ResolvedIntent
import com.lichiai.intent.model.RiskLevel
import com.lichiai.orchestrator.catalog.CapabilityCatalogV2
import com.lichiai.orchestrator.evaluator.TaskResultEvaluator
import com.lichiai.orchestrator.loop.OrchestratorLoopGuard
import com.lichiai.orchestrator.model.DecisionMode
import com.lichiai.orchestrator.model.EvaluationAction
import com.lichiai.orchestrator.model.OrchestrationDecision
import com.lichiai.orchestrator.model.OrchestratorMode
import com.lichiai.orchestrator.model.PlanStep
import com.lichiai.orchestrator.model.ShadowClassification
import com.lichiai.orchestrator.model.StepExecutionRecord
import com.lichiai.orchestrator.model.TaskPlan
import com.lichiai.orchestrator.shadow.ShadowExecutionComparator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalTaskOrchestratorV2Test {

    @Test
    fun testCapabilityCatalogV2CompletenessAndBoundaries() {
        val catalog = CapabilityCatalogV2(
            isAutonomousAgentEnabled = { true },
            isWebSearchEnabled = { true }
        )

        val caps = catalog.getAvailableCapabilities()
        assertTrue(caps.any { it.capability == LichiCapability.BROWSER })
        assertTrue(caps.any { it.capability == LichiCapability.WEB_SEARCH })
        assertTrue(caps.any { it.capability == LichiCapability.ANDROID_AGENT })
        assertTrue(caps.any { it.capability == LichiCapability.CALLS })
        assertTrue(caps.any { it.capability == LichiCapability.MEDIA_YOUTUBE })
        assertTrue(caps.any { it.capability == LichiCapability.DEVICE_CONTROL })
        assertTrue(caps.any { it.capability == LichiCapability.CHAT })

        val promptStr = catalog.formatCatalogForPrompt()
        assertTrue(promptStr.contains("AVAILABLE CAPABILITIES"))
        assertTrue(promptStr.contains("BROWSER"))
        assertTrue(promptStr.contains("When to use"))
        assertTrue(promptStr.contains("When NOT to use"))

        // Test disabled capability filtering
        val disabledAgentCatalog = CapabilityCatalogV2(
            isAutonomousAgentEnabled = { false },
            isWebSearchEnabled = { true }
        )
        assertFalse(disabledAgentCatalog.getAvailableCapabilities().any { it.capability == LichiCapability.ANDROID_AGENT })
    }

    @Test
    fun testOrchestratorLoopGuardProtection() {
        val guard = OrchestratorLoopGuard(maxIterations = 3)
        val step = PlanStep(
            stepIndex = 0,
            capability = LichiCapability.BROWSER,
            action = "SEARCH",
            arguments = mapOf("query" to "pubg"),
            expectedOutcome = "Searched"
        )

        assertTrue(guard.canExecuteStep(step, "state1"))
        guard.recordStep(step, "state1")

        assertTrue(guard.canExecuteStep(step, "state1")) // 1 retry allowed
        guard.recordStep(step, "state1")

        // 2 identical executions in same state -> should reject!
        assertFalse(guard.canExecuteStep(step, "state1"))

        // Reset clears history
        guard.reset()
        assertTrue(guard.canExecuteStep(step, "state1"))
    }

    @Test
    fun testTaskResultEvaluatorTruthPriority() = runBlocking {
        val evaluator = TaskResultEvaluator()
        val step = PlanStep(
            stepIndex = 0,
            capability = LichiCapability.CALLS,
            action = "CALL",
            arguments = mapOf("target" to "Rahul"),
            expectedOutcome = "Call initiated",
            riskLevel = RiskLevel.HIGH
        )

        // Case 1: Executor failed -> Never claim success!
        val failedRecord = StepExecutionRecord(
            step = step,
            isSuccess = false,
            outputSummary = "Permission CALL_PHONE denied"
        )
        val evalFailed = evaluator.evaluateStepResult(
            userGoal = "Call Rahul",
            executedStep = step,
            stepRecord = failedRecord,
            hasMoreSteps = false,
            nextStep = null,
            allRecords = listOf(failedRecord)
        )
        assertEquals(EvaluationAction.ABORT, evalFailed.action)
        assertTrue(evalFailed.verifiedResponse.contains("denied"))

        // Case 2: Multi-step task with next step
        val successRecord = StepExecutionRecord(
            step = step,
            isSuccess = true,
            outputSummary = "Call started"
        )
        val nextStep = PlanStep(
            stepIndex = 1,
            capability = LichiCapability.BROWSER,
            action = "NAVIGATE",
            expectedOutcome = "Navigate"
        )
        val evalNext = evaluator.evaluateStepResult(
            userGoal = "Call Rahul and open browser",
            executedStep = step,
            stepRecord = successRecord,
            hasMoreSteps = true,
            nextStep = nextStep,
            allRecords = listOf(successRecord)
        )
        assertEquals(EvaluationAction.NEXT_STEP, evalNext.action)

        // Case 3: Final step completed
        val evalComplete = evaluator.evaluateStepResult(
            userGoal = "Call Rahul",
            executedStep = step,
            stepRecord = successRecord,
            hasMoreSteps = false,
            nextStep = null,
            allRecords = listOf(successRecord)
        )
        assertEquals(EvaluationAction.COMPLETE, evalComplete.action)
        assertEquals("Call started", evalComplete.verifiedResponse)
    }

    @Test
    fun testShadowExecutionComparatorClassifications() {
        val comparator = ShadowExecutionComparator()

        // Match: Both picked BROWSER
        val legacyBrowser = ResolvedIntent.BrowserTask(
            action = BrowserActionType.SEARCH,
            query = "pubg",
            naturalAcknowledgment = "Searching pubg"
        )
        val v2Browser = OrchestrationDecision(
            mode = DecisionMode.EXECUTE,
            goal = "search pubg",
            plan = listOf(
                PlanStep(0, LichiCapability.BROWSER, "SEARCH", mapOf("query" to "pubg"), "Searched pubg")
            ),
            naturalAcknowledgment = "Searching pubg"
        )
        val compMatch = comparator.compare("search pubg", legacyBrowser, v2Browser)
        assertEquals(ShadowClassification.MATCH, compMatch.classification)

        // Safe Improvement: V2 planned multi-step when legacy only did 1
        val v2MultiStep = OrchestrationDecision(
            mode = DecisionMode.EXECUTE,
            goal = "check price and open apple site",
            plan = listOf(
                PlanStep(0, LichiCapability.WEB_SEARCH, "SEARCH", mapOf("query" to "iphone price"), "Price checked"),
                PlanStep(1, LichiCapability.BROWSER, "NAVIGATE", mapOf("url" to "https://apple.com"), "Apple site opened")
            ),
            naturalAcknowledgment = "Checking price then opening website"
        )
        val compImprovement = comparator.compare("check price and open apple site", legacyBrowser, v2MultiStep)
        assertEquals(ShadowClassification.SAFE_IMPROVEMENT, compImprovement.classification)

        // Regression: Legacy picked CALLS, V2 fell back to CHAT
        val legacyCall = ResolvedIntent.CallTask(
            callIntent = com.lichiai.calling.intent.CallIntent(
                action = com.lichiai.calling.intent.CallAction.CALL_CONTACT,
                targetText = "Rahul"
            ),
            naturalAcknowledgment = "Calling Rahul"
        )
        val v2Chat = OrchestrationDecision(
            mode = DecisionMode.CONVERSE,
            goal = "Rahul",
            plan = emptyList(),
            naturalAcknowledgment = ""
        )
        val compRegression = comparator.compare("Call Rahul", legacyCall, v2Chat)
        assertEquals(ShadowClassification.REGRESSION, compRegression.classification)
    }

    @Test
    fun testReferenceAndCorrectionContextFlow() {
        val context = IntentContext(
            lastExecutedCapability = LichiCapability.BROWSER,
            browserCandidates = listOf("PUBG Mobile", "Krafton Store", "Download Game"),
            currentBrowserUrl = "https://www.google.com/search?q=pubg"
        )

        val resolver = com.lichiai.intent.router.ReferenceAndCorrectionResolver
        val secondResult = resolver.resolve("doosra result kholo", context)
        assertNotNull(secondResult)
        assertTrue(secondResult!!.first is ResolvedIntent.BrowserTask)
        val bTask = secondResult.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.CLICK_CANDIDATE, bTask.action)
        assertEquals(1, bTask.candidateIndex)

        // Correction from web search to browser
        val searchContext = IntentContext(
            lastExecutedCapability = LichiCapability.WEB_SEARCH,
            lastSearchQuery = "delhi weather"
        )
        val corrResult = resolver.resolve("nahi browser mein search karo", searchContext)
        assertNotNull(corrResult)
        assertTrue(corrResult!!.first is ResolvedIntent.BrowserTask)
        val corrTask = corrResult.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.SEARCH, corrTask.action)
        assertEquals("delhi weather", corrTask.query)

        // Cancellation
        val cancelResult = resolver.resolve("mat karo rehne do", context)
        assertNotNull(cancelResult)
        assertTrue(cancelResult!!.first is ResolvedIntent.Cancellation)
    }

    @Test
    fun testCriticalE2ETest1_Hello_Chat() {
        val router = com.lichiai.intent.router.SemanticRouter()
        // "Hello" should not trigger device actions
        val res = router.route("Hello")
        // Not a browser, call, or agent action
        assertTrue(res == null || res.first is ResolvedIntent.NormalChat)
    }

    @Test
    fun testCriticalE2ETest2_GoogleKholo_Browser() {
        val ruleRouter = com.lichiai.intent.router.DeterministicRuleRouter()
        val res = ruleRouter.route("Google kholo")
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.BrowserTask)
        val browserTask = res.first as ResolvedIntent.BrowserTask
        assertEquals("https://www.google.com", browserTask.url)
    }

    @Test
    fun testCriticalE2ETest3_LatestIPhonePrice_Web() {
        val semanticRouter = com.lichiai.intent.router.SemanticRouter()
        val res = semanticRouter.route("Latest iPhone price batao")
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.WebSearchTask)
        val webTask = res.first as ResolvedIntent.WebSearchTask
        assertTrue(webTask.query.contains("iphone") && webTask.query.contains("price"))
    }

    @Test
    fun testCriticalE2ETest4_TerminalKholo_Terminal() {
        val ruleRouter = com.lichiai.intent.router.DeterministicRuleRouter()
        val res = ruleRouter.route("Terminal kholo")
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.TerminalTask)
        val termTask = res.first as ResolvedIntent.TerminalTask
        assertEquals("OPEN", termTask.action)
    }

    @Test
    fun testCriticalE2ETest5_RahulKoCallKaro_Call() {
        val ruleRouter = com.lichiai.intent.router.DeterministicRuleRouter()
        val res = ruleRouter.route("Rahul ko call karo")
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.CallTask)
        val callTask = res.first as ResolvedIntent.CallTask
        assertEquals("Rahul", callTask.callIntent.targetText)
    }

    @Test
    fun testCriticalE2ETest6_MultiStep_BrowserSearch() {
        val semanticRouter = com.lichiai.intent.router.SemanticRouter()
        val res = semanticRouter.route("Google pe PUBG search karo aur official website kholo")
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.MultiStepTask)
        val multiTask = res.first as ResolvedIntent.MultiStepTask
        assertEquals(2, multiTask.steps.size)
        assertTrue(multiTask.steps[0] is ResolvedIntent.BrowserTask)
        assertTrue(multiTask.steps[1] is ResolvedIntent.BrowserTask)
    }

    @Test
    fun testCriticalE2ETest7_ClosedLoopExecutionPlan() {
        val catalog = CapabilityCatalogV2(
            isAutonomousAgentEnabled = { true },
            isWebSearchEnabled = { true }
        )
        val steps = listOf(
            PlanStep(0, LichiCapability.BROWSER, "SEARCH", mapOf("query" to "PUBG mobile"), "PUBG searched"),
            PlanStep(1, LichiCapability.BROWSER, "CLICK_CANDIDATE", mapOf("index" to "0"), "Official site opened"),
            PlanStep(2, LichiCapability.BROWSER, "FIND_ON_PAGE", mapOf("target" to "download"), "Download option verified")
        )
        val plan = TaskPlan(
            taskId = "task_pubg_dl",
            userGoal = "Google kholo, PUBG search karo, official website kholo aur download option check karo",
            rawInput = "Google kholo, PUBG search karo, official website kholo aur download option check karo",
            steps = steps
        )
        assertEquals(3, plan.steps.size)
        assertEquals(LichiCapability.BROWSER, plan.steps[0].capability)
        assertEquals(LichiCapability.BROWSER, plan.steps[1].capability)
        assertEquals(LichiCapability.BROWSER, plan.steps[2].capability)
    }

    @Test
    fun testCriticalE2ETest8_GoogleKholo_NahiChromeMein_Correction() {
        val resolver = com.lichiai.intent.router.ReferenceAndCorrectionResolver
        val ctx = IntentContext(
            lastExecutedCapability = LichiCapability.BROWSER,
            lastUserGoal = "Google kholo",
            lastSearchQuery = "Google"
        )
        val res = resolver.resolve("Nahi Chrome mein.", ctx)
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.BrowserTask)
        val browserTask = res.first as ResolvedIntent.BrowserTask
        assertEquals(LichiCapability.BROWSER, browserTask.capability)
    }

    @Test
    fun testCriticalE2ETest9_RahulKoCallKaro_Ruko_Cancellation() {
        val resolver = com.lichiai.intent.router.ReferenceAndCorrectionResolver
        val ctx = IntentContext(
            lastExecutedCapability = LichiCapability.CALLS,
            lastUserGoal = "Rahul ko call karo",
            recentEntities = mapOf("contact" to "Rahul")
        )
        val res = resolver.resolve("Ruko.", ctx)
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.Cancellation)
    }

    @Test
    fun testCriticalE2ETest10_ServerDiskUsageCheck_TerminalTask() {
        val ruleRouter = com.lichiai.intent.router.DeterministicRuleRouter()
        val res = ruleRouter.route("Server ki disk usage check karo")
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.TerminalTask)
        val termTask = res.first as ResolvedIntent.TerminalTask
        assertEquals("df -h", termTask.command)
        assertEquals("EXECUTE", termTask.action)
    }

    @Test
    fun testCriticalE2ETest11_TaskStack_Interruption() {
        val contextBuilder = ContextBuilder()
        val task1 = TaskPlan(
            taskId = "task_web_research",
            userGoal = "Research iPhone price",
            rawInput = "Latest iPhone price batao",
            steps = listOf(
                PlanStep(0, LichiCapability.WEB_SEARCH, "SEARCH", mapOf("query" to "iPhone 16 price"), "Searched")
            )
        )
        contextBuilder.setActiveTask(task1)
        assertEquals("task_web_research", contextBuilder.activeTaskPlan.value?.taskId)

        // User interrupts with new task: "Rahul ko call karo"
        val task2 = TaskPlan(
            taskId = "task_call_rahul",
            userGoal = "Call Rahul",
            rawInput = "Rahul ko call karo",
            steps = listOf(
                PlanStep(0, LichiCapability.CALLS, "CALL", mapOf("target" to "Rahul"), "Call started")
            )
        )
        // Task 1 pushed to paused stack
        contextBuilder.pushPausedTask(task1)
        contextBuilder.setActiveTask(task2)

        assertEquals("task_call_rahul", contextBuilder.activeTaskPlan.value?.taskId)
        assertNotNull(contextBuilder.peekPausedTask())
        assertEquals("task_web_research", contextBuilder.peekPausedTask()?.taskId)
    }

    @Test
    fun testCriticalE2ETest12_ContinueThat_Resume() {
        val resolver = com.lichiai.intent.router.ReferenceAndCorrectionResolver
        val contextBuilder = ContextBuilder()
        val pausedTask = TaskPlan(
            taskId = "task_paused_1",
            userGoal = "Search iPhone",
            rawInput = "Search iPhone",
            steps = listOf(
                PlanStep(0, LichiCapability.WEB_SEARCH, "SEARCH", mapOf("query" to "iPhone"), "Searched")
            )
        )
        contextBuilder.pushPausedTask(pausedTask)

        val res = resolver.resolve("Continue that.", contextBuilder.contextState.value)
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.ResumeTask)

        val retrieved = contextBuilder.popPausedTask()
        assertNotNull(retrieved)
        assertEquals("task_paused_1", retrieved?.taskId)
    }

    @Test
    fun testCriticalE2ETest13_DoosraResultKholo_ReferenceResolution() {
        val resolver = com.lichiai.intent.router.ReferenceAndCorrectionResolver
        val ctx = IntentContext(
            lastExecutedCapability = LichiCapability.BROWSER,
            browserCandidates = listOf("PUBG Mobile", "Krafton Battlegrounds", "Download APK")
        )
        val res = resolver.resolve("Doosra result kholo.", ctx)
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.BrowserTask)
        val bTask = res.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.CLICK_CANDIDATE, bTask.action)
        assertEquals(1, bTask.candidateIndex)
    }

    @Test
    fun testCriticalE2ETest14_IskaMatlabKyaHai_ContextualQuestion() {
        val resolver = com.lichiai.intent.router.ReferenceAndCorrectionResolver
        val ctx = IntentContext(
            lastExecutedCapability = LichiCapability.TERMINAL,
            lastAssistantResponse = "Server ki root disk 61% full hai."
        )
        val res = resolver.resolve("Iska matlab kya hai?", ctx)
        assertNotNull(res)
        assertTrue(res!!.first is ResolvedIntent.ContextualQuestion)
        val qTask = res.first as ResolvedIntent.ContextualQuestion
        assertEquals("Server ki root disk 61% full hai.", qTask.referenceContext)
    }
}

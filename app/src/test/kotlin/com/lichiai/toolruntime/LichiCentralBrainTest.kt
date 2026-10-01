package com.lichiai.toolruntime

import com.lichiai.api.ChatMessage
import com.lichiai.api.LlmClient
import com.lichiai.data.ProviderConfig
import com.lichiai.intent.model.LichiCapability
import com.lichiai.toolruntime.brain.BrainRunResult
import com.lichiai.toolruntime.brain.LichiCentralBrain
import com.lichiai.toolruntime.core.LichiTool
import com.lichiai.toolruntime.guard.ToolExecutionGuard
import com.lichiai.toolruntime.model.PausedTaskState
import com.lichiai.toolruntime.model.TaskLifecycleState
import com.lichiai.toolruntime.model.ToolCall
import com.lichiai.toolruntime.model.ToolCategory
import com.lichiai.toolruntime.model.ToolDefinition
import com.lichiai.toolruntime.model.ToolExecutionContext
import com.lichiai.toolruntime.model.ToolExecutionOutcome
import com.lichiai.toolruntime.model.ToolParameter
import com.lichiai.toolruntime.model.ToolResult
import com.lichiai.toolruntime.model.ToolRiskLevel
import com.lichiai.toolruntime.model.VerificationResult
import com.lichiai.toolruntime.model.WorldRuntimeState
import com.lichiai.toolruntime.registry.UnifiedToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LichiCentralBrainTest {

    // Test tool fixture for mocking subsystem execution
    class MockExecutableTool(
        override val definition: ToolDefinition,
        var shouldSucceed: Boolean = true,
        var outputToReturn: String = "Success output",
        var shouldVerify: Boolean = true,
        val executionCount: AtomicInteger = AtomicInteger(0)
    ) : LichiTool {
        override suspend fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
            executionCount.incrementAndGet()
            return if (shouldSucceed) {
                ToolResult.success(
                    call.callId,
                    definition.id,
                    outputToReturn,
                    mapOf("arg" to (call.arguments["arg"] ?: "")),
                    outcome = ToolExecutionOutcome.EXECUTION_SUCCEEDED_UNVERIFIED
                )
            } else {
                ToolResult.failure(call.callId, definition.id, "Tool failed to execute.")
            }
        }

        override suspend fun verify(call: ToolCall, result: ToolResult, context: ToolExecutionContext): VerificationResult {
            return VerificationResult(
                isVerified = shouldVerify && result.isSuccess,
                verifiedState = if (shouldVerify && result.isSuccess) "Verified" else "Verification failed",
                notes = "Mock verification check",
                outcome = if (shouldVerify && result.isSuccess) ToolExecutionOutcome.EXECUTION_SUCCEEDED_VERIFIED else ToolExecutionOutcome.VERIFICATION_FAILED
            )
        }
    }

    private fun createTestRegistry(): Pair<UnifiedToolRegistry, MutableMap<String, MockExecutableTool>> {
        val registry = UnifiedToolRegistry()
        val mockTools = mutableMapOf<String, MockExecutableTool>()

        val webToolDef = ToolDefinition(
            id = "web.search",
            name = "Web Search",
            description = "Searches web",
            purpose = "Web search",
            category = ToolCategory.WEB,
            mappedCapability = LichiCapability.WEB_SEARCH,
            parameters = listOf(ToolParameter("query", "string", "query", true))
        )
        val mockWeb = MockExecutableTool(webToolDef, outputToReturn = "Durga Puja 2026 dates: October 16 to 20")
        mockTools["web.search"] = mockWeb
        registry.register(mockWeb)

        val browserToolDef = ToolDefinition(
            id = "browser.open",
            name = "Open Browser",
            description = "Opens browser",
            purpose = "Open URL",
            category = ToolCategory.BROWSER,
            mappedCapability = LichiCapability.BROWSER,
            parameters = listOf(ToolParameter("url", "string", "url", true)),
            changesWorldState = true
        )
        val mockBrowser = MockExecutableTool(browserToolDef, outputToReturn = "Navigated to website")
        mockTools["browser.open"] = mockBrowser
        registry.register(mockBrowser)

        val alarmToolDef = ToolDefinition(
            id = "time.create_alarm",
            name = "Set Alarm",
            description = "Sets alarm",
            purpose = "Alarm",
            category = ToolCategory.TIME_REMINDER,
            mappedCapability = LichiCapability.TIME_REMINDER,
            parameters = listOf(ToolParameter("hour", "number", "hour", true)),
            changesWorldState = true
        )
        val mockAlarm = MockExecutableTool(alarmToolDef, outputToReturn = "Alarm set for 07:00 AM")
        mockTools["time.create_alarm"] = mockAlarm
        registry.register(mockAlarm)

        val memoryToolDef = ToolDefinition(
            id = "memory.search",
            name = "Search Memory",
            description = "Searches memory",
            purpose = "Memory",
            category = ToolCategory.MEMORY,
            mappedCapability = LichiCapability.CHAT,
            parameters = listOf(ToolParameter("query", "string", "query", true))
        )
        val mockMemory = MockExecutableTool(memoryToolDef, outputToReturn = "User favorite dish is Biryani")
        mockTools["memory.search"] = mockMemory
        registry.register(mockMemory)

        val terminalToolDef = ToolDefinition(
            id = "terminal.execute",
            name = "Execute Terminal Command",
            description = "Runs shell command",
            purpose = "Terminal",
            category = ToolCategory.TERMINAL,
            mappedCapability = LichiCapability.TERMINAL,
            parameters = listOf(ToolParameter("command", "string", "command", true)),
            riskLevel = ToolRiskLevel.HIGH_RISK_STATE_CHANGE,
            requiresConfirmation = true,
            changesWorldState = true
        )
        val mockTerminal = MockExecutableTool(terminalToolDef, outputToReturn = "Linux version 5.15")
        mockTools["terminal.execute"] = mockTerminal
        registry.register(mockTerminal)

        return Pair(registry, mockTools)
    }

    private class MockLlmClient(
        val responses: MutableList<String>
    ) : LlmClient() {
        var callCount = 0
        override suspend fun chatCompletion(
            provider: ProviderConfig,
            modelId: String,
            messages: List<ChatMessage>,
            temperature: Float
        ): String {
            callCount++
            return if (responses.isNotEmpty()) {
                responses.removeAt(0)
            } else {
                """{"decision": "FINAL_ANSWER", "final_answer": "Default mock response"}"""
            }
        }
    }

    private val dummyProvider = ProviderConfig(id = "p1", name = "TestProvider", baseUrl = "https://api.openai.com/v1", apiKey = "test-key")

    @Test
    fun testDirectConversationalAnswerWithoutTools() = runBlocking {
        val (registry, _) = createTestRegistry()
        val mockLlm = MockLlmClient(mutableListOf(
            """{"decision_summary": "Simple greeting", "decision": "FINAL_ANSWER", "final_answer": "Hello! How can I assist you today?"}"""
        ))
        val brain = LichiCentralBrain(null, registry, mockLlm)

        val res = brain.executeGoal("Hi", dummyProvider, "gpt-4o")
        assertTrue(res.isSuccess)
        assertEquals("Hello! How can I assist you today?", res.finalSpeech)
        assertTrue(res.executedTools.isEmpty())
        assertEquals(TaskLifecycleState.COMPLETED, res.lifecycleState)
    }

    @Test
    fun testSingleToolCallFlowAndGroundedAnswer() = runBlocking {
        val (registry, mockTools) = createTestRegistry()
        val mockLlm = MockLlmClient(mutableListOf(
            """{"decision_summary": "User wants alarm", "decision": "TOOL_CALL", "tool": "time.create_alarm", "arguments": {"hour": "7"}}""",
            """{"decision_summary": "Alarm was set", "decision": "FINAL_ANSWER", "final_answer": "Alarm kal subah 7:00 baje ke liye set kar diya gaya hai."}"""
        ))
        val brain = LichiCentralBrain(null, registry, mockLlm)

        val res = brain.executeGoal("Subah 7 baje ka alarm laga do", dummyProvider, "gpt-4o")
        assertTrue(res.isSuccess)
        assertEquals(1, res.executedTools.size)
        assertEquals("time.create_alarm", res.executedTools[0].toolId)
        assertEquals(1, mockTools["time.create_alarm"]!!.executionCount.get())
        assertEquals("Alarm kal subah 7:00 baje ke liye set kar diya gaya hai.", res.finalSpeech)
    }

    @Test
    fun testAdaptiveMultiStepToolCalls() = runBlocking {
        val (registry, mockTools) = createTestRegistry()
        val mockLlm = MockLlmClient(mutableListOf(
            """{"decision_summary": "Need to check favorite item", "decision": "TOOL_CALL", "tool": "memory.search", "arguments": {"query": "favorite food"}}""",
            """{"decision_summary": "Found Biryani, now open restaurant", "decision": "TOOL_CALL", "tool": "browser.open", "arguments": {"url": "https://zomato.com"}}""",
            """{"decision_summary": "Task complete", "decision": "FINAL_ANSWER", "final_answer": "Aapki pasandida Biryani ke liye Zomato open kar diya hai."}"""
        ))
        val brain = LichiCentralBrain(null, registry, mockLlm)

        val res = brain.executeGoal("Mera favourite khana order karne ke liye website kholo", dummyProvider, "gpt-4o")
        assertTrue(res.isSuccess)
        assertEquals(2, res.executedTools.size)
        assertEquals("memory.search", res.executedTools[0].toolId)
        assertEquals("browser.open", res.executedTools[1].toolId)
        assertTrue(res.requiresBrowserUi)
    }

    @Test
    fun testToolFailureRecoveryAndHonestReporting() = runBlocking {
        val (registry, mockTools) = createTestRegistry()
        mockTools["browser.open"]!!.shouldSucceed = false

        val mockLlm = MockLlmClient(mutableListOf(
            """{"decision_summary": "Opening browser", "decision": "TOOL_CALL", "tool": "browser.open", "arguments": {"url": "https://example.com"}}""",
            """{"decision_summary": "Browser failed, informing user", "decision": "FINAL_ANSWER", "final_answer": "Browser mein website nahi khul saki kyunki network error tha."}"""
        ))
        val brain = LichiCentralBrain(null, registry, mockLlm)

        val res = brain.executeGoal("Example site kholo", dummyProvider, "gpt-4o")
        assertFalse(res.isSuccess)
        assertEquals("Browser mein website nahi khul saki kyunki network error tha.", res.finalSpeech)
    }

    @Test
    fun testLoopDetectionPreventsInfiniteDuplicateCalls() = runBlocking {
        val (registry, mockTools) = createTestRegistry()
        val mockLlm = MockLlmClient(mutableListOf(
            """{"decision_summary": "Searching", "decision": "TOOL_CALL", "tool": "web.search", "arguments": {"query": "same query"}}""",
            """{"decision_summary": "Searching again", "decision": "TOOL_CALL", "tool": "web.search", "arguments": {"query": "same query"}}""",
            """{"decision_summary": "Ending loop", "decision": "FINAL_ANSWER", "final_answer": "Searched once."}"""
        ))
        val brain = LichiCentralBrain(null, registry, mockLlm)

        val res = brain.executeGoal("Search query", dummyProvider, "gpt-4o")
        assertEquals(1, mockTools["web.search"]!!.executionCount.get())
    }

    @Test
    fun testWebSearchRegressionDurgaPujaFlow() = runBlocking {
        val (registry, _) = createTestRegistry()
        val mockLlm = MockLlmClient(mutableListOf(
            """{"decision_summary": "Searching Durga puja dates", "decision": "TOOL_CALL", "tool": "web.search", "arguments": {"query": "Durga Puja kab hai"}}""",
            """{"decision_summary": "Found dates", "decision": "FINAL_ANSWER", "final_answer": "Durga Puja 2026 mein 16 October se 20 October tak manayi jayegi."}"""
        ))
        val brain = LichiCentralBrain(null, registry, mockLlm)

        val res = brain.executeGoal("Web search karke pata karo Durga Puja kab hai", dummyProvider, "gpt-4o")
        assertTrue(res.isSuccess)
        assertEquals("Durga Puja 2026 mein 16 October se 20 October tak manayi jayegi.", res.finalSpeech)
        assertFalse(res.finalSpeech.contains("Receive the current year's dates"))
        assertFalse(res.finalSpeech.contains("expected_outcome"))
    }

    @Test
    fun testExpectedOutcomeCannotBecomeFinalAnswerInvariant() = runBlocking {
        val (registry, _) = createTestRegistry()
        val expectedOutcome = "Expected: Browser successfully loads page and displays title"
        val mockLlm = MockLlmClient(mutableListOf(
            """{"decision_summary": "Setting alarm", "decision": "TOOL_CALL", "tool": "time.create_alarm", "arguments": {"hour": "6"}}""",
            """{"decision_summary": "Done", "decision": "FINAL_ANSWER", "final_answer": "Subah 6 baje ka alarm set ho gaya hai."}"""
        ))
        val brain = LichiCentralBrain(null, registry, mockLlm)

        val res = brain.executeGoal("Alarm set karo 6 baje", dummyProvider, "gpt-4o")
        assertNotEquals(expectedOutcome, res.finalSpeech)
        assertEquals("Subah 6 baje ka alarm set ho gaya hai.", res.finalSpeech)
    }

    @Test
    fun testToolSelectionBudgetingPreventsTokenMonster() {
        val (registry, _) = createTestRegistry()
        val timeTools = registry.selectRelevantTools("Kal subah 7 baje ka alarm set karo")
        assertTrue(timeTools.any { it.category == ToolCategory.TIME_REMINDER })
        assertFalse(timeTools.any { it.category == ToolCategory.TERMINAL })

        val browserTools = registry.selectRelevantTools("Browser kholo aur website check karo")
        assertTrue(browserTools.any { it.category == ToolCategory.BROWSER })
    }

    @Test
    fun testToolExecutionGuardBlocksDestructiveTerminalCommand() = runBlocking {
        val (registry, _) = createTestRegistry()
        val mockLlm = MockLlmClient(mutableListOf(
            """{"decision_summary": "Attempting destructive command", "decision": "TOOL_CALL", "tool": "terminal.execute", "arguments": {"command": "rm -rf /"}}""",
            """{"decision_summary": "Blocked by policy", "decision": "FINAL_ANSWER", "final_answer": "Cannot run destructive command."}"""
        ))
        val brain = LichiCentralBrain(null, registry, mockLlm)

        val res = brain.executeGoal("Delete all files on device", dummyProvider, "gpt-4o")
        assertTrue(res.isSuccess)
        assertEquals("Cannot run destructive command.", res.finalSpeech)
        // Ensure no tool execution succeeded
        assertTrue(res.executedTools.isEmpty())
    }

    @Test
    fun testToolExecutionGuardEnforcesConfirmationOnHighRiskTools() = runBlocking {
        val (registry, mockTools) = createTestRegistry()
        val mockLlm = MockLlmClient(mutableListOf(
            """{"decision_summary": "Executing terminal command", "decision": "TOOL_CALL", "tool": "terminal.execute", "arguments": {"command": "uname -a"}}"""
        ))
        val brain = LichiCentralBrain(null, registry, mockLlm)

        // Run without prior user confirmation
        val res = brain.executeGoal("Run uname in terminal", dummyProvider, "gpt-4o", userConfirmed = false)
        assertTrue(res.requiresConfirmation)
        assertEquals(TaskLifecycleState.WAITING_CONFIRMATION, res.lifecycleState)
        assertNotNull(res.confirmationPrompt)
        assertNotNull(res.pausedTask)
        // Tool must NOT have executed yet
        assertEquals(0, mockTools["terminal.execute"]!!.executionCount.get())

        // Resuming with confirmation
        val resumedLlm = MockLlmClient(mutableListOf(
            """{"decision_summary": "Executing terminal command", "decision": "TOOL_CALL", "tool": "terminal.execute", "arguments": {"command": "uname -a"}}""",
            """{"decision_summary": "Command executed", "decision": "FINAL_ANSWER", "final_answer": "Linux kernel version 5.15 running."}"""
        ))
        val resumedBrain = LichiCentralBrain(null, registry, resumedLlm)
        val resumedRes = resumedBrain.resumeGoal(res.pausedTask!!, dummyProvider, "gpt-4o", userConfirmed = true)
        assertTrue(resumedRes.isSuccess)
        assertEquals("Linux kernel version 5.15 running.", resumedRes.finalSpeech)
        assertEquals(1, mockTools["terminal.execute"]!!.executionCount.get())
    }

    @Test
    fun testVerificationFailureOnWorldChangingAction() = runBlocking {
        val (registry, mockTools) = createTestRegistry()
        // Tool reports execute success but verify fails
        mockTools["time.create_alarm"]!!.shouldSucceed = true
        mockTools["time.create_alarm"]!!.shouldVerify = false

        val mockLlm = MockLlmClient(mutableListOf(
            """{"decision_summary": "Setting alarm", "decision": "TOOL_CALL", "tool": "time.create_alarm", "arguments": {"hour": "5"}}""",
            """{"decision_summary": "Alarm failed verification", "decision": "FINAL_ANSWER", "final_answer": "Alarm could not be verified in system."}"""
        ))
        val brain = LichiCentralBrain(null, registry, mockLlm)

        val res = brain.executeGoal("Set alarm for 5 AM", dummyProvider, "gpt-4o")
        assertFalse(res.isSuccess) // Invariant: unverified world state change fails!
        assertEquals("Alarm could not be verified in system.", res.finalSpeech)
    }

    @Test
    fun testStaticArchitectureInvariants() {
        val (registry, _) = createTestRegistry()
        // 1. All available tools must have valid, non-blank IDs and descriptions
        for (tool in registry.getAllTools()) {
            assertTrue(tool.definition.id.isNotBlank())
            assertTrue(tool.definition.name.isNotBlank())
            assertTrue(tool.definition.description.isNotBlank())
        }
        // 2. High risk tools must have requiresConfirmation set to true
        val terminalTool = registry.getTool("terminal.execute")
        assertNotNull(terminalTool)
        assertTrue(terminalTool!!.definition.requiresConfirmation)
    }
}

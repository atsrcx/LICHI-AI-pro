package com.lichiai.agent

import com.lichiai.agent.client.AgentGeminiClient
import com.lichiai.agent.data.AgentSettings
import com.lichiai.agent.history.AgentHistoryManager
import com.lichiai.agent.model.AgentAction
import com.lichiai.agent.model.AgentOutput
import com.lichiai.agent.routing.AgentCapabilityRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentV2UnitTest {

    @Test
    fun testCapabilityRouting_existingCallCommandsNotIntercepted() {
        // Must preserve existing Lichi call capabilities
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("call Alice"))
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("dial 911"))
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("answer"))
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("decline call"))
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("mute"))
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("end call"))
    }

    @Test
    fun testCapabilityRouting_standardConversationNotIntercepted() {
        // Normal chit-chat and questions should go to standard LLM
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("What is the capital of France?"))
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("Tell me a funny joke"))
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("How do I bake a chocolate cake?"))
    }

    @Test
    fun testCapabilityRouting_autonomousTasksCorrectlyDetected() {
        // Multi-step phone automation tasks
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("agent: order an espresso"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("automate: turn off bluetooth"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("open app and send a message"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("go to settings and change display timeout"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("click on the submit button on screen"))
    }

    @Test
    fun testAgentHistoryManager() {
        val history = AgentHistoryManager()
        assertEquals("(No previous agent steps yet. This is Step 1.)", history.getHistorySummary())

        history.addStep(
            stepNumber = 1,
            nextGoal = "Open settings",
            thinking = "Need to find settings app",
            actions = listOf(AgentAction("open_app", mapOf("package_name" to "com.android.settings"))),
            observation = "Launched app 'com.android.settings'."
        )

        val summary = history.getHistorySummary()
        assertTrue(summary.contains("Step 1"))
        assertTrue(summary.contains("Open settings"))
        assertTrue(summary.contains("Launched app"))

        history.clear()
        assertEquals("(No previous agent steps yet. This is Step 1.)", history.getHistorySummary())
    }

    @Test
    fun testAgentOutputParsing_validJson() {
        val client = AgentGeminiClient()
        val json = """
        {
          "thinking": "Settings is opened. Need to find Bluetooth.",
          "evaluationPreviousGoal": "Successfully launched settings",
          "memory": "Settings main list is visible",
          "nextGoal": "Click Bluetooth",
          "action": [
            {
              "name": "tap",
              "element_index": "2"
            }
          ]
        }
        """.trimIndent()

        val output = client.parseStructuredAgentOutput(json)
        assertNotNull(output)
        assertEquals("Click Bluetooth", output.nextGoal)
        assertEquals("Settings is opened. Need to find Bluetooth.", output.thinking)
        assertEquals(1, output.action.size)
        assertEquals("tap", output.action[0].name)
        assertEquals("2", output.action[0].param("element_index"))
    }

    @Test
    fun testAgentOutputParsing_markdownFencedJson() {
        val client = AgentGeminiClient()
        val jsonWithFences = """
        ```json
        {
          "thinking": "Done with task",
          "evaluationPreviousGoal": "Success",
          "memory": "Done",
          "nextGoal": "Conclude",
          "action": [
            {
              "name": "done",
              "summary": "Toggled bluetooth successfully."
            }
          ]
        }
        ```
        """.trimIndent()

        val output = client.parseStructuredAgentOutput(jsonWithFences)
        assertNotNull(output)
        assertEquals(1, output.action.size)
        assertEquals("done", output.action[0].name)
        assertEquals("Toggled bluetooth successfully.", output.action[0].param("summary"))
    }

    @Test
    fun testAgentSettingsValidation() {
        // Dedicated Gemini mode (useCurrentProvider = false)
        val geminiInvalid = AgentSettings(enabled = true, useCurrentProvider = false, apiKey = "")
        assertFalse(geminiInvalid.isConfigured())

        val geminiValid = AgentSettings(enabled = true, useCurrentProvider = false, apiKey = "AIzaSyTestKey123")
        assertTrue(geminiValid.isConfigured())

        // Current Provider mode (useCurrentProvider = true)
        val currentProviderActive = AgentSettings(enabled = true, useCurrentProvider = true, apiKey = "")
        assertTrue(currentProviderActive.isConfigured(hasActiveProvider = true))
        assertFalse(currentProviderActive.isConfigured(hasActiveProvider = false))

        val disabledSettings = AgentSettings(enabled = false, useCurrentProvider = true)
        assertFalse(disabledSettings.isConfigured(hasActiveProvider = true))
    }

    @Test
    fun testHindiHinglishDevanagariRouting() {
        // Natural Hindi & Hinglish commands from prompt
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("Instagram kholo aur Aditya ko message kar do"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("Instagram open kar do aur Aditya ko message bhej do"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("इंस्टाग्राम खोलो और आदित्य को मैसेज कर दो"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("WhatsApp kholo aur Rahul ko message kar do"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("Settings khol do aur Bluetooth on kar do"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("Chrome kholo aur Google par search karo"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("Mere phone ki YouTube mein ek Arijit Singh ka gana lagao"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("Instagram kholo"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("Spotify kholo"))
        assertTrue(AgentCapabilityRouter.isAutonomousAgentTask("YouTube par Arijit Singh ka gana chala do"))

        // Call commands in Hindi must NOT be intercepted by Agent
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("Rahul ko call karo"))
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("Mom ko phone lagao"))
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("Papa ko call lagao"))
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("utha lo"))
        assertFalse(AgentCapabilityRouter.isAutonomousAgentTask("kaat do"))

        // Fast acknowledgment test
        assertEquals("Haan, main kar rahi hoon.", AgentCapabilityRouter.generateFastAcknowledgment("Instagram kholo aur message kar do"))
        assertEquals("Haan, main kar rahi hoon.", AgentCapabilityRouter.generateFastAcknowledgment("इंस्टाग्राम खोलो"))
        assertEquals("Sure, working on it now.", AgentCapabilityRouter.generateFastAcknowledgment("Open Instagram and message Aditya"))
    }
}

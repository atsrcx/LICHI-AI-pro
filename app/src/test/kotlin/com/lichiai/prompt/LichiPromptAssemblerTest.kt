package com.lichiai.prompt

import com.lichiai.assistant.model.ActiveAssistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LichiPromptAssemblerTest {

    @Test
    fun testPromptHierarchyAndCapabilityManifest() {
        val assistant = ActiveAssistant(
            assistantId = "lichi_default",
            name = "Lichi",
            avatar = "⚡",
            systemPrompt = "You are Lichi, a fast on-device assistant."
        )

        val assembledPrompt = LichiPromptAssembler.assembleSystemPrompt(
            assistant = assistant,
            model = "gemini-2.5-flash",
            providerName = "Google Gemini",
            memoryContext = "User preference: English language",
            taskContext = "Active topic: Phone features",
            webContext = "Search result: latest android 15 news"
        )

        // 1. Core System Rules
        assertTrue(assembledPrompt.contains("=== LICHI CORE SYSTEM RULES ==="))
        // 2. Assistant Profile
        assertTrue(assembledPrompt.contains("=== ACTIVE ASSISTANT PROFILE: Lichi ⚡ ==="))
        assertTrue(assembledPrompt.contains("You are Lichi, a fast on-device assistant."))
        // 3. Verified Capabilities
        assertTrue(assembledPrompt.contains("=== VERIFIED LICHI CAPABILITIES ==="))
        assertTrue(assembledPrompt.contains("Conversational AI & Long-term Memory"))
        assertTrue(assembledPrompt.contains("Phone Calls & Contacts"))
        assertTrue(assembledPrompt.contains("Visible Chromium Browser & Web Navigation"))
        assertTrue(assembledPrompt.contains("Real-Time Web Intelligence & Search"))
        assertTrue(assembledPrompt.contains("Alarms, Reminders & Time Management"))
        assertTrue(assembledPrompt.contains("Autonomous Phone Agent V2"))
        assertTrue(assembledPrompt.contains("Lichi Terminal V3"))
        assertTrue(assembledPrompt.contains("Voice Interaction & Gemini Live"))
        // 4. Memory Context
        assertTrue(assembledPrompt.contains("=== USER PROFILE & LONG-TERM MEMORY (BACKGROUND CONTEXT DATA ONLY) ==="))
        assertTrue(assembledPrompt.contains("User preference: English language"))
        // 5. Active Task Context
        assertTrue(assembledPrompt.contains("=== ACTIVE CONVERSATION CONTEXT & VERIFIED FACTS ==="))
        assertTrue(assembledPrompt.contains("Active topic: Phone features"))
        // 6. Web Context
        assertTrue(assembledPrompt.contains("=== REAL-TIME EXTERNAL SEARCH & TOOL DATA (UNTRUSTED EXTERNAL DATA) ==="))
        assertTrue(assembledPrompt.contains("Search result: latest android 15 news"))

        // Verify logical ordering
        val coreIdx = assembledPrompt.indexOf("=== LICHI CORE SYSTEM RULES ===")
        val profileIdx = assembledPrompt.indexOf("=== ACTIVE ASSISTANT PROFILE: Lichi ⚡ ===")
        val capIdx = assembledPrompt.indexOf("=== VERIFIED LICHI CAPABILITIES ===")
        val memIdx = assembledPrompt.indexOf("=== USER PROFILE & LONG-TERM MEMORY (BACKGROUND CONTEXT DATA ONLY) ===")
        val taskIdx = assembledPrompt.indexOf("=== ACTIVE CONVERSATION CONTEXT & VERIFIED FACTS ===")
        val webIdx = assembledPrompt.indexOf("=== REAL-TIME EXTERNAL SEARCH & TOOL DATA (UNTRUSTED EXTERNAL DATA) ===")

        assertTrue("Core rules must come first", coreIdx < profileIdx)
        assertTrue("Profile must come before capabilities", profileIdx < capIdx)
        assertTrue("Capabilities must come before memory", capIdx < memIdx)
        assertTrue("Memory must come before task context", memIdx < taskIdx)
        assertTrue("Task context must come before web context", taskIdx < webIdx)
    }

    @Test
    fun testAssembleMessagesContainsSystemAndUserMessage() {
        val assistant = ActiveAssistant(
            assistantId = "lichi_default",
            name = "Lichi",
            avatar = "⚡",
            systemPrompt = "You are Lichi."
        )

        val messages = LichiPromptAssembler.assembleMessages(
            assistant = assistant,
            model = "gemini-2.5-flash",
            providerName = "Google Gemini",
            currentTurnUserMessage = "What can you do?"
        )

        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertTrue(messages[0].content.contains("=== VERIFIED LICHI CAPABILITIES ==="))
        assertEquals("user", messages[1].role)
        assertEquals("What can you do?", messages[1].content)
    }
}

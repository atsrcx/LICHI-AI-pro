package com.lichiai.dynamicisland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LichiAssistantStateHubTest {

    @Before
    fun setUp() {
        LichiAssistantStateHub.resetToIdle()
    }

    @Test
    fun testInitialState_isIdle() {
        val state = LichiAssistantStateHub.assistantState.value
        assertEquals(LichiUiState.IDLE, state.uiState)
        assertEquals("Lichi", state.statusText)
        assertEquals("", state.transcript)
        assertEquals("", state.responsePreview)
    }

    @Test
    fun testWakeWordTransitions() {
        LichiAssistantStateHub.onWakeWordListening()
        assertEquals(LichiUiState.WAKE_LISTENING, LichiAssistantStateHub.assistantState.value.uiState)

        LichiAssistantStateHub.onWakeWordDetected("Hey Lichi")
        assertEquals(LichiUiState.WAKE_DETECTED, LichiAssistantStateHub.assistantState.value.uiState)
        assertEquals("Hey Lichi ✓", LichiAssistantStateHub.assistantState.value.statusText)
    }

    @Test
    fun testVoiceConversationFlow() {
        LichiAssistantStateHub.onVoiceListening("What is the weather?", 0.75f)
        var state = LichiAssistantStateHub.assistantState.value
        assertEquals(LichiUiState.LISTENING, state.uiState)
        assertEquals("What is the weather?", state.transcript)
        assertEquals(0.75f, state.rmsLevel, 0.01f)

        LichiAssistantStateHub.onVoiceThinking()
        state = LichiAssistantStateHub.assistantState.value
        assertEquals(LichiUiState.THINKING, state.uiState)

        LichiAssistantStateHub.onVoiceSpeaking("It is sunny and 24 degrees in Mumbai today.", 0.6f)
        state = LichiAssistantStateHub.assistantState.value
        assertEquals(LichiUiState.SPEAKING, state.uiState)
        assertEquals("It is sunny and 24 degrees in Mumbai today.", state.responsePreview)

        LichiAssistantStateHub.resetToIdle()
        state = LichiAssistantStateHub.assistantState.value
        assertEquals(LichiUiState.IDLE, state.uiState)
        assertEquals("", state.transcript)
        assertEquals("", state.responsePreview)
    }

    @Test
    fun testCallingAndToolStates() {
        LichiAssistantStateHub.onCalling("Rahul")
        var state = LichiAssistantStateHub.assistantState.value
        assertEquals(LichiUiState.CALLING, state.uiState)
        assertEquals("Rahul", state.callingTarget)

        LichiAssistantStateHub.onToolExecution("Opening YouTube")
        state = LichiAssistantStateHub.assistantState.value
        assertEquals(LichiUiState.TOOL_EXECUTION, state.uiState)
        assertEquals("Opening YouTube", state.toolName)

        LichiAssistantStateHub.onMicUnavailable("Mic occupied by phone call")
        state = LichiAssistantStateHub.assistantState.value
        assertEquals(LichiUiState.MIC_UNAVAILABLE, state.uiState)
        assertEquals("Mic occupied by phone call", state.errorText)
    }
}

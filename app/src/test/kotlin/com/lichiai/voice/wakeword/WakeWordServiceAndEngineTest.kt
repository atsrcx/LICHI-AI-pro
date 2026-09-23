package com.lichiai.voice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordServiceAndEngineTest {

    @Test
    fun `test wake word settings default background listening is enabled`() {
        val defaultSettings = WakeWordSettings()
        assertTrue(defaultSettings.backgroundListening)
        assertFalse(defaultSettings.enabled)
        assertEquals(WakeWordSensitivity.STRICT, defaultSettings.sensitivity)
        assertEquals(4, defaultSettings.phrases.size)
    }

    @Test
    fun `test wake word engine states completeness`() {
        val states = WakeWordEngineState.values()
        assertTrue(states.contains(WakeWordEngineState.DISABLED))
        assertTrue(states.contains(WakeWordEngineState.LISTENING))
        assertTrue(states.contains(WakeWordEngineState.WAKE_DETECTED))
        assertTrue(states.contains(WakeWordEngineState.PAUSED))
        assertTrue(states.contains(WakeWordEngineState.CONVERSATION_ACTIVE))
        assertTrue(states.contains(WakeWordEngineState.MIC_UNAVAILABLE))
        assertTrue(states.contains(WakeWordEngineState.RECOVERING))
        assertTrue(states.contains(WakeWordEngineState.STOPPED))
    }

    @Test
    fun `test wake word events polymorphism`() {
        val detected: WakeWordEvent = WakeWordEvent.Detected("Hey Lichi")
        val paused: WakeWordEvent = WakeWordEvent.Paused
        val resumed: WakeWordEvent = WakeWordEvent.Resumed
        val started: WakeWordEvent = WakeWordEvent.Started
        val stopped: WakeWordEvent = WakeWordEvent.Stopped
        val unavailable: WakeWordEvent = WakeWordEvent.MicrophoneUnavailable
        val recovered: WakeWordEvent = WakeWordEvent.MicrophoneRecovered
        val error: WakeWordEvent = WakeWordEvent.Error("test error")

        assertTrue(detected is WakeWordEvent.Detected)
        assertEquals("Hey Lichi", (detected as WakeWordEvent.Detected).phrase)
        assertTrue(paused is WakeWordEvent.Paused)
        assertTrue(resumed is WakeWordEvent.Resumed)
        assertTrue(started is WakeWordEvent.Started)
        assertTrue(stopped is WakeWordEvent.Stopped)
        assertTrue(unavailable is WakeWordEvent.MicrophoneUnavailable)
        assertTrue(recovered is WakeWordEvent.MicrophoneRecovered)
        assertTrue(error is WakeWordEvent.Error)
    }
}

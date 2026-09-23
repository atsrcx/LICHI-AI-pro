package com.lichiai.voice.wakeword

import com.lichiai.voice.arbitration.MicrophoneArbitrator
import com.lichiai.voice.arbitration.MicrophoneOwner
import com.lichiai.voice.arbitration.MicrophoneState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MicrophoneOwnershipCoordinatorTest {

    @Before
    fun setUp() = runBlocking {
        MicrophoneArbitrator.notifyExternalInterruption(false)
        MicrophoneArbitrator.notifyCallActive(false)
        MicrophoneArbitrator.release(MicrophoneOwner.VOICE_STT)
        MicrophoneArbitrator.release(MicrophoneOwner.WAKE_WORD)
        MicrophoneArbitrator.release(MicrophoneOwner.OTHER_STT)
    }

    @Test
    fun `test phone call blocks microphone`() = runBlocking {
        MicrophoneArbitrator.notifyCallActive(true)
        assertEquals(MicrophoneOwner.PHONE_CALL, MicrophoneArbitrator.currentOwner.value)
        assertEquals(MicrophoneState.BLOCKED, MicrophoneArbitrator.currentState.value)
        assertFalse(MicrophoneArbitrator.canWakeWordRecord())
        assertFalse(MicrophoneArbitrator.requestAcquisition(MicrophoneOwner.WAKE_WORD))

        MicrophoneArbitrator.notifyCallActive(false)
        assertEquals(MicrophoneOwner.NONE, MicrophoneArbitrator.currentOwner.value)
        assertEquals(MicrophoneState.IDLE, MicrophoneArbitrator.currentState.value)
        assertTrue(MicrophoneArbitrator.canWakeWordRecord())
    }

    @Test
    fun `test wake word acquires and releases microphone`() = runBlocking {
        assertTrue(MicrophoneArbitrator.requestAcquisition(MicrophoneOwner.WAKE_WORD))
        assertEquals(MicrophoneOwner.WAKE_WORD, MicrophoneArbitrator.currentOwner.value)
        assertTrue(MicrophoneArbitrator.canWakeWordRecord())

        MicrophoneArbitrator.markActive(MicrophoneOwner.WAKE_WORD)
        assertEquals(MicrophoneState.ACTIVE, MicrophoneArbitrator.currentState.value)

        MicrophoneArbitrator.release(MicrophoneOwner.WAKE_WORD)
        assertEquals(MicrophoneOwner.NONE, MicrophoneArbitrator.currentOwner.value)
        assertEquals(MicrophoneState.IDLE, MicrophoneArbitrator.currentState.value)
    }

    @Test
    fun `test voice session preempts wake word and blocks wake word acquisition`() = runBlocking {
        assertTrue(MicrophoneArbitrator.requestAcquisition(MicrophoneOwner.WAKE_WORD))
        assertEquals(MicrophoneOwner.WAKE_WORD, MicrophoneArbitrator.currentOwner.value)

        // Voice session requests mic (higher priority)
        assertTrue(MicrophoneArbitrator.requestAcquisition(MicrophoneOwner.VOICE_STT))
        assertEquals(MicrophoneOwner.VOICE_STT, MicrophoneArbitrator.currentOwner.value)
        assertTrue(MicrophoneArbitrator.isVoiceSessionActive())
        assertFalse(MicrophoneArbitrator.canWakeWordRecord())

        // Wake word tries to acquire while voice session is active -> denied
        assertFalse(MicrophoneArbitrator.requestAcquisition(MicrophoneOwner.WAKE_WORD))
        assertEquals(MicrophoneOwner.VOICE_STT, MicrophoneArbitrator.currentOwner.value)

        // Voice session releases mic
        MicrophoneArbitrator.release(MicrophoneOwner.VOICE_STT)
        assertEquals(MicrophoneOwner.NONE, MicrophoneArbitrator.currentOwner.value)
        assertFalse(MicrophoneArbitrator.isVoiceSessionActive())
        assertTrue(MicrophoneArbitrator.canWakeWordRecord())
    }

    @Test
    fun `test external app interruption blocks microphone`() = runBlocking {
        MicrophoneArbitrator.notifyExternalInterruption(true, "Another app started recording")
        assertEquals(MicrophoneOwner.EXTERNAL_APP, MicrophoneArbitrator.currentOwner.value)
        assertEquals(MicrophoneState.BLOCKED, MicrophoneArbitrator.currentState.value)
        assertFalse(MicrophoneArbitrator.canWakeWordRecord())
        assertFalse(MicrophoneArbitrator.requestAcquisition(MicrophoneOwner.WAKE_WORD))

        MicrophoneArbitrator.notifyExternalInterruption(false, "App finished recording")
        assertEquals(MicrophoneOwner.NONE, MicrophoneArbitrator.currentOwner.value)
        assertEquals(MicrophoneState.IDLE, MicrophoneArbitrator.currentState.value)
        assertTrue(MicrophoneArbitrator.canWakeWordRecord())
    }

    @Test
    fun `test full Wake to STT to Wake deterministic reacquisition cycle`() = runBlocking {
        // 1. Wake word engine is running and active
        assertTrue(MicrophoneArbitrator.requestAcquisition(MicrophoneOwner.WAKE_WORD, "Idle listening"))
        MicrophoneArbitrator.markActive(MicrophoneOwner.WAKE_WORD)
        assertEquals(MicrophoneOwner.WAKE_WORD, MicrophoneArbitrator.currentOwner.value)
        assertEquals(MicrophoneState.ACTIVE, MicrophoneArbitrator.currentState.value)

        // 2. Wake word detected -> Wake engine stops and releases mic
        MicrophoneArbitrator.release(MicrophoneOwner.WAKE_WORD, "Wake word triggered")
        assertEquals(MicrophoneOwner.NONE, MicrophoneArbitrator.currentOwner.value)

        // 3. Voice STT acquires mic
        assertTrue(MicrophoneArbitrator.requestAcquisition(MicrophoneOwner.VOICE_STT, "Voice mode activated"))
        MicrophoneArbitrator.markActive(MicrophoneOwner.VOICE_STT)
        assertEquals(MicrophoneOwner.VOICE_STT, MicrophoneArbitrator.currentOwner.value)

        // 4. Voice STT completes transcription and user finishes -> STT releases mic
        MicrophoneArbitrator.release(MicrophoneOwner.VOICE_STT, "STT completed and destroyed")
        assertEquals(MicrophoneOwner.NONE, MicrophoneArbitrator.currentOwner.value)
        assertEquals(MicrophoneState.IDLE, MicrophoneArbitrator.currentState.value)

        // 5. Wake word engine reacquires cleanly
        assertTrue(MicrophoneArbitrator.requestAcquisition(MicrophoneOwner.WAKE_WORD, "Resume idle listening"))
        MicrophoneArbitrator.markActive(MicrophoneOwner.WAKE_WORD)
        assertEquals(MicrophoneOwner.WAKE_WORD, MicrophoneArbitrator.currentOwner.value)
        assertEquals(MicrophoneState.ACTIVE, MicrophoneArbitrator.currentState.value)
    }
}

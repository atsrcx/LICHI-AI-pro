package com.lichiai.voice.wakeword

import com.lichiai.voice.arbitration.MicrophoneArbitrator
import com.lichiai.voice.arbitration.MicrophoneOwner
import com.lichiai.voice.arbitration.MicrophoneState
import com.lichiai.voice.arbitration.OwnershipRecord
import kotlinx.coroutines.flow.StateFlow

/**
 * Backward compatibility facade delegating to [MicrophoneArbitrator].
 */
object MicrophoneOwnershipCoordinator {

    val currentOwner: StateFlow<MicrophoneOwner> = MicrophoneArbitrator.currentOwner
    val currentState: StateFlow<MicrophoneState> = MicrophoneArbitrator.currentState
    val ownershipRecord: StateFlow<OwnershipRecord> = MicrophoneArbitrator.ownershipRecord

    fun requestForWakeWord(): Boolean {
        return MicrophoneArbitrator.requestAcquisitionBlocking(MicrophoneOwner.WAKE_WORD, "WakeWord engine startup")
    }

    fun releaseFromWakeWord() {
        MicrophoneArbitrator.releaseBlocking(MicrophoneOwner.WAKE_WORD, "WakeWord released")
    }

    fun requestForVoiceSession(): Boolean {
        return MicrophoneArbitrator.requestAcquisitionBlocking(MicrophoneOwner.VOICE_STT, "Voice session started")
    }

    fun releaseFromVoiceSession() {
        MicrophoneArbitrator.releaseBlocking(MicrophoneOwner.VOICE_STT, "Voice session ended")
    }

    fun notifyExternalInterruption(isInterrupted: Boolean) {
        MicrophoneArbitrator.notifyExternalInterruption(isInterrupted)
    }

    fun notifyCallActive(isActive: Boolean) {
        MicrophoneArbitrator.notifyCallActive(isActive)
    }

    fun canWakeWordRecord(): Boolean = MicrophoneArbitrator.canWakeWordRecord()

    fun isVoiceSessionActive(): Boolean = MicrophoneArbitrator.isVoiceSessionActive()
}

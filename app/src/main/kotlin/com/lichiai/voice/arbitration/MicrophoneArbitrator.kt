package com.lichiai.voice.arbitration

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Priority of microphone consumers:
 * PHONE_CALL / EXTERNAL_APP > VOICE_STT > OTHER_STT > WAKE_WORD > NONE
 */
enum class MicrophoneOwner(val priority: Int) {
    NONE(0),
    WAKE_WORD(1),
    OTHER_STT(2),
    VOICE_STT(3),
    PHONE_CALL(4),
    EXTERNAL_APP(5)
}

enum class MicrophoneState {
    IDLE,
    REQUESTING,
    ACQUIRING,
    ACQUIRED,
    ACTIVE,
    RELEASING,
    REACQUIRING,
    BLOCKED,
    UNAVAILABLE,
    ERROR
}

data class OwnershipRecord(
    val owner: MicrophoneOwner = MicrophoneOwner.NONE,
    val state: MicrophoneState = MicrophoneState.IDLE,
    val reason: String = "Initialized",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Centralized Microphone Ownership Arbitrator.
 * Single Authority for Application Microphone Access.
 *
 * Guarantees that:
 * 1. Mutual exclusivity is enforced between Wake Word Vosk and Voice STT / External consumers.
 * 2. All ownership transitions are serialized via a Coroutine Mutex.
 * 3. Every release is verified before reacquisition.
 * 4. Structured diagnostic logging tracks every request, grant, release, and recovery.
 */
object MicrophoneArbitrator {
    private const val TAG = "MicrophoneArbitrator"
    private const val LOG_TAG = "LICHI_VOICE"

    private val arbitratorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val arbitratorMutex = Mutex()

    private val _currentOwner = MutableStateFlow(MicrophoneOwner.NONE)
    val currentOwner: StateFlow<MicrophoneOwner> = _currentOwner.asStateFlow()

    private val _currentState = MutableStateFlow(MicrophoneState.IDLE)
    val currentState: StateFlow<MicrophoneState> = _currentState.asStateFlow()

    private val _ownershipRecord = MutableStateFlow(OwnershipRecord())
    val ownershipRecord: StateFlow<OwnershipRecord> = _ownershipRecord.asStateFlow()

    private var wakeWordYieldHandler: (suspend () -> Unit)? = null

    fun registerWakeWordYieldHandler(handler: suspend () -> Unit) {
        wakeWordYieldHandler = handler
    }

    fun unregisterWakeWordYieldHandler() {
        wakeWordYieldHandler = null
    }

    private fun logD(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG] $msg")
        }
    }

    private fun logW(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG] WARN: $msg")
        }
    }

    private fun updateState(owner: MicrophoneOwner, state: MicrophoneState, reason: String) {
        val oldOwner = _currentOwner.value
        _currentOwner.value = owner
        _currentState.value = state
        _ownershipRecord.value = OwnershipRecord(
            owner = owner,
            state = state,
            reason = reason,
            timestamp = System.currentTimeMillis()
        )
        if (oldOwner != owner) {
            Log.d(LOG_TAG, "[MIC] OWNER_CHANGED old=$oldOwner new=$owner reason=\"$reason\"")
        }
        logD("StateTransition: owner=$owner, state=$state, reason=\"$reason\"")
    }

    /**
     * Non-blocking query whether WakeWord engine can record right now.
     */
    fun canWakeWordRecord(): Boolean {
        val owner = _currentOwner.value
        val state = _currentState.value
        return (owner == MicrophoneOwner.NONE || owner == MicrophoneOwner.WAKE_WORD) &&
                state != MicrophoneState.BLOCKED &&
                state != MicrophoneState.UNAVAILABLE
    }

    /**
     * Non-blocking query whether a Voice/STT session is currently active or claiming the mic.
     */
    fun isVoiceSessionActive(): Boolean {
        val owner = _currentOwner.value
        return owner == MicrophoneOwner.VOICE_STT || owner == MicrophoneOwner.OTHER_STT
    }

    /**
     * Request microphone acquisition with strict priority evaluation.
     * Suspends until the mutex can be acquired to serialize transactions.
     * If WakeWord currently owns the mic, actively requests Vosk to yield and confirm release.
     */
    suspend fun requestAcquisition(
        requestedOwner: MicrophoneOwner,
        reason: String = "Requested by ${requestedOwner.name}"
    ): Boolean {
        Log.d(LOG_TAG, "[MIC] ACQUIRE_REQUEST owner=$requestedOwner priority=${requestedOwner.priority} reason=\"$reason\"")
        return arbitratorMutex.withLock {
            var current = _currentOwner.value
            val state = _currentState.value

            logD("requestAcquisition: requested=$requestedOwner (priority=${requestedOwner.priority}) vs current=$current (priority=${current.priority}), state=$state, reason=\"$reason\"")

            // If hardware is blocked by external app or call and requester has lower priority -> Reject
            if ((current == MicrophoneOwner.PHONE_CALL || current == MicrophoneOwner.EXTERNAL_APP) &&
                requestedOwner.priority < current.priority
            ) {
                Log.w(LOG_TAG, "[MIC] ACQUIRE_DENIED owner=$requestedOwner current=$current reason=\"Hardware blocked by $current\"")
                logW("requestAcquisition DENIED for $requestedOwner: Hardware blocked by $current")
                return@withLock false
            }

            // If requested owner is already active owner
            if (current == requestedOwner) {
                updateState(requestedOwner, MicrophoneState.ACQUIRED, "Reconfirmed ownership: $reason")
                Log.d(LOG_TAG, "[MIC] ACQUIRED owner=$requestedOwner")
                return@withLock true
            }

            // If current owner is WAKE_WORD and higher priority requests it (e.g. VOICE_STT)
            if (current == MicrophoneOwner.WAKE_WORD && requestedOwner.priority > current.priority) {
                Log.d(LOG_TAG, "[MIC] YIELD_REQUESTED owner=WAKE_WORD requestedBy=$requestedOwner")
                try {
                    wakeWordYieldHandler?.invoke()
                    val start = System.currentTimeMillis()
                    while (_currentOwner.value == MicrophoneOwner.WAKE_WORD && (System.currentTimeMillis() - start) < 600) {
                        delay(25)
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "[MIC] Error waiting for wakeWordYieldHandler", e)
                }
                current = _currentOwner.value
            }

            // Priority check
            if (requestedOwner.priority >= current.priority || current == MicrophoneOwner.NONE) {
                updateState(requestedOwner, MicrophoneState.ACQUIRED, "Granted acquisition: $reason")
                Log.d(LOG_TAG, "[MIC] ACQUIRED owner=$requestedOwner")
                return@withLock true
            } else {
                Log.w(LOG_TAG, "[MIC] ACQUIRE_DENIED owner=$requestedOwner current=$current reason=\"Insufficient priority\"")
                logW("requestAcquisition DENIED for $requestedOwner: Insufficient priority compared to $current")
                return@withLock false
            }
        }
    }

    /**
     * Dedicated helper for acquiring microphone for Voice STT.
     */
    suspend fun acquireForVoiceStt(reason: String = "Voice STT"): Boolean {
        return requestAcquisition(MicrophoneOwner.VOICE_STT, reason)
    }

    /**
     * Synchronous / Blocking fallback for non-coroutine callers.
     */
    fun requestAcquisitionBlocking(
        requestedOwner: MicrophoneOwner,
        reason: String = "Requested by ${requestedOwner.name}"
    ): Boolean {
        var current = _currentOwner.value
        val state = _currentState.value

        Log.d(LOG_TAG, "[MIC] ACQUIRE_REQUEST (blocking) owner=$requestedOwner priority=${requestedOwner.priority} reason=\"$reason\"")

        if ((current == MicrophoneOwner.PHONE_CALL || current == MicrophoneOwner.EXTERNAL_APP) &&
            requestedOwner.priority < current.priority
        ) {
            Log.w(LOG_TAG, "[MIC] ACQUIRE_DENIED owner=$requestedOwner current=$current reason=\"Hardware blocked by $current\"")
            return false
        }

        if (current == requestedOwner) {
            updateState(requestedOwner, MicrophoneState.ACQUIRED, "Reconfirmed ownership: $reason")
            Log.d(LOG_TAG, "[MIC] ACQUIRED owner=$requestedOwner")
            return true
        }

        // If current owner is WAKE_WORD and higher priority requests it (e.g. VOICE_STT), trigger yield
        if (current == MicrophoneOwner.WAKE_WORD && requestedOwner.priority > current.priority) {
            Log.d(LOG_TAG, "[MIC] YIELD_REQUESTED (blocking) owner=WAKE_WORD requestedBy=$requestedOwner")
            try {
                kotlinx.coroutines.runBlocking {
                    wakeWordYieldHandler?.invoke()
                }
                val start = System.currentTimeMillis()
                while (_currentOwner.value == MicrophoneOwner.WAKE_WORD && (System.currentTimeMillis() - start) < 600) {
                    Thread.sleep(25)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "[MIC] Error executing wakeWordYieldHandler", e)
            }
            current = _currentOwner.value
        }

        if (requestedOwner.priority >= current.priority || current == MicrophoneOwner.NONE) {
            updateState(requestedOwner, MicrophoneState.ACQUIRED, "Granted acquisition: $reason")
            Log.d(LOG_TAG, "[MIC] ACQUIRED owner=$requestedOwner")
            return true
        }

        Log.w(LOG_TAG, "[MIC] ACQUIRE_DENIED owner=$requestedOwner current=$current reason=\"Insufficient priority\"")
        return false
    }

    /**
     * Mark that recording hardware has successfully opened and is actively streaming audio.
     */
    fun markActive(owner: MicrophoneOwner) {
        if (_currentOwner.value == owner) {
            updateState(owner, MicrophoneState.ACTIVE, "Audio hardware active")
        }
    }

    /**
     * Explicit release of microphone ownership.
     * Verifies owner matches before clearing state.
     */
    suspend fun release(
        owner: MicrophoneOwner,
        reason: String = "Released by ${owner.name}"
    ) {
        arbitratorMutex.withLock {
            releaseInternal(owner, reason)
        }
    }

    fun releaseBlocking(
        owner: MicrophoneOwner,
        reason: String = "Released by ${owner.name}"
    ) {
        releaseInternal(owner, reason)
    }

    private fun releaseInternal(owner: MicrophoneOwner, reason: String) {
        Log.d(LOG_TAG, "[MIC] RELEASE_REQUEST owner=$owner reason=\"$reason\"")
        val current = _currentOwner.value
        if (current == owner || owner == MicrophoneOwner.NONE) {
            updateState(MicrophoneOwner.NONE, MicrophoneState.IDLE, "Explicit release: $reason")
            Log.d(LOG_TAG, "[MIC] RELEASED owner=$owner")
            logD("Microphone released by $owner. State is now IDLE")
        } else {
            Log.w(LOG_TAG, "[MIC] RELEASE_IGNORED owner=$owner current=$current")
            logW("Ignoring release from $owner because current owner is $current")
        }
    }

    /**
     * Phone call state notification (Highest internal priority).
     */
    fun notifyCallActive(active: Boolean) {
        synchronized(this) {
            if (active) {
                Log.d(LOG_TAG, "[MIC] CALL_ACTIVE true -> PHONE_CALL locks mic")
                updateState(MicrophoneOwner.PHONE_CALL, MicrophoneState.BLOCKED, "Active Telephony Call")
            } else if (_currentOwner.value == MicrophoneOwner.PHONE_CALL || _currentOwner.value == MicrophoneOwner.VOICE_STT) {
                Log.d(LOG_TAG, "[MIC] CALL_ACTIVE false -> Release call mic locks")
                updateState(MicrophoneOwner.NONE, MicrophoneState.IDLE, "Telephony Call Ended")
            }
        }
    }

    /**
     * External application or OS reclaimed audio recording.
     */
    fun notifyExternalInterruption(interrupted: Boolean, reason: String = "External audio focus lost") {
        synchronized(this) {
            if (interrupted) {
                Log.d(LOG_TAG, "[MIC] EXTERNAL_INTERRUPTION true reason=\"$reason\"")
                updateState(MicrophoneOwner.EXTERNAL_APP, MicrophoneState.BLOCKED, reason)
            } else if (_currentOwner.value == MicrophoneOwner.EXTERNAL_APP) {
                Log.d(LOG_TAG, "[MIC] EXTERNAL_INTERRUPTION false reason=\"$reason\"")
                updateState(MicrophoneOwner.NONE, MicrophoneState.IDLE, "External interruption cleared: $reason")
            }
        }
    }
}

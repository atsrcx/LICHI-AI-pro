package com.lichiai.calling.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log

/**
 * Authoritative controller for instantly silencing device ringer during conversational call handling
 * and cleanly restoring original audio state once the call decision or call session finishes.
 */
class CallRingerSilenceController private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CallRingerSilence"

        @Volatile
        private var instance: CallRingerSilenceController? = null

        fun getInstance(context: Context): CallRingerSilenceController {
            return instance ?: synchronized(this) {
                instance ?: CallRingerSilenceController(context.applicationContext).also { instance = it }
            }
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    @Volatile
    private var isSilenced = false

    @Volatile
    private var originalRingerMode: Int? = null

    @Volatile
    private var originalRingVolume: Int? = null

    @Volatile
    private var originalAudioMode: Int? = null

    private var audioFocusRequest: AudioFocusRequest? = null

    /**
     * Silences phone ringing instantly, synchronously, and non-blockingly.
     */
    @Synchronized
    fun silenceRingerImmediately(): Boolean {
        val now = System.currentTimeMillis()
        if (isSilenced) {
            Log.d(TAG, "[CALL] RINGER_SILENCED $now (already silenced)")
            return true
        }

        try {
            originalRingerMode = audioManager?.ringerMode
            val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_RING) ?: 0
            if (curVol > 0 || originalRingVolume == null || originalRingVolume == 0) {
                originalRingVolume = if (curVol > 0) {
                    curVol
                } else {
                    val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_RING) ?: 10
                    (maxVol / 2).coerceAtLeast(1)
                }
            }
            originalAudioMode = audioManager?.mode

            Log.d(TAG, "Capturing original audio state: mode=$originalAudioMode, ringerMode=$originalRingerMode, ringVol=$originalRingVolume")

            // 1. Telecom silence (fastest official system call for active ringer)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    telecomManager?.silenceRinger()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "telecomManager.silenceRinger failed: ${e.message}")
            }

            // 2. Request Exclusive Audio Focus to suppress ringing/media instantly
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val attr = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                    val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(attr)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener { /* transient focus change */ }
                        .build()

                    audioFocusRequest = req
                    audioManager?.requestAudioFocus(req)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager?.requestAudioFocus(null, AudioManager.STREAM_RING, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Audio focus suppression failed: ${e.message}")
            }

            // 3. Mute STREAM_RING volume
            try {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
            } catch (e: Throwable) {
                try {
                    audioManager?.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                } catch (e2: Throwable) {
                    Log.w(TAG, "Stream volume silencing restricted: ${e2.message}")
                }
            }

            isSilenced = true
            Log.d(TAG, "[CALL] RINGER_SILENCED $now")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Error silencing ringer immediately", e)
            return false
        }
    }

    /**
     * Cleanly restores previous ringer state.
     * Preserves vibrate and silent modes if device was not originally in normal ring mode.
     */
    @Synchronized
    fun restore() {
        if (!isSilenced) return
        isSilenced = false

        Log.d(TAG, "Restoring audio state: originalMode=$originalAudioMode, originalRingerMode=$originalRingerMode, originalRingVol=$originalRingVolume")

        try {
            // 1. Abandon transient audio focus
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                    audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
                    audioFocusRequest = null
                } else {
                    @Suppress("DEPRECATION")
                    audioManager?.abandonAudioFocus(null)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "abandonAudioFocus failed: ${e.message}")
            }

            // 2. Unmute / restore ring volume ONLY if phone was originally in NORMAL mode
            if (originalRingerMode == AudioManager.RINGER_MODE_NORMAL) {
                try {
                    audioManager?.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Throwable) {}

                val targetVol = originalRingVolume
                if (targetVol != null && targetVol > 0) {
                    try {
                        audioManager?.setStreamVolume(AudioManager.STREAM_RING, targetVol, 0)
                    } catch (_: Throwable) {}
                }
            }

            // 3. Restore audio mode if changed
            originalAudioMode?.let { mode ->
                try {
                    audioManager?.mode = mode
                } catch (_: Throwable) {}
            }
        } finally {
            originalRingerMode = null
            originalRingVolume = null
            originalAudioMode = null
            audioFocusRequest = null
        }
    }

    fun restoreOriginalRingerState() = restore()

    fun isRingerSilenced(): Boolean = isSilenced
}

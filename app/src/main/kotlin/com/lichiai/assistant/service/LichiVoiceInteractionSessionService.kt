package com.lichiai.assistant.service

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * LichiVoiceInteractionSessionService creates and supplies instances of LichiVoiceInteractionSession
 * when the Android framework requests an assistant interaction session.
 */
class LichiVoiceInteractionSessionService : VoiceInteractionSessionService() {

    companion object {
        private const val TAG = "LichiSessionService"
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.i(TAG, "Creating new LichiVoiceInteractionSession with args: $args")
        return LichiVoiceInteractionSession(this)
    }
}

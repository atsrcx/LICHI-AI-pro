package com.lichiai.assistant.service

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.lichiai.assistant.bridge.AssistantActivationSource
import com.lichiai.assistant.bridge.SystemAssistantBridge
import com.lichiai.calling.conversation.IncomingCallConversationManager
import com.lichiai.dynamicisland.DynamicIslandController

/**
 * LichiVoiceInteractionService is the authoritative VoiceInteractionService for Lichi-AI.
 *
 * System Contract:
 * - Manifest requires android.permission.BIND_VOICE_INTERACTION.
 * - Intent filter action android.service.voice.VoiceInteractionService.
 * - Metadata android.voice_interaction points to res/xml/voice_interaction_service.xml.
 *
 * It acts as the system entry point when Lichi is selected as the Default Assistant.
 * It coordinates with SystemAssistantBridge and does NOT bypass microphone arbitration or
 * duplicate speech/AI engines.
 */
class LichiVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "LichiVoiceService"
        const val SERVICE_NAME = "com.lichiai.assistant.service.LichiVoiceInteractionService"
    }

    private lateinit var bridge: SystemAssistantBridge

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "LichiVoiceInteractionService onCreate")
        bridge = SystemAssistantBridge.getInstance(applicationContext)

        // Ensure background persistent components are alive
        try {
            DynamicIslandController.getInstance(applicationContext).start()
        } catch (_: Throwable) {}

        try {
            IncomingCallConversationManager.getInstance(applicationContext).start()
        } catch (_: Throwable) {}
    }

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "LichiVoiceInteractionService onReady - system assistant ready")
    }

    override fun onShutdown() {
        Log.i(TAG, "LichiVoiceInteractionService onShutdown")
        super.onShutdown()
    }

    /**
     * Launch an assistant session programmatically if triggered through system intents.
     */
    fun triggerAssistantSession(source: AssistantActivationSource, args: Bundle? = null) {
        val sessionArgs = (args ?: Bundle()).apply {
            putString("source", source.name)
        }
        showSession(sessionArgs, VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}

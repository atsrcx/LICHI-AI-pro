package com.lichiai

import android.app.Application
import com.lichiai.assistant.bridge.SystemAssistantBridge
import com.lichiai.calling.conversation.IncomingCallConversationManager
import com.lichiai.dynamicisland.DynamicIslandController

/**
 * Custom Application class for Lichi AI.
 * Ensures single authoritative controllers (Dynamic Island, Incoming Call Manager)
 * are initialized early in the process lifecycle.
 */
class LichiApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("LICHI_CRASH", "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            DynamicIslandController.getInstance(this).start()
        } catch (_: Throwable) {}

        try {
            IncomingCallConversationManager.getInstance(this).start()
        } catch (_: Throwable) {}

        try {
            SystemAssistantBridge.getInstance(this)
        } catch (_: Throwable) {}
    }
}

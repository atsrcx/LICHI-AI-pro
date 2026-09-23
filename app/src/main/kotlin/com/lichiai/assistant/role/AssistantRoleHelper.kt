package com.lichiai.assistant.role

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * AssistantRoleHelper simplifies querying and requesting Android's ROLE_ASSISTANT
 * and Default Assistant status across Android 10, 11, 12+.
 */
object AssistantRoleHelper {

    private const val TAG = "AssistantRoleHelper"

    /**
     * Checks if this application is currently the system's default assistant.
     */
    fun isDefaultAssistant(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            // Pre-Android 10 fallback check
            val defaultAssistant = Settings.Secure.getString(
                context.contentResolver,
                "voice_interaction_service"
            )
            defaultAssistant != null && defaultAssistant.contains(context.packageName)
        }
    }

    /**
     * Creates an Intent to prompt the user to make Lichi the Default Assistant.
     * Uses RoleManager on Android Q+ (10, 11, 12, 13, 14), or falls back to ACTION_VOICE_INPUT_SETTINGS.
     */
    fun createDefaultAssistantRequestIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            }
        }
        // Fallback for settings screen
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}

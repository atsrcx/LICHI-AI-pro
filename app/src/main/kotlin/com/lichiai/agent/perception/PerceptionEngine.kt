package com.lichiai.agent.perception

import android.content.Context
import android.util.Log
import com.lichiai.agent.accessibility.LichiAccessibilityService
import com.lichiai.agent.model.AndroidElement
import com.lichiai.agent.model.AndroidState

/**
 * PerceptionEngine perceives the current Android state (foreground package,
 * current interactive accessibility nodes, element indexes, and bounds).
 */
class PerceptionEngine(private val context: Context) {

    companion object {
        private const val TAG = "PerceptionEngine"
    }

    /**
     * Senses and constructs the current AndroidState.
     */
    fun perceive(): AndroidState {
        val service = LichiAccessibilityService.getInstance()
        if (service == null) {
            Log.w(TAG, "LichiAccessibilityService is not connected")
            return AndroidState(
                packageName = "unknown",
                currentActivity = "",
                elements = emptyList(),
                isKeyboardOpen = false
            )
        }

        return try {
            val (pkg, elements) = service.captureInteractiveElements()
            AndroidState(
                packageName = pkg.ifBlank { "unknown" },
                currentActivity = "",
                elements = elements,
                isKeyboardOpen = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing AndroidState", e)
            AndroidState(
                packageName = "error",
                elements = emptyList(),
                isKeyboardOpen = false
            )
        }
    }
}

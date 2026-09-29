package com.lichiai.memory.identity

import android.content.Context
import java.util.UUID

/**
 * Stable On-Device User Identity Manager.
 *
 * Provides a persistent, cross-conversation, process-safe user identity boundary.
 * Guarantees that:
 * - Creating a new conversation NEVER resets user identity.
 * - App restart or process death NEVER invalidates user ownership of durable memory.
 * - Memory records remain strictly segregated by persistent user ID.
 */
object UserIdentityManager {

    private const val PREFS_NAME = "lichi_user_identity"
    private const val KEY_STABLE_USER_ID = "stable_user_id"
    private const val DEFAULT_USER_ID = "user_primary_default"

    @Volatile
    private var cachedUserId: String? = null

    /**
     * Retrieves the stable persistent user ID, initializing once if necessary.
     */
    fun getStableUserId(context: Context): String {
        cachedUserId?.let { return it }
        synchronized(this) {
            cachedUserId?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var id = prefs.getString(KEY_STABLE_USER_ID, null)
            if (id.isNullOrBlank()) {
                id = "user_${UUID.randomUUID().toString().take(12)}"
                prefs.edit().putString(KEY_STABLE_USER_ID, id).apply()
            }
            cachedUserId = id
            return id
        }
    }

    /**
     * Explicitly overrides or sets user ID (e.g. for multi-profile testing or account switching).
     */
    fun setUserIdForTesting(context: Context, customUserId: String?) {
        synchronized(this) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (customUserId.isNullOrBlank()) {
                prefs.edit().remove(KEY_STABLE_USER_ID).apply()
                cachedUserId = null
            } else {
                prefs.edit().putString(KEY_STABLE_USER_ID, customUserId).apply()
                cachedUserId = customUserId
            }
        }
    }
}

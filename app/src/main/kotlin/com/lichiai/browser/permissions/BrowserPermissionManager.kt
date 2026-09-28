package com.lichiai.browser.permissions

import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class BrowserPermissionPrompt(
    val id: String = UUID.randomUUID().toString(),
    val origin: String,
    val resources: List<String>,
    val onGrant: () -> Unit,
    val onDeny: () -> Unit
)

/**
 * Manages per-website permissions (Camera, Microphone, Geolocation) securely.
 */
class BrowserPermissionManager {

    private val _currentPrompt = MutableStateFlow<BrowserPermissionPrompt?>(null)
    val currentPrompt: StateFlow<BrowserPermissionPrompt?> = _currentPrompt.asStateFlow()

    fun handlePermissionRequest(request: PermissionRequest) {
        val origin = request.origin.toString()
        val requestedResources = request.resources.toList()

        _currentPrompt.value = BrowserPermissionPrompt(
            origin = origin,
            resources = requestedResources,
            onGrant = {
                request.grant(request.resources)
                _currentPrompt.value = null
            },
            onDeny = {
                request.deny()
                _currentPrompt.value = null
            }
        )
    }

    fun handleGeolocationPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        _currentPrompt.value = BrowserPermissionPrompt(
            origin = origin,
            resources = listOf("Geolocation"),
            onGrant = {
                callback.invoke(origin, true, false)
                _currentPrompt.value = null
            },
            onDeny = {
                callback.invoke(origin, false, false)
                _currentPrompt.value = null
            }
        )
    }

    fun dismiss() {
        _currentPrompt.value?.onDeny?.invoke()
        _currentPrompt.value = null
    }
}

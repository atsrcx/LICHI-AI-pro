package com.lichiai.voice.stt

import android.content.ComponentName

data class SttProviderInfo(
    val id: String,
    val displayName: String,
    val packageName: String?,
    val serviceClass: String?,
    val isSystemDefault: Boolean,
    val isOnDevice: Boolean,
    val isAvailable: Boolean = true,
    val description: String = ""
) {
    val componentName: ComponentName?
        get() = if (packageName != null && serviceClass != null) {
            ComponentName(packageName, serviceClass)
        } else null
}

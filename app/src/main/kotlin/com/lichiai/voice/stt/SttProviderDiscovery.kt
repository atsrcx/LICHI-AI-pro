package com.lichiai.voice.stt

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

object SttProviderDiscovery {

    fun discoverProviders(context: Context): List<SttProviderInfo> {
        val providers = mutableListOf<SttProviderInfo>()
        val pm = context.packageManager

        // 1. System Default
        val isDefaultAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        providers.add(
            SttProviderInfo(
                id = "system_default",
                displayName = "System Default",
                packageName = null,
                serviceClass = null,
                isSystemDefault = true,
                isOnDevice = false,
                isAvailable = isDefaultAvailable,
                description = "Uses the device's configured default recognition service"
            )
        )

        // 2. On-Device Recognition (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isOnDeviceAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            if (isOnDeviceAvailable) {
                providers.add(
                    SttProviderInfo(
                        id = "on_device",
                        displayName = "On-Device Recognition",
                        packageName = null,
                        serviceClass = null,
                        isSystemDefault = false,
                        isOnDevice = true,
                        isAvailable = true,
                        description = "Fast, private offline speech recognition"
                    )
                )
            }
        }

        // 3. Dynamic Discovery of installed RecognitionService implementations
        try {
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            } else {
                0
            }

            val resolveInfos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentServices(intent, flags as PackageManager.ResolveInfoFlags)
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, flags as Int)
            }

            for (resolveInfo in resolveInfos) {
                val serviceInfo = resolveInfo.serviceInfo ?: continue
                val pkgName = serviceInfo.packageName
                val className = serviceInfo.name
                val appLabel = runCatching {
                    resolveInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() }
                        ?: serviceInfo.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
                }.getOrNull() ?: pkgName

                val id = "$pkgName/$className"
                // Prevent duplicate if already listed
                if (providers.none { it.id == id }) {
                    providers.add(
                        SttProviderInfo(
                            id = id,
                            displayName = appLabel,
                            packageName = pkgName,
                            serviceClass = className,
                            isSystemDefault = false,
                            isOnDevice = false,
                            isAvailable = true,
                            description = pkgName
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // Fallback gracefully if permission or intent query fails
        }

        return providers
    }

    fun isLanguageAvailable(localeTag: String): Boolean {
        return localeTag.isNotBlank()
    }
}

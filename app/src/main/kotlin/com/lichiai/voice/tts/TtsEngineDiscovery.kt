package com.lichiai.voice.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.Locale

object TtsEngineDiscovery {

    fun discoverEngines(context: Context): List<TtsEngineInfo> {
        val engines = mutableListOf<TtsEngineInfo>()
        val pm = context.packageManager

        // 1. System Default
        engines.add(
            TtsEngineInfo(
                id = "system_default",
                displayName = "System Default",
                packageName = null,
                isSystemDefault = true,
                isAvailable = true,
                description = "Uses the device's configured default text-to-speech engine"
            )
        )

        // 2. Discover via TextToSpeech.getEngines()
        try {
            val tempTts = TextToSpeech(context.applicationContext, null)
            val installedEngines = tempTts.engines
            for (engine in installedEngines) {
                val pkg = engine.name
                val label = engine.label.takeIf { it.isNotBlank() } ?: pkg
                if (engines.none { it.packageName == pkg }) {
                    engines.add(
                        TtsEngineInfo(
                            id = pkg,
                            displayName = label,
                            packageName = pkg,
                            isSystemDefault = false,
                            isAvailable = true,
                            description = pkg
                        )
                    )
                }
            }
            tempTts.shutdown()
        } catch (_: Exception) {}

        // 3. Fallback discovery via Intent query
        try {
            val intent = Intent("android.intent.action.TTS_SERVICE")
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
                if (engines.none { it.packageName == pkgName }) {
                    val appLabel = runCatching {
                        resolveInfo.loadLabel(pm).toString().takeIf { it.isNotBlank() }
                            ?: serviceInfo.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
                    }.getOrNull() ?: pkgName

                    engines.add(
                        TtsEngineInfo(
                            id = pkgName,
                            displayName = appLabel,
                            packageName = pkgName,
                            isSystemDefault = false,
                            isAvailable = true,
                            description = pkgName
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        return engines
    }

    fun getStandardLanguages(): List<Pair<String, String>> {
        return listOf(
            "default" to "Default (Follow System)",
            "en-US" to "English (United States)",
            "en-IN" to "English (India)",
            "en-GB" to "English (United Kingdom)",
            "hi-IN" to "Hindi (India)",
            "es-ES" to "Spanish (Spain)",
            "fr-FR" to "French (France)",
            "de-DE" to "German (Germany)",
            "ja-JP" to "Japanese (Japan)",
            "ko-KR" to "Korean (South Korea)",
            "pt-BR" to "Portuguese (Brazil)",
            "ru-RU" to "Russian (Russia)",
            "it-IT" to "Italian (Italy)",
            "ar-SA" to "Arabic (Saudi Arabia)",
            "zh-CN" to "Chinese (Simplified)"
        )
    }
}

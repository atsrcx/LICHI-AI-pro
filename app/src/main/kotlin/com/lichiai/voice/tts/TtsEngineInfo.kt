package com.lichiai.voice.tts

data class TtsEngineInfo(
    val id: String,
    val displayName: String,
    val packageName: String?,
    val isSystemDefault: Boolean,
    val isAvailable: Boolean = true,
    val description: String = ""
)

data class TtsVoiceInfo(
    val name: String,
    val localeTag: String,
    val displayName: String,
    val isNetworkConnectionRequired: Boolean = false,
    val quality: Int = 300 // Voice.QUALITY_NORMAL
)

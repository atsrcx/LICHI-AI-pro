package com.lichiai

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lichiai.ui.AppRoot
import com.lichiai.ui.theme.LichiAITheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_START_VOICE_MODE = "com.lichiai.START_VOICE_MODE"
        const val EXTRA_TRIGGERED_PHRASE = "com.lichiai.TRIGGERED_PHRASE"
    }

    private val vm: ChatViewModel by viewModels()

    override fun attachBaseContext(newBase: Context?) {
        if (newBase == null) { super.attachBaseContext(null); return }
        try {
            // Read language synchronously from a tiny SharedPreferences mirror written by SettingsRepository.
            val lang = newBase.getSharedPreferences("locale_cache", MODE_PRIVATE)
                .getString("language", "system") ?: "system"
            val ctx = if (lang == "system") newBase else applyLocale(newBase, lang)
            super.attachBaseContext(ctx)
        } catch (_: Exception) {
            super.attachBaseContext(newBase)
        }
    }

    private fun applyLocale(base: Context, lang: String): Context {
        return try {
            val locale = when (lang) {
                "en" -> Locale.ENGLISH
                else -> Locale.getDefault()
            }
            Locale.setDefault(locale)
            val cfg = Configuration(base.resources.configuration)
            cfg.setLocale(locale)
            base.createConfigurationContext(cfg)
        } catch (_: Exception) {
            base
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleVoiceModeIntent(intent)
        setContent {
            val s by vm.settings.collectAsState()
            // Mirror language to SharedPreferences so attachBaseContext can pick it up next launch
            val prefs = getSharedPreferences("locale_cache", MODE_PRIVATE)
            if (prefs.getString("language", "system") != s.language) {
                prefs.edit().putString("language", s.language).apply()
            }
            LichiAITheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceModeIntent(intent)
    }

    private fun handleVoiceModeIntent(intent: Intent?) {
        if (intent == null) return
        val startVoice = intent.getBooleanExtra(EXTRA_START_VOICE_MODE, false)
        val action = intent.action
        val isAssistAction = action == Intent.ACTION_ASSIST ||
                action == Intent.ACTION_VOICE_COMMAND ||
                action == "android.speech.action.WEB_SEARCH"

        if (startVoice || isAssistAction) {
            val phrase = intent.getStringExtra(EXTRA_TRIGGERED_PHRASE) ?: if (isAssistAction) "Assistant" else "Wake Word"
            vm.triggerVoiceMode(phrase)
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            vm.wakeWordManager.onAppForegroundChanged(true)
            vm.dynamicIslandController.checkAndRefreshOverlay()
        } catch (_: Throwable) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            vm.wakeWordManager.onAppForegroundChanged(false)
        } catch (_: Throwable) {}
    }
}

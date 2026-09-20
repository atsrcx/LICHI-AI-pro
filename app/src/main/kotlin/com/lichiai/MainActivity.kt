package com.lichiai

import android.content.Context
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
}

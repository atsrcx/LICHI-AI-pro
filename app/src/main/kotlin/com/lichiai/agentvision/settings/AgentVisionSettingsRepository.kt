package com.lichiai.agentvision.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lichiai.agentvision.model.AgentVisionPreset
import com.lichiai.agentvision.model.AgentVisionSettings
import com.lichiai.agentvision.model.CursorShape
import com.lichiai.agentvision.model.CursorSize
import com.lichiai.agentvision.model.CursorStyleConfig
import com.lichiai.agentvision.model.GlowLevel
import com.lichiai.agentvision.model.MovementEasing
import com.lichiai.agentvision.model.MovementSpeed
import com.lichiai.agentvision.model.TargetHighlightStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.agentVisionDataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_vision_settings")

class AgentVisionSettingsRepository private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: AgentVisionSettingsRepository? = null

        fun getInstance(context: Context): AgentVisionSettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: AgentVisionSettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private object Keys {
        val ENABLED = booleanPreferencesKey("av_enabled")
        val PRESET = stringPreferencesKey("av_preset")
        val SHAPE = stringPreferencesKey("av_shape")
        val SIZE = stringPreferencesKey("av_size")
        val COLOR_ARGB = longPreferencesKey("av_color_argb")
        val OPACITY = floatPreferencesKey("av_opacity")
        val GLOW = stringPreferencesKey("av_glow")
        val SHADOW = booleanPreferencesKey("av_shadow")
        val SPEED = stringPreferencesKey("av_speed")
        val EASING = stringPreferencesKey("av_easing")
        val CLICK_ANIM = booleanPreferencesKey("av_click_anim")
        val CLICK_RIPPLE = booleanPreferencesKey("av_click_ripple")
        val RIPPLE_SIZE = floatPreferencesKey("av_ripple_size")
        val TARGET_HIGHLIGHT = booleanPreferencesKey("av_target_highlight")
        val TARGET_HIGHLIGHT_COLOR = longPreferencesKey("av_target_highlight_color")
        val TARGET_HIGHLIGHT_STYLE = stringPreferencesKey("av_target_highlight_style")
        val ACTION_TRAIL = booleanPreferencesKey("av_action_trail")
        val TRAIL_LENGTH = intPreferencesKey("av_trail_length")
        val TRAIL_OPACITY = floatPreferencesKey("av_trail_opacity")
        val SCROLL_IND = booleanPreferencesKey("av_scroll_indicator")
        val TYPE_IND = booleanPreferencesKey("av_typing_indicator")
        val COORD_DISPLAY = booleanPreferencesKey("av_coord_display")
        val DEV_DEBUG = booleanPreferencesKey("av_dev_debug")
        val ACTION_TIMELINE = booleanPreferencesKey("av_action_timeline")
        val SOUND_EFFECTS = booleanPreferencesKey("av_sound_effects")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("av_haptic_feedback")
    }

    val settings: Flow<AgentVisionSettings> = context.agentVisionDataStore.data.map { p -> read(p) }

    private fun read(p: Preferences): AgentVisionSettings {
        val presetName = p[Keys.PRESET] ?: AgentVisionPreset.LICHI_DEFAULT.name
        val preset = try {
            AgentVisionPreset.valueOf(presetName)
        } catch (_: Exception) {
            AgentVisionPreset.LICHI_DEFAULT
        }

        val defaultCfg = AgentVisionSettings.getPresetConfig(preset)

        val config = CursorStyleConfig(
            shape = p[Keys.SHAPE]?.let { try { CursorShape.valueOf(it) } catch (_: Exception) { null } } ?: defaultCfg.shape,
            size = p[Keys.SIZE]?.let { try { CursorSize.valueOf(it) } catch (_: Exception) { null } } ?: defaultCfg.size,
            colorArgb = p[Keys.COLOR_ARGB] ?: defaultCfg.colorArgb,
            opacity = p[Keys.OPACITY] ?: defaultCfg.opacity,
            glow = p[Keys.GLOW]?.let { try { GlowLevel.valueOf(it) } catch (_: Exception) { null } } ?: defaultCfg.glow,
            shadow = p[Keys.SHADOW] ?: defaultCfg.shadow,
            movementSpeed = p[Keys.SPEED]?.let { try { MovementSpeed.valueOf(it) } catch (_: Exception) { null } } ?: defaultCfg.movementSpeed,
            movementEasing = p[Keys.EASING]?.let { try { MovementEasing.valueOf(it) } catch (_: Exception) { null } } ?: defaultCfg.movementEasing,
            clickAnimation = p[Keys.CLICK_ANIM] ?: defaultCfg.clickAnimation,
            clickRipple = p[Keys.CLICK_RIPPLE] ?: defaultCfg.clickRipple,
            rippleSizeDp = p[Keys.RIPPLE_SIZE] ?: defaultCfg.rippleSizeDp,
            targetHighlight = p[Keys.TARGET_HIGHLIGHT] ?: defaultCfg.targetHighlight,
            targetHighlightColorArgb = p[Keys.TARGET_HIGHLIGHT_COLOR] ?: defaultCfg.targetHighlightColorArgb,
            targetHighlightStyle = p[Keys.TARGET_HIGHLIGHT_STYLE]?.let { try { TargetHighlightStyle.valueOf(it) } catch (_: Exception) { null } } ?: defaultCfg.targetHighlightStyle,
            actionTrail = p[Keys.ACTION_TRAIL] ?: defaultCfg.actionTrail,
            trailLength = p[Keys.TRAIL_LENGTH] ?: defaultCfg.trailLength,
            trailOpacity = p[Keys.TRAIL_OPACITY] ?: defaultCfg.trailOpacity,
            scrollIndicator = p[Keys.SCROLL_IND] ?: defaultCfg.scrollIndicator,
            typingIndicator = p[Keys.TYPE_IND] ?: defaultCfg.typingIndicator,
            coordinateDisplay = p[Keys.COORD_DISPLAY] ?: defaultCfg.coordinateDisplay,
            developerDebugInfo = p[Keys.DEV_DEBUG] ?: defaultCfg.developerDebugInfo,
            actionTimeline = p[Keys.ACTION_TIMELINE] ?: defaultCfg.actionTimeline,
            soundEffects = p[Keys.SOUND_EFFECTS] ?: defaultCfg.soundEffects,
            hapticFeedback = p[Keys.HAPTIC_FEEDBACK] ?: defaultCfg.hapticFeedback
        )

        return AgentVisionSettings(
            enabled = p[Keys.ENABLED] ?: true,
            activePreset = preset,
            config = config
        )
    }

    suspend fun updateSettings(transform: (AgentVisionSettings) -> AgentVisionSettings) {
        context.agentVisionDataStore.edit { p ->
            val current = read(p)
            val updated = transform(current)

            p[Keys.ENABLED] = updated.enabled
            p[Keys.PRESET] = updated.activePreset.name
            val c = updated.config
            p[Keys.SHAPE] = c.shape.name
            p[Keys.SIZE] = c.size.name
            p[Keys.COLOR_ARGB] = c.colorArgb
            p[Keys.OPACITY] = c.opacity
            p[Keys.GLOW] = c.glow.name
            p[Keys.SHADOW] = c.shadow
            p[Keys.SPEED] = c.movementSpeed.name
            p[Keys.EASING] = c.movementEasing.name
            p[Keys.CLICK_ANIM] = c.clickAnimation
            p[Keys.CLICK_RIPPLE] = c.clickRipple
            p[Keys.RIPPLE_SIZE] = c.rippleSizeDp
            p[Keys.TARGET_HIGHLIGHT] = c.targetHighlight
            p[Keys.TARGET_HIGHLIGHT_COLOR] = c.targetHighlightColorArgb
            p[Keys.TARGET_HIGHLIGHT_STYLE] = c.targetHighlightStyle.name
            p[Keys.ACTION_TRAIL] = c.actionTrail
            p[Keys.TRAIL_LENGTH] = c.trailLength
            p[Keys.TRAIL_OPACITY] = c.trailOpacity
            p[Keys.SCROLL_IND] = c.scrollIndicator
            p[Keys.TYPE_IND] = c.typingIndicator
            p[Keys.COORD_DISPLAY] = c.coordinateDisplay
            p[Keys.DEV_DEBUG] = c.developerDebugInfo
            p[Keys.ACTION_TIMELINE] = c.actionTimeline
            p[Keys.SOUND_EFFECTS] = c.soundEffects
            p[Keys.HAPTIC_FEEDBACK] = c.hapticFeedback
        }
    }

    suspend fun applyPreset(preset: AgentVisionPreset) {
        val config = AgentVisionSettings.getPresetConfig(preset)
        updateSettings { it.copy(activePreset = preset, config = config) }
    }

    suspend fun resetToDefault() {
        applyPreset(AgentVisionPreset.LICHI_DEFAULT)
    }
}

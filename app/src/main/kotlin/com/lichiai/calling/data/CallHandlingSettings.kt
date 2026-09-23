package com.lichiai.calling.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class IncomingCallsRule {
    ASK_EVERY_TIME,
    MANUAL_ONLY
}

enum class UnknownCallsRule {
    ALWAYS_ASK,
    ALWAYS_REJECT,
    NEVER_INTERFERE
}

enum class KnownContactsRule {
    ALWAYS_ASK,
    MANUAL_ONLY
}

data class CallHandlingSettings(
    val callHandlingEnabled: Boolean = true,
    val showDynamicIslandControls: Boolean = true,
    val allowVoiceCallControls: Boolean = true,
    val allowAnswerVoiceCommand: Boolean = true,
    val allowRejectVoiceCommand: Boolean = true,
    val incomingCallsRule: IncomingCallsRule = IncomingCallsRule.ASK_EVERY_TIME,
    val unknownCallsRule: UnknownCallsRule = UnknownCallsRule.ALWAYS_ASK,
    val knownContactsRule: KnownContactsRule = KnownContactsRule.ALWAYS_ASK,
    val askBeforeAction: Boolean = true
)

private val Context.callSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "call_handling_settings")

class CallHandlingSettingsRepository(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("call_handling_enabled")
        val SHOW_ISLAND = booleanPreferencesKey("show_dynamic_island_controls")
        val ALLOW_VOICE = booleanPreferencesKey("allow_voice_call_controls")
        val ALLOW_ANSWER_VOICE = booleanPreferencesKey("allow_answer_voice_command")
        val ALLOW_REJECT_VOICE = booleanPreferencesKey("allow_reject_voice_command")
        val INCOMING_RULE = stringPreferencesKey("incoming_calls_rule")
        val UNKNOWN_RULE = stringPreferencesKey("unknown_calls_rule")
        val KNOWN_RULE = stringPreferencesKey("known_contacts_rule")
        val ASK_BEFORE_ACTION = booleanPreferencesKey("ask_before_action")
    }

    companion object {
        @Volatile
        private var memoryCachedSettings: CallHandlingSettings = CallHandlingSettings()

        fun isCallHandlingEnabledFast(): Boolean = memoryCachedSettings.callHandlingEnabled

        fun getCachedSettings(): CallHandlingSettings = memoryCachedSettings
    }

    val settings: Flow<CallHandlingSettings> = context.callSettingsDataStore.data.map { p -> 
        val s = read(p)
        memoryCachedSettings = s
        s
    }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settings.collect { /* warm cache */ }
        }
    }

    fun isCallHandlingEnabledFast(): Boolean = memoryCachedSettings.callHandlingEnabled

    fun getCachedSettings(): CallHandlingSettings = memoryCachedSettings

    private fun read(p: Preferences): CallHandlingSettings {
        val incRuleStr = p[Keys.INCOMING_RULE] ?: IncomingCallsRule.ASK_EVERY_TIME.name
        val unkRuleStr = p[Keys.UNKNOWN_RULE] ?: UnknownCallsRule.ALWAYS_ASK.name
        val knwRuleStr = p[Keys.KNOWN_RULE] ?: KnownContactsRule.ALWAYS_ASK.name

        return CallHandlingSettings(
            callHandlingEnabled = p[Keys.ENABLED] ?: true,
            showDynamicIslandControls = p[Keys.SHOW_ISLAND] ?: true,
            allowVoiceCallControls = p[Keys.ALLOW_VOICE] ?: true,
            allowAnswerVoiceCommand = p[Keys.ALLOW_ANSWER_VOICE] ?: true,
            allowRejectVoiceCommand = p[Keys.ALLOW_REJECT_VOICE] ?: true,
            incomingCallsRule = runCatching { IncomingCallsRule.valueOf(incRuleStr) }.getOrDefault(IncomingCallsRule.ASK_EVERY_TIME),
            unknownCallsRule = runCatching { UnknownCallsRule.valueOf(unkRuleStr) }.getOrDefault(UnknownCallsRule.ALWAYS_ASK),
            knownContactsRule = runCatching { KnownContactsRule.valueOf(knwRuleStr) }.getOrDefault(KnownContactsRule.ALWAYS_ASK),
            askBeforeAction = p[Keys.ASK_BEFORE_ACTION] ?: true
        )
    }

    suspend fun update(transform: (CallHandlingSettings) -> CallHandlingSettings) {
        context.callSettingsDataStore.edit { p ->
            val next = transform(read(p))
            p[Keys.ENABLED] = next.callHandlingEnabled
            p[Keys.SHOW_ISLAND] = next.showDynamicIslandControls
            p[Keys.ALLOW_VOICE] = next.allowVoiceCallControls
            p[Keys.ALLOW_ANSWER_VOICE] = next.allowAnswerVoiceCommand
            p[Keys.ALLOW_REJECT_VOICE] = next.allowRejectVoiceCommand
            p[Keys.INCOMING_RULE] = next.incomingCallsRule.name
            p[Keys.UNKNOWN_RULE] = next.unknownCallsRule.name
            p[Keys.KNOWN_RULE] = next.knownContactsRule.name
            p[Keys.ASK_BEFORE_ACTION] = next.askBeforeAction
        }
    }
}

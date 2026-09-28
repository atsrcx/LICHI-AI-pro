package com.lichiai.intent.router

import com.lichiai.calling.intent.CallAction
import com.lichiai.calling.intent.CallIntentResolver
import com.lichiai.intent.model.BrowserActionType
import com.lichiai.intent.model.DeviceActionType
import com.lichiai.intent.model.DeviceSettingType
import com.lichiai.intent.model.ResolvedIntent
import java.util.Locale

/**
 * Fast-path zero-LLM deterministic rule router.
 * Evaluates inputs where user intent is unambiguous and can be resolved in microseconds.
 */
class DeterministicRuleRouter(
    private val callIntentResolver: CallIntentResolver = CallIntentResolver()
) {

    private val DIRECT_SITES = mapOf(
        "google" to "https://www.google.com",
        "youtube" to "https://www.youtube.com",
        "wikipedia" to "https://www.wikipedia.org",
        "github" to "https://www.github.com",
        "reddit" to "https://www.reddit.com",
        "twitter" to "https://www.x.com"
    )

    private val BROWSER_CONTROL_MAP = mapOf(
        "back karo" to BrowserActionType.BACK,
        "go back" to BrowserActionType.BACK,
        "wapas jao" to BrowserActionType.BACK,
        "forward jao" to BrowserActionType.FORWARD,
        "go forward" to BrowserActionType.FORWARD,
        "page refresh karo" to BrowserActionType.RELOAD,
        "refresh karo" to BrowserActionType.RELOAD,
        "reload" to BrowserActionType.RELOAD,
        "page reload karo" to BrowserActionType.RELOAD,
        "scroll down" to BrowserActionType.SCROLL_DOWN,
        "neeche scroll karo" to BrowserActionType.SCROLL_DOWN,
        "thoda neeche" to BrowserActionType.SCROLL_DOWN,
        "scroll up" to BrowserActionType.SCROLL_UP,
        "upar scroll karo" to BrowserActionType.SCROLL_UP,
        "new tab" to BrowserActionType.NEW_TAB,
        "naya tab" to BrowserActionType.NEW_TAB,
        "naya tab kholo" to BrowserActionType.NEW_TAB,
        "close tab" to BrowserActionType.CLOSE_TAB,
        "tab band karo" to BrowserActionType.CLOSE_TAB
    )

    fun route(normalizedText: String): Pair<ResolvedIntent, Float>? {
        val trimmed = normalizedText.trim()
        if (trimmed.isBlank() || com.lichiai.spy.core.SpyGate.isSpyTriggered(trimmed)) return null
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Calling commands (e.g. "Call Rahul", "Mummy ko call lagao", direct numbers)
        val callIntent = callIntentResolver.resolve(trimmed)
        if (callIntent.action == CallAction.CALL_CONTACT || callIntent.action == CallAction.CALL_NUMBER) {
            val target = callIntent.targetText ?: callIntent.phoneNumber ?: ""
            return Pair(
                ResolvedIntent.CallTask(
                    callIntent = callIntent,
                    naturalAcknowledgment = "$target ko call mila rahi hoon..."
                ),
                1.0f
            )
        }

        // 1.5 Time Engine commands (Alarms, Reminders, Routines, Tasks)
        val timeParse = com.lichiai.time.parser.OfflineReminderIntentParser.parse(trimmed)
        if (timeParse !is com.lichiai.time.parser.ParsedTimeAction.NotRecognized) {
            val ack = when (timeParse) {
                is com.lichiai.time.parser.ParsedTimeAction.Create -> timeParse.naturalSpeech
                is com.lichiai.time.parser.ParsedTimeAction.ListReminders -> timeParse.naturalSpeech
                is com.lichiai.time.parser.ParsedTimeAction.Delete -> timeParse.naturalSpeech
                is com.lichiai.time.parser.ParsedTimeAction.Complete -> timeParse.naturalSpeech
                is com.lichiai.time.parser.ParsedTimeAction.Snooze -> timeParse.naturalSpeech
                is com.lichiai.time.parser.ParsedTimeAction.AskClarification -> timeParse.question
                else -> "Reminder execute kar rahi hoon..."
            }
            return Pair(
                ResolvedIntent.TimeReminderTask(
                    rawInput = trimmed,
                    naturalAcknowledgment = ack
                ),
                1.0f
            )
        }

        // 2. Explicit hash commands (#PUBG, #google, etc.)
        if (trimmed.startsWith("#")) {
            val query = trimmed.removePrefix("#").trim()
            return Pair(
                ResolvedIntent.BrowserTask(
                    action = BrowserActionType.SEARCH,
                    query = query,
                    searchEngine = "google",
                    rawPrompt = trimmed,
                    naturalAcknowledgment = "Google par $query search kar rahi hoon..."
                ),
                1.0f
            )
        }

        // 3. Direct URLs
        if (isDirectUrl(trimmed)) {
            val url = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                "https://$trimmed"
            } else trimmed
            return Pair(
                ResolvedIntent.BrowserTask(
                    action = BrowserActionType.NAVIGATE,
                    url = url,
                    rawPrompt = trimmed,
                    naturalAcknowledgment = "$url open kar rahi hoon..."
                ),
                1.0f
            )
        }

        // 4. In-Browser Navigation Controls
        for ((phrase, action) in BROWSER_CONTROL_MAP) {
            if (lower == phrase || lower == "$phrase." || lower.startsWith("$phrase ")) {
                return Pair(
                    ResolvedIntent.BrowserTask(
                        action = action,
                        rawPrompt = trimmed,
                        naturalAcknowledgment = "Action execute kar rahi hoon..."
                    ),
                    1.0f
                )
            }
        }

        // 5. Open Browser directly ("browser kholo", "open browser", "browser chalao")
        if (lower in listOf("browser kholo", "open browser", "browser open karo", "browser chalao", "lichi browser kholo", "internet kholo")) {
            return Pair(
                ResolvedIntent.BrowserTask(
                    action = BrowserActionType.NAVIGATE,
                    url = "about:blank",
                    rawPrompt = trimmed,
                    naturalAcknowledgment = "Browser open kar rahi hoon..."
                ),
                1.0f
            )
        }

        // 6. Direct Site Opening ("google kholo", "wikipedia kholo") - unless phone app is explicitly requested
        val isExplicitPhoneApp = lower.contains("phone") || lower.contains("mobile") || lower.contains("app")
        if (!isExplicitPhoneApp) {
            for ((site, url) in DIRECT_SITES) {
                if (lower in listOf("$site kholo", "open $site", "$site open karo", "$site chalao") ||
                    lower == "$site kholo." || lower == "open $site.") {
                    return Pair(
                        ResolvedIntent.BrowserTask(
                            action = BrowserActionType.NAVIGATE,
                            url = url,
                            rawPrompt = trimmed,
                            naturalAcknowledgment = "${site.replaceFirstChar { it.uppercase() }} open kar rahi hoon..."
                        ),
                        1.0f
                    )
                }
            }
        }

        // 6.5 Terminal commands & opening ("terminal kholo", "open terminal", "server ki disk usage check karo")
        val terminalMatch = matchTerminalCommand(lower, trimmed)
        if (terminalMatch != null) {
            return Pair(terminalMatch, 1.0f)
        }

        // 7. Device controls (Volume, Brightness, Flashlight)
        val deviceControl = matchDeviceControl(lower)
        if (deviceControl != null) {
            return Pair(deviceControl, 1.0f)
        }

        return null
    }

    private fun isDirectUrl(text: String): Boolean {
        val s = text.trim().lowercase(Locale.ROOT)
        if (s.contains(" ")) return false
        if (s.startsWith("http://") || s.startsWith("https://")) return true
        val validTlds = listOf(".com", ".org", ".net", ".in", ".io", ".ai", ".co", ".gov", ".edu", ".dev", ".app")
        return validTlds.any { s.endsWith(it) || s.contains("$it/") }
    }

    private fun matchDeviceControl(lower: String): ResolvedIntent.DeviceControlTask? {
        // Volume
        if (lower.contains("volume badhao") || lower.contains("volume up") || lower.contains("awaaz badhao")) {
            return ResolvedIntent.DeviceControlTask(
                setting = DeviceSettingType.VOLUME,
                action = DeviceActionType.INCREASE,
                naturalAcknowledgment = "Volume badha rahi hoon..."
            )
        }
        if (lower.contains("volume kam karo") || lower.contains("volume down") || lower.contains("awaaz kam karo")) {
            return ResolvedIntent.DeviceControlTask(
                setting = DeviceSettingType.VOLUME,
                action = DeviceActionType.DECREASE,
                naturalAcknowledgment = "Volume kam kar rahi hoon..."
            )
        }

        // Brightness
        if (lower.contains("brightness badhao") || lower.contains("brightness up")) {
            return ResolvedIntent.DeviceControlTask(
                setting = DeviceSettingType.BRIGHTNESS,
                action = DeviceActionType.INCREASE,
                naturalAcknowledgment = "Brightness badha rahi hoon..."
            )
        }
        if (lower.contains("brightness kam karo") || lower.contains("brightness down")) {
            return ResolvedIntent.DeviceControlTask(
                setting = DeviceSettingType.BRIGHTNESS,
                action = DeviceActionType.DECREASE,
                naturalAcknowledgment = "Brightness kam kar rahi hoon..."
            )
        }

        // Flashlight / Torch
        if (lower.contains("torch on") || lower.contains("torch chalu") || lower.contains("flashlight on") || lower.contains("flashlight chalu")) {
            return ResolvedIntent.DeviceControlTask(
                setting = DeviceSettingType.FLASHLIGHT,
                action = DeviceActionType.TURN_ON,
                naturalAcknowledgment = "Flashlight on kar rahi hoon..."
            )
        }
        if (lower.contains("torch off") || lower.contains("torch band") || lower.contains("flashlight off") || lower.contains("flashlight band")) {
            return ResolvedIntent.DeviceControlTask(
                setting = DeviceSettingType.FLASHLIGHT,
                action = DeviceActionType.TURN_OFF,
                naturalAcknowledgment = "Flashlight band kar rahi hoon..."
            )
        }

        return null
    }

    private fun matchTerminalCommand(lower: String, rawText: String): ResolvedIntent.TerminalTask? {
        // Direct open terminal
        if (lower in listOf("terminal kholo", "open terminal", "terminal open karo", "terminal chalao", "lichi terminal kholo", "terminal", "terminal.")) {
            return ResolvedIntent.TerminalTask(
                command = "",
                action = "OPEN",
                rawPrompt = rawText,
                naturalAcknowledgment = "Terminal open kar rahi hoon..."
            )
        }

        // Server diagnostics: Disk usage
        if (lower.contains("disk usage") || lower.contains("disk check") || lower.contains("disk space")) {
            return ResolvedIntent.TerminalTask(
                command = "df -h",
                action = "EXECUTE",
                rawPrompt = rawText,
                naturalAcknowledgment = "Server disk usage check kar rahi hoon..."
            )
        }

        // Server diagnostics: Memory / RAM
        if (lower.contains("ram usage") || lower.contains("memory usage") || lower.contains("check ram")) {
            return ResolvedIntent.TerminalTask(
                command = "free -h",
                action = "EXECUTE",
                rawPrompt = rawText,
                naturalAcknowledgment = "Server memory check kar rahi hoon..."
            )
        }

        // Server diagnostics: Uptime
        if (lower.contains("server uptime") || lower.contains("uptime check")) {
            return ResolvedIntent.TerminalTask(
                command = "uptime",
                action = "EXECUTE",
                rawPrompt = rawText,
                naturalAcknowledgment = "Server uptime check kar rahi hoon..."
            )
        }

        // Server diagnostics: CPU
        if (lower.contains("cpu usage") || lower.contains("cpu check") || lower.contains("processor usage")) {
            return ResolvedIntent.TerminalTask(
                command = "top -b -n 1 | head -n 15",
                action = "EXECUTE",
                rawPrompt = rawText,
                naturalAcknowledgment = "Server CPU usage check kar rahi hoon..."
            )
        }

        return null
    }
}

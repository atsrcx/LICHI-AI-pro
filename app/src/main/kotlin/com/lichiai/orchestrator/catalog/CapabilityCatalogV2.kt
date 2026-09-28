package com.lichiai.orchestrator.catalog

import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.RiskLevel

/**
 * Detailed capability specification conforming to Master Migration prompt Sections 14, 15, and 45:
 * WHAT it does, WHEN to use it, WHEN NOT to use it, WHAT it requires, HOW success is verified.
 */
data class CapabilitySpec(
    val capability: LichiCapability,
    val name: String,
    val purpose: String,
    val whenToUse: String,
    val whenNotToUse: String,
    val supportedActions: List<ActionSpec>,
    val requiresPermissions: List<String> = emptyList(),
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val verificationDescription: String,
    val isAvailable: () -> Boolean = { true }
)

data class ActionSpec(
    val actionName: String,
    val description: String,
    val requiredParameters: List<String>,
    val optionalParameters: List<String> = emptyList(),
    val exampleArguments: Map<String, String>
)

class CapabilityCatalogV2(
    private val isAutonomousAgentEnabled: () -> Boolean = { true },
    private val isWebSearchEnabled: () -> Boolean = { true }
) {

    val capabilities: List<CapabilitySpec> = listOf(
        CapabilitySpec(
            capability = LichiCapability.BROWSER,
            name = "Lichi Browser",
            purpose = "Visible full Chromium browser for web navigation, searches, and interactive page browsing.",
            whenToUse = "When the user wants to see a website on screen, browse search results visibly, open links, scroll or interact with web pages, or explicit browser requests ('browser kholo', 'Google par search karo', 'website kholo').",
            whenNotToUse = "When the user asks for a quick factual answer or live news summary in chat without needing visible webpage navigation (use WEB_SEARCH instead).",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "SEARCH",
                    description = "Search Google, YouTube, or Bing in the visible browser.",
                    requiredParameters = listOf("query"),
                    optionalParameters = listOf("engine"),
                    exampleArguments = mapOf("query" to "PUBG mobile official website", "engine" to "google")
                ),
                ActionSpec(
                    actionName = "NAVIGATE",
                    description = "Navigate directly to a URL in the visible browser.",
                    requiredParameters = listOf("url"),
                    exampleArguments = mapOf("url" to "https://www.apple.com")
                ),
                ActionSpec(
                    actionName = "CLICK_CANDIDATE",
                    description = "Click an ordinal search result candidate (e.g. 1st, 2nd, 3rd) on the active browser page.",
                    requiredParameters = listOf("index"),
                    exampleArguments = mapOf("index" to "1")
                ),
                ActionSpec(
                    actionName = "SCROLL_DOWN",
                    description = "Scroll down the current active web page.",
                    requiredParameters = emptyList(),
                    exampleArguments = emptyMap()
                ),
                ActionSpec(
                    actionName = "SCROLL_UP",
                    description = "Scroll up the current active web page.",
                    requiredParameters = emptyList(),
                    exampleArguments = emptyMap()
                ),
                ActionSpec(
                    actionName = "FIND_ON_PAGE",
                    description = "Find and highlight specific text or sections on the active web page.",
                    requiredParameters = listOf("target"),
                    exampleArguments = mapOf("target" to "download")
                ),
                ActionSpec(
                    actionName = "BACK",
                    description = "Navigate back to the previous page in history.",
                    requiredParameters = emptyList(),
                    exampleArguments = emptyMap()
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via Chromium URL change, page title, or search candidate extraction."
        ),
        CapabilitySpec(
            capability = LichiCapability.WEB_SEARCH,
            name = "Real-Time Web Intelligence",
            purpose = "Retrieve live real-time factual data, current prices, live scores, and current news into the chat conversation.",
            whenToUse = "When user asks about current events, today's news, latest gadget prices, weather, or facts requiring real-time internet verification.",
            whenNotToUse = "When user wants to browse websites visually in the browser (use BROWSER instead) or asks general timeless questions (use CHAT instead).",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "SEARCH",
                    description = "Retrieve web sources and answers for a specific informational query.",
                    requiredParameters = listOf("query"),
                    optionalParameters = listOf("news", "images"),
                    exampleArguments = mapOf("query" to "iPhone 17 Pro price", "news" to "false")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via search response containing citations, sources, and verified content.",
            isAvailable = isWebSearchEnabled
        ),
        CapabilitySpec(
            capability = LichiCapability.ANDROID_AGENT,
            name = "Autonomous Phone Agent V2",
            purpose = "Automate device tasks across third-party Android apps using screen perception and accessibility.",
            whenToUse = "When user wants to interact with native installed apps, send messages in WhatsApp/Instagram, or perform complex multi-step device workflows.",
            whenNotToUse = "When the user only wants to place a direct phone call (use CALLS instead) or open a website (use BROWSER instead).",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "AUTOMATE",
                    description = "Execute a natural language task on the phone using screen inspection and actions.",
                    requiredParameters = listOf("task"),
                    optionalParameters = listOf("app"),
                    exampleArguments = mapOf("task" to "Send a message to Aditya saying hello", "app" to "instagram")
                ),
                ActionSpec(
                    actionName = "OPEN_APP",
                    description = "Launch an installed Android application.",
                    requiredParameters = listOf("app"),
                    exampleArguments = mapOf("app" to "instagram")
                )
            ),
            riskLevel = RiskLevel.HIGH,
            verificationDescription = "Verified via Android Accessibility UI node inspection and screen perception state.",
            isAvailable = isAutonomousAgentEnabled
        ),
        CapabilitySpec(
            capability = LichiCapability.CALLS,
            name = "Universal Call Engine",
            purpose = "Place direct phone calls to contacts or telephone numbers with SIM management and disambiguation.",
            whenToUse = "When user asks to call a person or telephone number ('Rahul ko call karo', 'call 9876543210').",
            whenNotToUse = "When user asks how to call or asks questions about calling without wanting to initiate an actual call.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "CALL",
                    description = "Initiate an outgoing telephone call to a contact name or phone number.",
                    requiredParameters = listOf("target"),
                    exampleArguments = mapOf("target" to "Rahul")
                )
            ),
            requiresPermissions = listOf(android.Manifest.permission.CALL_PHONE),
            riskLevel = RiskLevel.HIGH,
            verificationDescription = "Verified via Android TelecomManager call state and intent initiation result."
        ),
        CapabilitySpec(
            capability = LichiCapability.MEDIA_YOUTUBE,
            name = "Media & YouTube",
            purpose = "Search and play music, videos, and songs on YouTube or Spotify.",
            whenToUse = "When user wants to play a song, video, or playlist ('YouTube par Arijit Singh ka gana lagao', 'play Believer on Spotify').",
            whenNotToUse = "When user asks a factual question about a singer or video without requesting playback.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "PLAY",
                    description = "Search and immediately launch playback for a media query.",
                    requiredParameters = listOf("query"),
                    optionalParameters = listOf("app"),
                    exampleArguments = mapOf("query" to "Arijit Singh latest romantic songs", "app" to "youtube")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via YouTube/Spotify package launch intent resolution."
        ),
        CapabilitySpec(
            capability = LichiCapability.DEVICE_CONTROL,
            name = "Device System Controls",
            purpose = "Adjust system settings such as volume, brightness, and flashlight.",
            whenToUse = "When user wants to adjust phone volume, brightness, or toggle torch.",
            whenNotToUse = "When adjusting settings inside a third-party app.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "SET_SETTING",
                    description = "Control a specific hardware or system setting.",
                    requiredParameters = listOf("setting", "action"),
                    optionalParameters = listOf("value"),
                    exampleArguments = mapOf("setting" to "volume", "action" to "increase")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via system setting state change."
        ),
        CapabilitySpec(
            capability = LichiCapability.SKILL_MANAGEMENT,
            name = "Skill Management",
            purpose = "Install, configure, or inspect skills for Lichi AI.",
            whenToUse = "When user explicitly manages skills ('install skill', 'list skills', 'enable skill').",
            whenNotToUse = "For normal tasks that simply utilize installed skills.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "MANAGE_SKILL",
                    description = "Execute a skill management operation.",
                    requiredParameters = listOf("operation"),
                    exampleArguments = mapOf("operation" to "list")
                )
            ),
            riskLevel = RiskLevel.MEDIUM,
            verificationDescription = "Verified via SkillRepository update confirmation."
        ),
        CapabilitySpec(
            capability = LichiCapability.TIME_REMINDER,
            name = "Lichi Time Engine",
            purpose = "Native offline alarms, reminders, recurring routines, calendar schedules, and task checklists.",
            whenToUse = "When user wants to set an alarm, reminder, timer, recurring schedule, or asks about scheduled alarms/reminders (e.g. 'alarm lagao 7 baje', 'remind me to call Rahul', 'everyday 6am reminder', 'show my alarms').",
            whenNotToUse = "When user asks general questions or unrelated web/phone tasks.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "CREATE",
                    description = "Create a new alarm, reminder, routine, or task.",
                    requiredParameters = listOf("input"),
                    exampleArguments = mapOf("input" to "kal subah 8 baje alarm lagao")
                ),
                ActionSpec(
                    actionName = "LIST",
                    description = "List all alarms or reminders.",
                    requiredParameters = emptyList(),
                    exampleArguments = emptyMap()
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via Android AlarmManager scheduling and local ReminderStore."
        ),
        CapabilitySpec(
            capability = LichiCapability.TERMINAL,
            name = "Lichi Terminal V3",
            purpose = "Local Termux process shell execution, remote SSH terminal sessions, PTY control, and SFTP file management.",
            whenToUse = "When the user asks to run shell commands, check server status via SSH, inspect terminal output, or manage remote files ('terminal kholo', 'SSH karo', 'server disk usage check karo', 'run ls -la').",
            whenNotToUse = "When the user asks for web searches or normal chat conversations.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "EXECUTE",
                    description = "Execute a shell command locally in Termux or on remote SSH session.",
                    requiredParameters = listOf("command"),
                    optionalParameters = listOf("backend", "host", "user"),
                    exampleArguments = mapOf("command" to "df -h", "backend" to "LOCAL_TERMUX")
                ),
                ActionSpec(
                    actionName = "SSH_CONNECT",
                    description = "Establish a remote SSH session to a target host.",
                    requiredParameters = listOf("host"),
                    optionalParameters = listOf("user", "port"),
                    exampleArguments = mapOf("host" to "192.168.1.100", "user" to "ubuntu", "port" to "22")
                ),
                ActionSpec(
                    actionName = "OPEN",
                    description = "Open the interactive Lichi Terminal V3 interface.",
                    requiredParameters = emptyList(),
                    exampleArguments = emptyMap()
                )
            ),
            riskLevel = RiskLevel.MEDIUM,
            verificationDescription = "Verified via exit code, output inspection, and terminal process execution snapshot."
        ),
        CapabilitySpec(
            capability = LichiCapability.CHAT,
            name = "Conversational AI",
            purpose = "Conversational explanations, guidance, answering questions, or chit-chat.",
            whenToUse = "When user asks general knowledge questions, requests advice, or has conversational dialogue.",
            whenNotToUse = "When the user is commanding an action or task on the phone or web.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "CONVERSE",
                    description = "Provide a direct helpful spoken/text response.",
                    requiredParameters = emptyList(),
                    exampleArguments = emptyMap()
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Completed upon response emission."
        )
    )

    fun getAvailableCapabilities(): List<CapabilitySpec> =
        capabilities.filter { it.isAvailable() }

    fun findSpec(capability: LichiCapability): CapabilitySpec? =
        capabilities.firstOrNull { it.capability == capability }

    /**
     * Formats the capability catalog into a structured prompt section for the LLM.
     */
    fun formatCatalogForPrompt(): String {
        return buildString {
            append("AVAILABLE CAPABILITIES:\n")
            for (cap in getAvailableCapabilities()) {
                append("• [${cap.capability.name}] - ${cap.name}\n")
                append("  Purpose: ${cap.purpose}\n")
                append("  When to use: ${cap.whenToUse}\n")
                append("  When NOT to use: ${cap.whenNotToUse}\n")
                append("  Supported Actions: ${cap.supportedActions.joinToString(", ") { "${it.actionName}(${it.requiredParameters.joinToString()})" }}\n")
                append("  Risk Level: ${cap.riskLevel}\n\n")
            }
        }
    }
}

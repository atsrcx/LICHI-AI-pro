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
                    optionalParameters = listOf("news", "images", "domain"),
                    exampleArguments = mapOf("query" to "iPhone 17 Pro price", "news" to "false")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via search response containing citations, sources, and verified content.",
            isAvailable = isWebSearchEnabled
        ),
        CapabilitySpec(
            capability = LichiCapability.DORK_SEARCH,
            name = "Advanced Dork Search",
            purpose = "Controlled advanced public-web search supporting site:, intitle:, inurl:, filetype:, and exact phrases.",
            whenToUse = "When a query requires specific search operators, filetype filtering (e.g. filetype:pdf), or exact title/URL constraints on the public web.",
            whenNotToUse = "For credential hunting or unauthorized security exploits (which are strictly forbidden).",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "DORK_SEARCH",
                    description = "Execute structured search operators on public web sources.",
                    requiredParameters = listOf("query"),
                    optionalParameters = listOf("site", "fileType", "inTitle"),
                    exampleArguments = mapOf("query" to "site:developer.android.com \"VoiceInteractionService\"")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via structured dork operator query execution and search results."
        ),
        CapabilitySpec(
            capability = LichiCapability.SITE_SEARCH,
            name = "Domain Site Search",
            purpose = "Search and crawl specifically within a targeted website domain.",
            whenToUse = "When user asks to search specifically within a single site (e.g. 'Search Google Android docs for VoiceInteractionService').",
            whenNotToUse = "For open general web queries with no target domain.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "SITE_SEARCH",
                    description = "Search within a targeted domain and extract relevant pages.",
                    requiredParameters = listOf("domain", "query"),
                    optionalParameters = listOf("maxPages"),
                    exampleArguments = mapOf("domain" to "developer.android.com", "query" to "VoiceInteractionService")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via domain-constrained search and internal link extraction."
        ),
        CapabilitySpec(
            capability = LichiCapability.DEEP_SEARCH,
            name = "Autonomous Deep Search",
            purpose = "Multi-step in-depth web research pipeline decomposing complex queries, comparing multi-provider sources, and synthesizing facts.",
            whenToUse = "When user asks complex questions requiring multi-angle research, deep factual comparisons, or comprehensive investigations.",
            whenNotToUse = "For quick single-fact lookups or simple chit-chat.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "DEEP_SEARCH",
                    description = "Execute multi-query search, deduplication, content extraction, and synthesis.",
                    requiredParameters = listOf("query"),
                    optionalParameters = listOf("maxBudget"),
                    exampleArguments = mapOf("query" to "Best laptops under ₹80,000 comparison")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via multi-provider responses, deduplicated sources, and fact verification."
        ),
        CapabilitySpec(
            capability = LichiCapability.RESEARCH,
            name = "Structured Research & Synthesis",
            purpose = "Conduct structured research producing questions, sources, evidence, claims, consensus, and explicit disagreement reporting.",
            whenToUse = "When user needs a structured investigation where sources might disagree, requiring transparent claim-by-claim verification.",
            whenNotToUse = "When the user wants simple navigational commands in the browser.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "RESEARCH",
                    description = "Conduct structured multi-source research with conflict detection.",
                    requiredParameters = listOf("topic"),
                    optionalParameters = listOf("queries"),
                    exampleArguments = mapOf("topic" to "Quantum computing roadmap 2026")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via structured research report containing sources, claims, and agreement scores."
        ),
        CapabilitySpec(
            capability = LichiCapability.NAVIGATE,
            name = "Browser Navigate",
            purpose = "Navigate to URLs, go back, forward, or reload in the Chromium browser engine.",
            whenToUse = "When the user or plan requires opening a URL or moving through browser history.",
            whenNotToUse = "For purely text-based search queries without a target URL.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "OPEN_URL",
                    description = "Navigate directly to a URL.",
                    requiredParameters = listOf("url"),
                    exampleArguments = mapOf("url" to "https://news.ycombinator.com")
                ),
                ActionSpec(
                    actionName = "BACK",
                    description = "Navigate back in history.",
                    requiredParameters = emptyList(),
                    exampleArguments = emptyMap()
                ),
                ActionSpec(
                    actionName = "FORWARD",
                    description = "Navigate forward in history.",
                    requiredParameters = emptyList(),
                    exampleArguments = emptyMap()
                ),
                ActionSpec(
                    actionName = "RELOAD",
                    description = "Reload current page.",
                    requiredParameters = emptyList(),
                    exampleArguments = emptyMap()
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via page URL change or reload completion."
        ),
        CapabilitySpec(
            capability = LichiCapability.EXTRACT,
            name = "Content Extraction",
            purpose = "Extract structured text, tables, links, headings, and prices with temporal validity (CURRENT, HISTORICAL, ESTIMATED).",
            whenToUse = "When content from the active browser page or target URL needs to be parsed into structured data.",
            whenNotToUse = "When merely navigating without needing data extraction.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "EXTRACT",
                    description = "Extract structured content from the current web page.",
                    requiredParameters = emptyList(),
                    optionalParameters = listOf("target"),
                    exampleArguments = mapOf("target" to "ALL")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via extracted text, table, or price records."
        ),
        CapabilitySpec(
            capability = LichiCapability.FIND_ON_PAGE,
            name = "Find On Page",
            purpose = "Search and highlight specific text or keywords on the current active web page.",
            whenToUse = "When user wants to find a specific phrase or keyword on the loaded page.",
            whenNotToUse = "When searching across the whole internet.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "FIND",
                    description = "Find and highlight keyword matches on the active page.",
                    requiredParameters = listOf("keyword"),
                    exampleArguments = mapOf("keyword" to "specifications")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via WebView search matches count."
        ),
        CapabilitySpec(
            capability = LichiCapability.COMPARE,
            name = "Cross-Source Comparison",
            purpose = "Compare normalized attributes (price, specs, features, availability) across multiple products or sources.",
            whenToUse = "When user asks to compare 2 or more products, services, or claims ('Compare laptop A and laptop B').",
            whenNotToUse = "For single-item research.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "COMPARE",
                    description = "Generate normalized comparison matrix across entities.",
                    requiredParameters = listOf("entities"),
                    optionalParameters = listOf("criteria"),
                    exampleArguments = mapOf("entities" to "MacBook Air M3 vs Dell XPS 13")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via comparison matrix containing verified attributes."
        ),
        CapabilitySpec(
            capability = LichiCapability.VERIFY,
            name = "Fact & Page Verification",
            purpose = "Verify factual claims against official, primary, or reputable secondary web sources.",
            whenToUse = "When a claim needs explicit verification against official sources (e.g. checking listed price on apple.com).",
            whenNotToUse = "When no factual claim is provided.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "VERIFY",
                    description = "Verify a specific claim against official or primary sources.",
                    requiredParameters = listOf("claim"),
                    optionalParameters = listOf("domain"),
                    exampleArguments = mapOf("claim" to "iPhone 17 base storage is 256GB", "domain" to "apple.com")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via fact evidence score and official source match."
        ),
        CapabilitySpec(
            capability = LichiCapability.FORMS,
            name = "Form Fill & Submit",
            purpose = "Discover input fields, type values, select options, and submit forms safely.",
            whenToUse = "When user wants to fill out a web form, search box, or input field on a webpage.",
            whenNotToUse = "For password or sensitive financial credential submission without user authorization.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "FILL_AND_SUBMIT",
                    description = "Fill inputs and submit the form.",
                    requiredParameters = listOf("fields"),
                    optionalParameters = listOf("submit"),
                    exampleArguments = mapOf("fields" to "query: Android SDK", "submit" to "true")
                )
            ),
            riskLevel = RiskLevel.MEDIUM,
            verificationDescription = "Verified via field value mutation and form submission event."
        ),
        CapabilitySpec(
            capability = LichiCapability.DOWNLOAD,
            name = "Browser Download",
            purpose = "Initiate and track file downloads safely from the web.",
            whenToUse = "When user wants to download a file, PDF, or APK from a web link.",
            whenNotToUse = "When user simply wants to view content without downloading.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "DOWNLOAD",
                    description = "Download a file from a URL.",
                    requiredParameters = listOf("url"),
                    optionalParameters = listOf("filename"),
                    exampleArguments = mapOf("url" to "https://example.com/doc.pdf")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via Android DownloadManager queue record."
        ),
        CapabilitySpec(
            capability = LichiCapability.UPLOAD,
            name = "Browser Upload",
            purpose = "Select and upload a file to a web form input with user authorization.",
            whenToUse = "When a webpage requires uploading an attachment or document.",
            whenNotToUse = "For unauthorized data transmission.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "UPLOAD",
                    description = "Upload a file to a file input on the page.",
                    requiredParameters = listOf("filePath"),
                    optionalParameters = listOf("targetId"),
                    exampleArguments = mapOf("filePath" to "/storage/doc.pdf")
                )
            ),
            riskLevel = RiskLevel.MEDIUM,
            verificationDescription = "Verified via file input value attachment."
        ),
        CapabilitySpec(
            capability = LichiCapability.MULTI_TAB,
            name = "Multi-Tab Manager",
            purpose = "Manage multiple browser tabs, categorize research/source tabs, and switch or close tabs.",
            whenToUse = "When multi-tab browsing is requested (open new tab, switch tab, close tab).",
            whenNotToUse = "For single-tab browsing.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "MANAGE_TABS",
                    description = "Perform tab management action (OPEN, CLOSE, SWITCH, LIST).",
                    requiredParameters = listOf("action"),
                    optionalParameters = listOf("tabId", "url"),
                    exampleArguments = mapOf("action" to "OPEN", "url" to "https://google.com")
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via active tab ID update in TabManager."
        ),
        CapabilitySpec(
            capability = LichiCapability.PAGE_SUMMARY,
            name = "Page Summary",
            purpose = "Distill the active web page into an executive summary, bullet points, and extracted facts.",
            whenToUse = "When user asks to summarize the currently opened webpage or article.",
            whenNotToUse = "When user wants full raw HTML or page navigation.",
            supportedActions = listOf(
                ActionSpec(
                    actionName = "SUMMARIZE",
                    description = "Generate a structured summary of the current page.",
                    requiredParameters = emptyList(),
                    exampleArguments = emptyMap()
                )
            ),
            riskLevel = RiskLevel.LOW,
            verificationDescription = "Verified via summary text containing page title and key points."
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
                    requiredParameters = listOf("title"),
                    optionalParameters = listOf("time", "is_alarm", "recurrence", "input"),
                    exampleArguments = mapOf("title" to "Buy groceries", "time" to "18:00")
                ),
                ActionSpec(
                    actionName = "LIST",
                    description = "List all alarms or reminders.",
                    requiredParameters = emptyList(),
                    exampleArguments = emptyMap()
                ),
                ActionSpec(
                    actionName = "DELETE",
                    description = "Delete a specific alarm or reminder by title or ID.",
                    requiredParameters = listOf("title"),
                    exampleArguments = mapOf("title" to "Morning walk")
                ),
                ActionSpec(
                    actionName = "COMPLETE",
                    description = "Mark a reminder or task as completed.",
                    requiredParameters = listOf("title"),
                    exampleArguments = mapOf("title" to "Call Rahul")
                ),
                ActionSpec(
                    actionName = "SNOOZE",
                    description = "Snooze an active or upcoming reminder.",
                    requiredParameters = emptyList(),
                    optionalParameters = listOf("title", "minutes"),
                    exampleArguments = mapOf("minutes" to "10")
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

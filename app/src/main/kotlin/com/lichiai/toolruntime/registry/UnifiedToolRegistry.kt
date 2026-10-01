package com.lichiai.toolruntime.registry

import android.content.Context
import com.lichiai.agent.bridge.AutonomousAgentTool
import com.lichiai.agent.executor.AgentActionExecutor
import com.lichiai.browser.BrowserController
import com.lichiai.calling.action.CallActionExecutor
import com.lichiai.calling.engine.UniversalCallEngine
import com.lichiai.memory.manager.LichiMemoryEngine
import com.lichiai.skill.repository.SkillRepository
import com.lichiai.terminal.core.TerminalManager
import com.lichiai.terminal.task.TerminalTaskManager
import com.lichiai.time.adapter.TimeCapabilityAdapter
import com.lichiai.time.manager.ReminderManager
import com.lichiai.toolruntime.core.LichiTool
import com.lichiai.toolruntime.model.ToolCategory
import com.lichiai.toolruntime.model.ToolDefinition
import com.lichiai.toolruntime.model.WorldRuntimeState
import com.lichiai.toolruntime.tools.AndroidAutomateTool
import com.lichiai.toolruntime.tools.AndroidOpenAppTool
import com.lichiai.toolruntime.tools.BrowserActionTool
import com.lichiai.toolruntime.tools.BrowserExtractTool
import com.lichiai.toolruntime.tools.BrowserOpenTool
import com.lichiai.toolruntime.tools.CallActionTool
import com.lichiai.toolruntime.tools.CallContactTool
import com.lichiai.toolruntime.tools.DeviceStateTool
import com.lichiai.toolruntime.tools.DeviceVolumeTool
import com.lichiai.toolruntime.tools.MediaPlayTool
import com.lichiai.toolruntime.tools.MemorySearchTool
import com.lichiai.toolruntime.tools.MemoryStoreTool
import com.lichiai.toolruntime.tools.SkillListTool
import com.lichiai.toolruntime.tools.TerminalExecuteTool
import com.lichiai.toolruntime.tools.TimeCreateAlarmTool
import com.lichiai.toolruntime.tools.TimeCreateReminderTool
import com.lichiai.toolruntime.tools.TimeListRemindersTool
import com.lichiai.toolruntime.tools.WebSearchTool
import com.lichiai.toolruntime.tools.WebVerifyTool
import com.lichiai.web.WebIntelligenceManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative Unified Tool Registry for Lichi AI.
 * Holds all real executable capability bindings and enforces strict availability.
 */
class UnifiedToolRegistry(
    private val context: Context? = null,
    val webIntelligenceManager: WebIntelligenceManager? = context?.let { WebIntelligenceManager.getInstance(it) },
    val browserController: BrowserController? = null,
    val universalCallEngine: UniversalCallEngine? = null,
    val callActionExecutor: CallActionExecutor? = null,
    val autonomousAgentTool: AutonomousAgentTool? = null,
    val actionExecutor: AgentActionExecutor? = context?.let { com.lichiai.agent.Agent.getInstance(it).actionExecutor },
    val terminalManager: TerminalManager? = context?.let { TerminalManager.getInstance(it) },
    val reminderManager: ReminderManager? = context?.let { ReminderManager(it) },
    val timeCapabilityAdapter: TimeCapabilityAdapter? = context?.let { TimeCapabilityAdapter(it) },
    val memoryEngine: LichiMemoryEngine? = context?.let { LichiMemoryEngine.getInstance(it) },
    val skillRepository: SkillRepository? = context?.let { SkillRepository.getInstance(it) },
    val onNavigateToBrowser: () -> Unit = {},
    val onNavigateToTerminal: () -> Unit = {}
) {

    private val tools = ConcurrentHashMap<String, LichiTool>()

    init {
        registerDefaultTools()
    }

    private fun registerDefaultTools() {
        // Web
        webIntelligenceManager?.let {
            register(WebSearchTool(it))
            register(WebVerifyTool(it))
        }

        // Browser
        browserController?.let { bc ->
            register(com.lichiai.toolruntime.tools.BrowserTaskTool(bc, onNavigateToBrowser))
            register(BrowserOpenTool(bc, onNavigateToBrowser))
            register(BrowserExtractTool(bc))
            register(BrowserActionTool(bc, onNavigateToBrowser))
        }

        // Android
        if (context != null && actionExecutor != null) {
            register(AndroidOpenAppTool(context, actionExecutor))
        }
        autonomousAgentTool?.let { aat ->
            register(AndroidAutomateTool(aat))
        }

        // Telephony
        universalCallEngine?.let { uce ->
            register(CallContactTool(uce))
        }
        callActionExecutor?.let { cae ->
            register(CallActionTool(cae))
        }

        // Terminal
        if (context != null && terminalManager != null) {
            val taskManager = TerminalTaskManager.getInstance(context)
            register(TerminalExecuteTool(terminalManager, taskManager, onNavigateToTerminal))
        }

        // Device
        if (context != null) {
            register(DeviceVolumeTool(context))
            register(DeviceStateTool(context))
        }

        // Time / Alarms
        timeCapabilityAdapter?.let {
            register(TimeCreateAlarmTool(it))
            register(TimeCreateReminderTool(it))
            register(TimeListRemindersTool(it))
        }

        // Memory
        memoryEngine?.let {
            register(MemorySearchTool(it))
            register(MemoryStoreTool(it))
        }

        // Skills & Media
        skillRepository?.let {
            register(SkillListTool(it))
        }
        if (context != null) {
            register(MediaPlayTool(context))
        }
    }

    fun register(tool: LichiTool) {
        tools[tool.definition.id] = tool
    }

    fun getTool(id: String): LichiTool? = tools[id]

    fun getAllTools(): List<LichiTool> = tools.values.toList()

    fun getAvailableTools(): List<LichiTool> = tools.values.filter { it.definition.isAvailable() }

    /**
     * Relevance-based tool discovery to prevent token explosions.
     * Selects only the tool definitions that match the user goal and active context,
     * along with a compact family index for any remaining capabilities.
     */
    fun selectRelevantTools(goal: String, currentContext: WorldRuntimeState? = null): List<ToolDefinition> {
        val lowerGoal = goal.lowercase(Locale.ROOT)
        val available = getAvailableTools()

        val activeCategories = mutableSetOf<ToolCategory>()

        // 1. Keyword-based category activation
        if (containsAny(lowerGoal, "alarm", "wake", "timer", "reminder", "remind", "baje", "subah", "shaam", "baj")) {
            activeCategories.add(ToolCategory.TIME_REMINDER)
        }
        if (containsAny(lowerGoal, "browser", "website", "chrome", "page", "url", "open site", "scroll", "kholo site")) {
            activeCategories.add(ToolCategory.BROWSER)
        }
        if (containsAny(lowerGoal, "search", "google", "kab", "kya", "latest", "price", "who", "when", "news", "today", "current", "weather", "rate", "dhundo", "pata karo", "batao", "puja", "diwali", "match", "score")) {
            activeCategories.add(ToolCategory.WEB)
        }
        if (containsAny(lowerGoal, "call", "phone", "dial", "lagao", "mila", "contact", "ring", "uthana", "kaato")) {
            activeCategories.add(ToolCategory.TELEPHONY)
        }
        if (containsAny(lowerGoal, "terminal", "bash", "ssh", "command", "cmd", "shell", "run script")) {
            activeCategories.add(ToolCategory.TERMINAL)
        }
        if (containsAny(lowerGoal, "volume", "awaz", "sound", "mute", "battery", "charge", "setting")) {
            activeCategories.add(ToolCategory.DEVICE)
        }
        if (containsAny(lowerGoal, "open", "kholo", "whatsapp", "instagram", "insta", "youtube", "app", "tap", "click", "screen", "automate")) {
            activeCategories.add(ToolCategory.ANDROID)
            activeCategories.add(ToolCategory.MEDIA)
        }
        if (containsAny(lowerGoal, "remember", "yaad", "mera naam", "birthday", "preference", "memory", "store this")) {
            activeCategories.add(ToolCategory.MEMORY)
        }
        if (containsAny(lowerGoal, "skill", "skills", "plugin")) {
            activeCategories.add(ToolCategory.SKILLS)
        }
        if (containsAny(lowerGoal, "play", "gana", "music", "song", "video", "sunao")) {
            activeCategories.add(ToolCategory.MEDIA)
        }

        // 2. Context-based activation
        if (currentContext?.currentBrowserUrl?.isNotBlank() == true) {
            activeCategories.add(ToolCategory.BROWSER)
        }
        if (currentContext?.activeCallState?.isNotBlank() == true) {
            activeCategories.add(ToolCategory.TELEPHONY)
        }

        // 3. If no specific category was triggered or broad request, activate foundational set
        if (activeCategories.isEmpty()) {
            activeCategories.addAll(listOf(ToolCategory.WEB, ToolCategory.ANDROID, ToolCategory.DEVICE, ToolCategory.TIME_REMINDER))
        }

        // Return tool definitions belonging to active categories
        val matched = available
            .filter { it.definition.category in activeCategories }
            .map { it.definition }

        return if (matched.isNotEmpty()) matched else available.take(6).map { it.definition }
    }

    /**
     * Formats selected tool schemas into a compact JSON schema prompt string.
     */
    fun formatToolsForPrompt(selectedTools: List<ToolDefinition>): String {
        return buildString {
            append("AVAILABLE TOOLS FOR THIS TURN:\n")
            for (tool in selectedTools) {
                append("• ID: \"${tool.id}\" (${tool.name})\n")
                append("  Description: ${tool.description}\n")
                append("  Risk Level: ${tool.riskLevel}\n")
                if (tool.parameters.isNotEmpty()) {
                    append("  Parameters:\n")
                    for (p in tool.parameters) {
                        val req = if (p.required) "required" else "optional"
                        val enumStr = if (!p.enumValues.isNullOrEmpty()) " [allowed: ${p.enumValues.joinToString()}]" else ""
                        append("    - \"${p.name}\" (${p.type}, $req): ${p.description}$enumStr\n")
                    }
                } else {
                    append("  Parameters: none\n")
                }
            }
        }
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it) }
    }
}

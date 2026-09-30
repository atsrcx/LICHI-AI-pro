package com.lichiai.intent.model

import com.lichiai.calling.intent.CallIntent
import com.lichiai.skill.model.Skill
import com.lichiai.skill.router.SkillManagementRequest
import kotlinx.serialization.Serializable

/**
 * All recognized high-level Lichi capabilities.
 * Architecturally peer capabilities — never entangled or cross-contaminated.
 */
enum class LichiCapability(val id: String, val displayName: String) {
    BROWSER("browser", "Lichi Browser"),
    WEB_SEARCH("web_search", "Real-Time Web Intelligence"),
    DORK_SEARCH("dork_search", "Advanced Dork Search"),
    SITE_SEARCH("site_search", "Domain Site Search"),
    DEEP_SEARCH("deep_search", "Autonomous Deep Search"),
    RESEARCH("research", "Structured Research"),
    NAVIGATE("navigate", "Browser Navigate"),
    EXTRACT("extract", "Content Extraction"),
    FIND_ON_PAGE("find_on_page", "Find On Page"),
    COMPARE("compare", "Cross-Source Comparison"),
    VERIFY("verify", "Fact & Page Verification"),
    FORMS("forms", "Form Fill & Submit"),
    DOWNLOAD("download", "Browser Download"),
    UPLOAD("upload", "Browser Upload"),
    MULTI_TAB("multi_tab", "Multi-Tab Manager"),
    PAGE_SUMMARY("page_summary", "Page Summary"),
    INSPECT_PAGE("inspect_page", "Browser Inspection & DevTools"),
    ANDROID_AGENT("android_agent", "Autonomous Phone Agent V2"),
    CALLS("calls", "Universal Call Engine"),
    MEDIA_YOUTUBE("media_youtube", "Media & YouTube"),
    DEVICE_CONTROL("device_control", "Device System Controls"),
    TIME_REMINDER("time_reminder", "Lichi Time Engine"),
    TERMINAL("terminal", "Lichi Terminal V3"),
    SKILL_MANAGEMENT("skill_management", "Skill Management"),
    CHAT("chat", "Conversational AI")
}

enum class BrowserActionType {
    NAVIGATE,
    SEARCH,
    CLICK_CANDIDATE,
    SCROLL_DOWN,
    SCROLL_UP,
    FIND_ON_PAGE,
    BACK,
    FORWARD,
    RELOAD,
    NEW_TAB,
    CLOSE_TAB
}

enum class DeviceSettingType {
    VOLUME,
    BRIGHTNESS,
    FLASHLIGHT,
    WIFI,
    BLUETOOTH
}

enum class DeviceActionType {
    INCREASE,
    DECREASE,
    TURN_ON,
    TURN_OFF,
    SET_VALUE
}

enum class ResolutionSource {
    DETERMINISTIC_RULE,
    SEMANTIC_MATCH,
    CONTEXT_FOLLOWUP,
    CORRECTION,
    LLM_CLASSIFICATION,
    FALLBACK
}

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum class IntentType {
    CONVERSATION,
    QUESTION,
    INFORMATION_REQUEST,
    WEB_RESEARCH,
    WEB_SEARCH,
    VISIBLE_BROWSER_TASK,
    ANDROID_ACTION,
    AUTONOMOUS_TASK,
    MEDIA_ACTION,
    DEVICE_ACTION,
    CALL_ACTION,
    MESSAGE_ACTION,
    FILE_ACTION,
    MEMORY_ACTION,
    FOLLOW_UP,
    CORRECTION,
    CANCELLATION,
    CONFIRMATION,
    CLARIFICATION,
    CONTINUATION,
    MULTI_STEP_TASK
}

enum class AmbiguityLevel {
    KNOWN,
    LIKELY,
    AMBIGUOUS,
    UNKNOWN
}

enum class FreshnessRequirement {
    CURRENT,
    LATEST,
    HISTORICAL,
    NONE
}

enum class ActiveTaskState {
    NO_ACTIVE_TASK,
    ACTIVE_TASK,
    WAITING_FOR_CONFIRMATION,
    WAITING_FOR_CLARIFICATION,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Structured semantic model capturing user intent, true goal, entities, constraints, and desired outcome.
 * "LLM understands WHAT THE USER MEANS. Existing Lichi systems decide HOW THAT INTENT IS EXECUTED."
 */
@Serializable
data class IntentUnderstanding(
    val userGoal: String,
    val intentType: IntentType,
    val domain: String,
    val action: String? = null,
    val target: String? = null,
    val entities: Map<String, String> = emptyMap(),
    val constraints: List<String> = emptyList(),
    val desiredOutcome: String? = null,
    val interactionMode: String? = null,
    val informationRequirement: String? = null,
    val freshnessRequirement: FreshnessRequirement = FreshnessRequirement.NONE,
    val contextReferences: List<String> = emptyList(),
    val isFollowUp: Boolean = false,
    val isCorrection: Boolean = false,
    val isCancellation: Boolean = false,
    val isConfirmation: Boolean = false,
    val modifiesPreviousTask: Boolean = false,
    val missingInformation: List<String> = emptyList(),
    val ambiguity: AmbiguityLevel = AmbiguityLevel.KNOWN,
    val executionIntent: String? = null,
    val capabilityHint: LichiCapability? = null
)

/**
 * Snapshot of conversational and application state used for contextual reference
 * and correction resolution (e.g. "doosra result kholo", "ismein download dhundo", "nahi browser mein karo").
 */
data class IntentContext(
    val conversationId: String? = null,
    val activeCapability: LichiCapability? = null,
    val currentBrowserUrl: String? = null,
    val currentBrowserTitle: String? = null,
    val browserCandidates: List<String> = emptyList(),
    val lastSearchQuery: String? = null,
    val lastUserGoal: String? = null,
    val lastAssistantResponse: String? = null,
    val lastActionTimestamp: Long = 0L,
    val recentResults: List<String> = emptyList(),
    val lastExecutedCapability: LichiCapability? = null,
    val recentEntities: Map<String, String> = emptyMap(),
    val lastActionType: String? = null,
    val pendingConfirmation: String? = null,
    val activeTaskState: ActiveTaskState = ActiveTaskState.NO_ACTIVE_TASK,
    val recentTurns: List<Pair<String, String>> = emptyList(),
    val lastPlatform: String? = null,
    val lastUsername: String? = null,
    val lastProfileUrl: String? = null,
    val lastPlatformProfile: com.lichiai.spy.model.PlatformProfile? = null,
    val recentProfiles: List<com.lichiai.spy.model.PlatformProfile> = emptyList(),
    val lastTerminalSessionId: String? = null,
    val lastTerminalCwd: String? = null,
    val lastCallContact: String? = null,
    val lastSelectedSource: String? = null
)

/**
 * Formally resolved intent ready for execution by a specific capability.
 * ARCHITECTURAL RULE: ResolvedIntent is an execution adapter and is NOT an intent classifier.
 * It encapsulates structured execution arguments produced by UniversalLlmPlanner / TaskPlan steps.
 */
sealed class ResolvedIntent {
    abstract val capability: LichiCapability
    abstract val naturalAcknowledgment: String

    data class BrowserTask(
        val action: BrowserActionType,
        val query: String? = null,
        val url: String? = null,
        val searchEngine: String = "google",
        val candidateIndex: Int? = null,
        val findTarget: String? = null,
        val rawPrompt: String = "",
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.BROWSER
    }

    data class WebSearchTask(
        val query: String,
        val isImageSearch: Boolean = false,
        val isNewsSearch: Boolean = false,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.WEB_SEARCH
    }

    data class DorkSearchTask(
        val query: String,
        val site: String? = null,
        val fileType: String? = null,
        val exactPhrase: String? = null,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.DORK_SEARCH
    }

    data class SiteSearchTask(
        val domain: String,
        val query: String,
        val maxPages: Int = 3,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.SITE_SEARCH
    }

    data class DeepSearchTask(
        val query: String,
        val maxBudgetQueries: Int = 3,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.DEEP_SEARCH
    }

    data class ResearchTask(
        val topic: String,
        val queries: List<String> = emptyList(),
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.RESEARCH
    }

    data class NavigateTask(
        val url: String,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.NAVIGATE
    }

    data class ExtractTask(
        val target: String = "ALL",
        val url: String? = null,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.EXTRACT
    }

    data class FindOnPageTask(
        val keyword: String,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.FIND_ON_PAGE
    }

    data class CompareTask(
        val entities: List<String>,
        val criteria: List<String> = emptyList(),
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.COMPARE
    }

    data class VerifyTask(
        val claim: String,
        val domain: String? = null,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.VERIFY
    }

    data class FormsTask(
        val fieldValues: Map<String, String>,
        val submit: Boolean = true,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.FORMS
    }

    data class DownloadTask(
        val url: String,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.DOWNLOAD
    }

    data class UploadTask(
        val targetIdOrIndex: String,
        val filePath: String,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.UPLOAD
    }

    data class MultiTabTask(
        val action: String, // OPEN, CLOSE, SWITCH, LIST
        val tabId: String? = null,
        val url: String? = null,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.MULTI_TAB
    }

    data class PageSummaryTask(
        val focus: String? = null,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.PAGE_SUMMARY
    }

    data class InspectTask(
        val mode: String = "FULL_INSPECTION",
        val query: String = "",
        val showUi: Boolean = false,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.INSPECT_PAGE
    }

    data class AndroidAgentTask(
        val goal: String,
        val targetApp: String? = null,
        val matchedSkills: List<Skill> = emptyList(),
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.ANDROID_AGENT
    }

    data class CallTask(
        val callIntent: CallIntent,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.CALLS
    }

    data class MediaTask(
        val query: String,
        val targetApp: String = "youtube",
        val isPlayback: Boolean = true,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.MEDIA_YOUTUBE
    }

    data class DeviceControlTask(
        val setting: DeviceSettingType,
        val action: DeviceActionType,
        val value: String? = null,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.DEVICE_CONTROL
    }

    data class TimeReminderTask(
        val rawInput: String,
        val action: String = "CREATE",
        val title: String? = null,
        val time: String? = null,
        val timeMs: Long? = null,
        val isAlarm: Boolean = false,
        val recurrence: String? = null,
        val id: String? = null,
        val minutes: Int = 10,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.TIME_REMINDER
    }

    data class SkillManagementTask(
        val request: SkillManagementRequest,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.SKILL_MANAGEMENT
    }

    data class TerminalTask(
        val command: String,
        val action: String = "EXECUTE",
        val host: String? = null,
        val user: String? = null,
        val port: Int = 22,
        val backendType: String = "LOCAL_TERMUX",
        val rawPrompt: String = "",
        override val naturalAcknowledgment: String = "Terminal task execute kar raha hoon."
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.TERMINAL
    }

    data class MultiStepTask(
        val steps: List<ResolvedIntent>,
        val description: String,
        override val naturalAcknowledgment: String
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = steps.firstOrNull()?.capability ?: LichiCapability.BROWSER
    }

    data class Clarification(
        val question: String,
        val options: List<String> = emptyList(),
        override val naturalAcknowledgment: String = question
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.CHAT
    }

    data class Cancellation(
        val reason: String = "User requested cancellation",
        override val naturalAcknowledgment: String = "Task cancel kar diya."
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.CHAT
    }

    data class ResumeTask(
        val reason: String = "User requested resume",
        override val naturalAcknowledgment: String = "Task resume kar rahi hoon..."
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.CHAT
    }

    data class TaskInterruption(
        val reason: String = "User requested interruption",
        val nextIntent: ResolvedIntent? = null,
        override val naturalAcknowledgment: String = "Pehle wala task pause karke naya task shuru kar rahi hoon..."
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = nextIntent?.capability ?: LichiCapability.CHAT
    }

    data class ContextualQuestion(
        val question: String,
        val referenceContext: String? = null,
        override val naturalAcknowledgment: String = ""
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.CHAT
    }

    data class NormalChat(
        val prompt: String,
        override val naturalAcknowledgment: String = ""
    ) : ResolvedIntent() {
        override val capability: LichiCapability get() = LichiCapability.CHAT
    }
}

/**
 * Result of the Universal Intent Engine resolution.
 */
data class IntentResolutionResult(
    val intent: ResolvedIntent,
    val confidence: Float,
    val source: ResolutionSource,
    val normalizedInput: String,
    val rationale: String,
    val understanding: IntentUnderstanding? = null
)

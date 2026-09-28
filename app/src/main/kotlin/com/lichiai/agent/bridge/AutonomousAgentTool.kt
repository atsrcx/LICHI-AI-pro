package com.lichiai.agent.bridge

import android.content.Context
import com.lichiai.agent.Agent
import com.lichiai.agent.model.AgentRunResult

/**
 * AutonomousAgentTool acts as the unified, minimal high-level bridge between
 * Lichi's assistant layer and the isolated Autonomous Agent V2 execution runtime.
 *
 * It represents ONE tool capability rather than exposing low-level actions.
 */
class AutonomousAgentTool(context: Context) {

    companion object {
        const val TOOL_NAME = "autonomous_phone_agent"
        const val TOOL_DESCRIPTION = "Automates complex, multi-step UI navigation, app interactions, and phone tasks."
    }

    private val agent = Agent.getInstance(context)

    val liveStatus: kotlinx.coroutines.flow.StateFlow<com.lichiai.agent.model.AgentLiveStatus>
        get() = agent.liveStatus

    suspend fun execute(
        taskDescription: String,
        matchedSkills: List<com.lichiai.skill.model.Skill> = emptyList(),
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)? = null
    ): AgentRunResult {
        return agent.executeTask(taskDescription, matchedSkills, onProgress)
    }

    fun isEnabled(): Boolean = agent.settingsRepository.settings.value.enabled

    fun isConfigured(): Boolean = agent.settingsRepository.isConfigValid()
}

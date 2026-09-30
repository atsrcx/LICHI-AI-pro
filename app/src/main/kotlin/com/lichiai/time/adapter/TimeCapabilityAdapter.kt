package com.lichiai.time.adapter

import android.content.Context
import com.lichiai.time.manager.ReminderManager
import com.lichiai.time.model.ReminderItem
import com.lichiai.time.model.ReminderType
import com.lichiai.time.parser.ParsedTimeAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TimeExecutionOutcome(
    val isSuccess: Boolean,
    val naturalSpeech: String,
    val createdItem: ReminderItem? = null,
    val requiresScreenNavigation: Boolean = false
)

/**
 * Execution adapter for Lichi Time Engine.
 * Executes structured reminder/alarm requests from UniversalTaskOrchestratorV2 / UniversalLlmPlanner.
 */
class TimeCapabilityAdapter(private val context: Context) {

    val manager = ReminderManager(context)
    private val timeFormat = SimpleDateFormat("h:mm a, d MMM", Locale.getDefault())

    private fun formatTime(epochMs: Long): String {
        return timeFormat.format(Date(epochMs))
    }

    /**
     * Executes structured reminder/alarm commands decided by the canonical planner.
     */
    suspend fun executeStructured(
        action: String,
        title: String? = null,
        timeMs: Long? = null,
        isAlarm: Boolean = false,
        recurrenceRule: String? = null,
        idOrQuery: String? = null,
        snoozeMinutes: Int = 10,
        rawInput: String = ""
    ): TimeExecutionOutcome {
        return when (action.uppercase()) {
            "LIST" -> {
                val list = manager.repository.getSnapshot()
                val active = list.filter { it.isActive }
                val speech = if (active.isEmpty()) {
                    "Aapke paas koi active alarm ya reminder nahi hai."
                } else {
                    val summary = active.take(5).joinToString(", ") { "${it.title} (${formatTime(it.triggerEpochMs)})" }
                    "Aapke paas ${active.size} active reminders/alarms hain: $summary"
                }
                TimeExecutionOutcome(
                    isSuccess = true,
                    naturalSpeech = speech,
                    requiresScreenNavigation = true
                )
            }
            "DELETE" -> {
                val list = manager.repository.getSnapshot()
                val target = if (!idOrQuery.isNullOrBlank()) {
                    list.firstOrNull { it.id == idOrQuery }
                        ?: list.firstOrNull { it.title.contains(idOrQuery, ignoreCase = true) }
                } else list.firstOrNull { it.isActive }

                if (target != null) {
                    manager.deleteReminder(target.id)
                    TimeExecutionOutcome(
                        isSuccess = true,
                        naturalSpeech = "${target.title} ko delete kar diya gaya hai."
                    )
                } else {
                    TimeExecutionOutcome(
                        isSuccess = false,
                        naturalSpeech = "Delete karne ke liye koi matching reminder nahi mila."
                    )
                }
            }
            "COMPLETE" -> {
                val list = manager.repository.getSnapshot()
                val target = if (!idOrQuery.isNullOrBlank()) {
                    list.firstOrNull { it.id == idOrQuery }
                        ?: list.firstOrNull { it.title.contains(idOrQuery, ignoreCase = true) }
                } else list.firstOrNull { it.isActive }

                if (target != null) {
                    manager.completeReminder(target.id)
                    TimeExecutionOutcome(
                        isSuccess = true,
                        naturalSpeech = "${target.title} ko complete mark kar diya gaya hai."
                    )
                } else {
                    TimeExecutionOutcome(
                        isSuccess = false,
                        naturalSpeech = "Complete karne ke liye reminder nahi mila."
                    )
                }
            }
            "SNOOZE" -> {
                val list = manager.repository.getSnapshot()
                val target = if (!idOrQuery.isNullOrBlank()) {
                    list.firstOrNull { it.id == idOrQuery }
                        ?: list.firstOrNull { it.title.contains(idOrQuery, ignoreCase = true) }
                } else list.firstOrNull { it.isActive }

                if (target != null) {
                    manager.snoozeReminder(target.id, snoozeMinutes)
                    TimeExecutionOutcome(
                        isSuccess = true,
                        naturalSpeech = "${target.title} ko $snoozeMinutes minute ke liye snooze kar diya hai."
                    )
                } else {
                    TimeExecutionOutcome(
                        isSuccess = false,
                        naturalSpeech = "Snooze karne ke liye reminder nahi mila."
                    )
                }
            }
            "CREATE" -> {
                if (timeMs != null || !title.isNullOrBlank()) {
                    val finalTitle = title?.ifBlank { if (isAlarm) "Alarm" else "Reminder" } ?: (if (isAlarm) "Alarm" else "Reminder")
                    val epoch = timeMs ?: (System.currentTimeMillis() + 10 * 60 * 1000L)
                    val item = ReminderItem(
                        id = UUID.randomUUID().toString(),
                        title = finalTitle,
                        triggerEpochMs = epoch,
                        type = if (isAlarm) ReminderType.ALARM else ReminderType.REMINDER
                    )
                    manager.createReminder(item)
                    TimeExecutionOutcome(
                        isSuccess = true,
                        naturalSpeech = "${item.title} scheduled for ${formatTime(item.triggerEpochMs)}.",
                        createdItem = item
                    )
                } else if (rawInput.isNotBlank()) {
                    handleQuery(rawInput)
                } else {
                    TimeExecutionOutcome(
                        isSuccess = false,
                        naturalSpeech = "Please provide reminder time and title."
                    )
                }
            }
            else -> {
                if (rawInput.isNotBlank()) {
                    handleQuery(rawInput)
                } else {
                    TimeExecutionOutcome(
                        isSuccess = false,
                        naturalSpeech = "Time action '$action' processed."
                    )
                }
            }
        }
    }

    /**
     * Backward-compatibility helper for raw time queries.
     */
    suspend fun handleQuery(rawInput: String): TimeExecutionOutcome {
        val result = manager.executeNaturalCommand(rawInput)
        return when (result) {
            is ParsedTimeAction.Create -> {
                TimeExecutionOutcome(
                    isSuccess = true,
                    naturalSpeech = result.naturalSpeech,
                    createdItem = result.item
                )
            }
            is ParsedTimeAction.ListReminders -> {
                TimeExecutionOutcome(
                    isSuccess = true,
                    naturalSpeech = result.naturalSpeech,
                    requiresScreenNavigation = true
                )
            }
            is ParsedTimeAction.Delete -> {
                TimeExecutionOutcome(
                    isSuccess = true,
                    naturalSpeech = result.naturalSpeech
                )
            }
            is ParsedTimeAction.Complete -> {
                TimeExecutionOutcome(
                    isSuccess = true,
                    naturalSpeech = result.naturalSpeech
                )
            }
            is ParsedTimeAction.Snooze -> {
                TimeExecutionOutcome(
                    isSuccess = true,
                    naturalSpeech = result.naturalSpeech
                )
            }
            is ParsedTimeAction.AskClarification -> {
                TimeExecutionOutcome(
                    isSuccess = false,
                    naturalSpeech = result.question
                )
            }
            is ParsedTimeAction.NotRecognized -> {
                TimeExecutionOutcome(
                    isSuccess = false,
                    naturalSpeech = "Reminder time ya details samajh nahi aayi. Kripya time aur title specify karein."
                )
            }
        }
    }
}

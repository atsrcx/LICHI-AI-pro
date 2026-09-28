package com.lichiai.time.adapter

import android.content.Context
import com.lichiai.time.manager.ReminderManager
import com.lichiai.time.model.ReminderItem
import com.lichiai.time.parser.OfflineReminderIntentParser
import com.lichiai.time.parser.ParsedTimeAction

data class TimeExecutionOutcome(
    val isSuccess: Boolean,
    val naturalSpeech: String,
    val createdItem: ReminderItem? = null,
    val requiresScreenNavigation: Boolean = false
)

class TimeCapabilityAdapter(private val context: Context) {

    val manager = ReminderManager(context)

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

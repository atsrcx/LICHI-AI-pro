package com.lichiai.time.manager

import android.content.Context
import com.lichiai.time.data.ReminderRepository
import com.lichiai.time.model.ReminderItem
import com.lichiai.time.parser.OfflineReminderIntentParser
import com.lichiai.time.parser.ParsedTimeAction
import com.lichiai.time.scheduler.AlarmScheduler

class ReminderManager(private val context: Context) {

    val repository = ReminderRepository(context)
    val scheduler = AlarmScheduler(context)

    suspend fun createReminder(item: ReminderItem): ReminderItem {
        repository.upsert(item)
        scheduler.schedule(item)
        return item
    }

    suspend fun updateReminder(item: ReminderItem): ReminderItem {
        repository.upsert(item)
        if (item.isActive) {
            scheduler.schedule(item)
        } else {
            scheduler.cancel(item.id)
        }
        return item
    }

    suspend fun deleteReminder(id: String) {
        scheduler.cancel(id)
        repository.delete(id)
    }

    suspend fun completeReminder(id: String) {
        scheduler.cancel(id)
        repository.complete(id)
        val updated = repository.getById(id)
        if (updated != null && updated.isActive) {
            scheduler.schedule(updated)
        }
    }

    suspend fun snoozeReminder(id: String, minutes: Int = 10) {
        scheduler.cancel(id)
        repository.snooze(id, minutes)
        val updated = repository.getById(id)
        if (updated != null) {
            scheduler.schedule(updated)
        }
    }

    suspend fun dismissReminder(id: String) {
        scheduler.cancel(id)
        repository.dismiss(id)
        val updated = repository.getById(id)
        if (updated != null && updated.isActive) {
            scheduler.schedule(updated)
        }
    }

    suspend fun toggleActive(id: String) {
        repository.toggleActive(id)
        val updated = repository.getById(id) ?: return
        if (updated.isActive) {
            scheduler.schedule(updated)
        } else {
            scheduler.cancel(id)
        }
    }

    suspend fun executeNaturalCommand(input: String): ParsedTimeAction {
        val parsed = OfflineReminderIntentParser.parse(input)
        when (parsed) {
            is ParsedTimeAction.Create -> {
                createReminder(parsed.item)
            }
            is ParsedTimeAction.Delete -> {
                val list = repository.getSnapshot()
                val target = list.firstOrNull { it.title.contains(parsed.targetQuery, ignoreCase = true) }
                if (target != null) {
                    deleteReminder(target.id)
                }
            }
            is ParsedTimeAction.Complete -> {
                val list = repository.getSnapshot()
                val target = list.firstOrNull { it.title.contains(parsed.targetQuery, ignoreCase = true) }
                    ?: list.firstOrNull { it.isActive }
                if (target != null) {
                    completeReminder(target.id)
                }
            }
            is ParsedTimeAction.Snooze -> {
                val list = repository.getSnapshot()
                val target = list.firstOrNull { it.title.contains(parsed.targetQuery, ignoreCase = true) }
                    ?: list.firstOrNull { it.isActive }
                if (target != null) {
                    snoozeReminder(target.id, parsed.minutes)
                }
            }
            else -> {}
        }
        return parsed
    }
}

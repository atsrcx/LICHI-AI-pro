package com.lichiai.time.data

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.lichiai.time.model.ChecklistItem
import com.lichiai.time.model.DeliveryLogEntry
import com.lichiai.time.model.ReminderCategory
import com.lichiai.time.model.ReminderConflict
import com.lichiai.time.model.ReminderDiagnostics
import com.lichiai.time.model.ReminderItem
import com.lichiai.time.model.ReminderStatus
import com.lichiai.time.model.ReminderType
import com.lichiai.time.recurrence.RecurrenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ReminderRepository(private val context: Context) {

    private val store = ReminderStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    val reminders: StateFlow<List<ReminderItem>> = store.remindersFlow
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    private val _diagnostics = MutableStateFlow(computeDiagnostics(emptyList()))
    val diagnostics: StateFlow<ReminderDiagnostics> = _diagnostics.asStateFlow()

    init {
        scope.launch {
            reminders.collect { list ->
                _diagnostics.value = computeDiagnostics(list)
            }
        }
    }

    suspend fun getSnapshot(): List<ReminderItem> = store.snapshot()

    suspend fun getById(id: String): ReminderItem? = getSnapshot().firstOrNull { it.id == id }

    suspend fun upsert(item: ReminderItem) {
        store.upsert(item)
    }

    suspend fun delete(id: String) {
        store.delete(id)
    }

    suspend fun complete(id: String) {
        val current = getById(id) ?: return
        val nextOccurrence = RecurrenceEngine.computeNextOccurrence(current)

        if (nextOccurrence != null && current.isRecurring) {
            // Recurring reminder: log delivery and move trigger to next calculated occurrence
            val updatedLog = current.deliveryLog + DeliveryLogEntry(
                timestampEpochMs = System.currentTimeMillis(),
                event = "COMPLETED_AND_RESCHEDULED",
                details = "Rescheduled to next recurrence"
            )
            store.upsert(
                current.copy(
                    triggerEpochMs = nextOccurrence,
                    status = ReminderStatus.ACTIVE,
                    snoozeCount = 0,
                    deliveryLog = updatedLog
                )
            )
        } else {
            // One-time reminder: mark completed
            val updatedLog = current.deliveryLog + DeliveryLogEntry(
                timestampEpochMs = System.currentTimeMillis(),
                event = "COMPLETED",
                details = "Marked as completed"
            )
            store.upsert(
                current.copy(
                    status = ReminderStatus.COMPLETED,
                    deliveryLog = updatedLog
                )
            )
        }
    }

    suspend fun snooze(id: String, minutes: Int = 10) {
        val current = getById(id) ?: return
        val newTrigger = System.currentTimeMillis() + (minutes * 60 * 1000L)
        val updatedLog = current.deliveryLog + DeliveryLogEntry(
            timestampEpochMs = System.currentTimeMillis(),
            event = "SNOOZED",
            details = "Snoozed for $minutes minutes"
        )
        store.upsert(
            current.copy(
                triggerEpochMs = newTrigger,
                status = ReminderStatus.SNOOZED,
                snoozeCount = current.snoozeCount + 1,
                deliveryLog = updatedLog
            )
        )
    }

    suspend fun dismiss(id: String) {
        val current = getById(id) ?: return
        val nextOccurrence = RecurrenceEngine.computeNextOccurrence(current)

        if (nextOccurrence != null && current.isRecurring) {
            val updatedLog = current.deliveryLog + DeliveryLogEntry(
                timestampEpochMs = System.currentTimeMillis(),
                event = "DISMISSED_AND_RESCHEDULED",
                details = "Dismissed, next occurrence scheduled"
            )
            store.upsert(
                current.copy(
                    triggerEpochMs = nextOccurrence,
                    status = ReminderStatus.ACTIVE,
                    snoozeCount = 0,
                    deliveryLog = updatedLog
                )
            )
        } else {
            val updatedLog = current.deliveryLog + DeliveryLogEntry(
                timestampEpochMs = System.currentTimeMillis(),
                event = "DISMISSED",
                details = "Dismissed"
            )
            store.upsert(
                current.copy(
                    status = ReminderStatus.DISMISSED,
                    deliveryLog = updatedLog
                )
            )
        }
    }

    suspend fun toggleChecklistItem(reminderId: String, checklistItemId: String) {
        val current = getById(reminderId) ?: return
        val updatedChecklist = current.checklist.map {
            if (it.id == checklistItemId) it.copy(isCompleted = !it.isCompleted) else it
        }
        store.upsert(current.copy(checklist = updatedChecklist))
    }

    suspend fun toggleActive(id: String) {
        val current = getById(id) ?: return
        val newStatus = if (current.status == ReminderStatus.PAUSED) {
            ReminderStatus.ACTIVE
        } else if (current.status == ReminderStatus.ACTIVE) {
            ReminderStatus.PAUSED
        } else {
            ReminderStatus.ACTIVE
        }
        store.upsert(current.copy(status = newStatus))
    }

    suspend fun recordDelivery(id: String, success: Boolean, details: String = "") {
        val current = getById(id) ?: return
        val updatedLog = current.deliveryLog + DeliveryLogEntry(
            timestampEpochMs = System.currentTimeMillis(),
            event = if (success) "DELIVERED" else "DELIVERY_FAILED",
            details = details
        )
        store.upsert(
            current.copy(
                lastDeliveredEpochMs = System.currentTimeMillis(),
                deliveryLog = updatedLog
            )
        )
    }

    fun detectConflicts(): List<ReminderConflict> {
        val list = reminders.value.filter { it.isActive }.sortedBy { it.triggerEpochMs }
        val conflicts = mutableListOf<ReminderConflict>()

        for (i in 0 until list.size - 1) {
            val r1 = list[i]
            val r2 = list[i + 1]
            val diffMs = Math.abs(r2.triggerEpochMs - r1.triggerEpochMs)
            val diffMin = diffMs / (60 * 1000L)
            // If two alarms/reminders are scheduled within 10 minutes of each other
            if (diffMin <= 10) {
                conflicts.add(ReminderConflict(reminder1 = r1, reminder2 = r2, timeDifferenceMinutes = diffMin))
            }
        }
        return conflicts
    }

    fun computeDiagnostics(list: List<ReminderItem>): ReminderDiagnostics {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
        val notifEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()

        var deliveries = 0
        var failures = 0
        var lastDelivery: Long? = null

        list.forEach { item ->
            item.deliveryLog.forEach { log ->
                if (log.event == "DELIVERED" || log.event == "COMPLETED") deliveries++
                if (log.event == "DELIVERY_FAILED") failures++
            }
            if (item.lastDeliveredEpochMs != null) {
                if (lastDelivery == null || item.lastDeliveredEpochMs > lastDelivery!!) {
                    lastDelivery = item.lastDeliveredEpochMs
                }
            }
        }

        val activeList = list.filter { it.isActive }
        val nextScheduled = activeList.minByOrNull { it.triggerEpochMs }?.triggerEpochMs

        return ReminderDiagnostics(
            totalReminders = list.size,
            activeAlarms = activeList.count { it.isAlarm },
            activeReminders = activeList.count { !it.isAlarm },
            canScheduleExactAlarms = canScheduleExact,
            isNotificationsEnabled = notifEnabled,
            lastScheduledEventEpochMs = nextScheduled,
            lastDeliveredEventEpochMs = lastDelivery,
            deliverySuccessCount = deliveries,
            failureCount = failures
        )
    }

    /**
     * Standard RFC-5545 iCalendar (.ics) export
     */
    fun exportIcsCalendar(): String {
        val list = reminders.value
        val sb = StringBuilder()
        val icsDateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//Lichi AI//Lichi Time Engine 1.0//EN\r\n")
        sb.append("CALSCALE:GREGORIAN\r\n")

        for (r in list) {
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:${r.id}@lichi.ai\r\n")
            sb.append("DTSTAMP:${icsDateFormat.format(Date(r.createdAtEpochMs))}\r\n")
            sb.append("DTSTART:${icsDateFormat.format(Date(r.triggerEpochMs))}\r\n")
            sb.append("SUMMARY:${r.title.replace("\n", " ")}\r\n")
            if (r.description.isNotBlank()) {
                sb.append("DESCRIPTION:${r.description.replace("\n", "\\n")}\r\n")
            }
            sb.append("CATEGORIES:${r.category.displayName}\r\n")
            sb.append("STATUS:${if (r.isCompleted) "COMPLETED" else "CONFIRMED"}\r\n")
            sb.append("END:VEVENT\r\n")
        }

        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    fun createBackupJson(): String {
        return json.encodeToString(ListSerializer(ReminderItem.serializer()), reminders.value)
    }

    suspend fun restoreBackupJson(jsonString: String): Boolean {
        return runCatching {
            val list = json.decodeFromString(ListSerializer(ReminderItem.serializer()), jsonString)
            store.save(list)
            true
        }.getOrDefault(false)
    }
}

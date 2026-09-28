package com.lichiai.time.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class ReminderType {
    REMINDER,
    ALARM,
    TASK,
    ROUTINE,
    LOCATION
}

enum class ReminderStatus {
    ACTIVE,
    SNOOZED,
    COMPLETED,
    DISMISSED,
    MISSED,
    PAUSED
}

enum class RecurrenceType {
    NONE,
    DAILY,
    WEEKDAYS,     // Monday to Friday
    WEEKENDS,     // Saturday & Sunday
    WEEKLY,       // Same day every week
    MONTHLY,      // Same day of month (e.g. 15th)
    YEARLY,       // Same day of year
    CUSTOM_DAYS   // Specific days (e.g. Mon, Wed, Fri)
}

enum class ReminderCategory(val displayName: String) {
    GENERAL("General"),
    WORK("Work"),
    PERSONAL("Personal"),
    HEALTH("Health"),
    MEDICINE("Medicine"),
    BILLS("Bills & Finance"),
    CALL("Call"),
    STUDY("Study")
}

@Serializable
data class ChecklistItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val isCompleted: Boolean = false
)

@Serializable
data class DeliveryLogEntry(
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val event: String,
    val details: String = ""
)

@Serializable
data class LocationTriggerInfo(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 150f,
    val placeName: String,
    val triggerOnEntry: Boolean = true
)

@Serializable
data class ReminderItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val type: ReminderType = ReminderType.REMINDER,
    val triggerEpochMs: Long,
    val originalTriggerEpochMs: Long = triggerEpochMs,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val status: ReminderStatus = ReminderStatus.ACTIVE,
    val recurrence: RecurrenceType = RecurrenceType.NONE,
    val customDaysOfWeek: List<Int> = emptyList(), // Calendar.SUNDAY (1) .. Calendar.SATURDAY (7)
    val preAlertMinutes: List<Int> = emptyList(), // e.g. [5, 15, 30] minutes before
    val checklist: List<ChecklistItem> = emptyList(),
    val snoozeCount: Int = 0,
    val snoozeDurationMinutes: Int = 10,
    val alarmToneUri: String? = null,
    val vibrate: Boolean = true,
    val ttsAnnounce: Boolean = true,
    val category: ReminderCategory = ReminderCategory.GENERAL,
    val locationTrigger: LocationTriggerInfo? = null,
    val lastDeliveredEpochMs: Long? = null,
    val deliveryLog: List<DeliveryLogEntry> = emptyList()
) {
    val isAlarm: Boolean get() = type == ReminderType.ALARM
    val isRecurring: Boolean get() = recurrence != RecurrenceType.NONE
    val hasChecklist: Boolean get() = checklist.isNotEmpty()
    val isCompleted: Boolean get() = status == ReminderStatus.COMPLETED
    val isSnoozed: Boolean get() = status == ReminderStatus.SNOOZED
    val isMissed: Boolean get() = status == ReminderStatus.MISSED
    val isActive: Boolean get() = status == ReminderStatus.ACTIVE || status == ReminderStatus.SNOOZED
}

enum class ReminderTab(val label: String) {
    TODAY("Today"),
    TOMORROW("Tomorrow"),
    UPCOMING("Upcoming"),
    CALENDAR("Calendar"),
    RECURRING("Recurring"),
    ALARMS("Alarms"),
    TASKS("Tasks"),
    COMPLETED("Completed"),
    MISSED("Missed")
}

data class ReminderConflict(
    val reminder1: ReminderItem,
    val reminder2: ReminderItem,
    val timeDifferenceMinutes: Long
)

data class ReminderDiagnostics(
    val totalReminders: Int,
    val activeAlarms: Int,
    val activeReminders: Int,
    val canScheduleExactAlarms: Boolean,
    val isNotificationsEnabled: Boolean,
    val lastScheduledEventEpochMs: Long?,
    val lastDeliveredEventEpochMs: Long?,
    val deliverySuccessCount: Int,
    val failureCount: Int
)

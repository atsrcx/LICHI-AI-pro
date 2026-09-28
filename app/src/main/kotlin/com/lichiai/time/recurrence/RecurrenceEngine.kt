package com.lichiai.time.recurrence

import com.lichiai.time.model.RecurrenceType
import com.lichiai.time.model.ReminderItem
import java.util.Calendar

object RecurrenceEngine {

    /**
     * Computes the next trigger time in epoch milliseconds strictly AFTER `referenceEpochMs` (usually current time).
     * Returns null if no next occurrence (e.g. non-recurring event).
     */
    fun computeNextOccurrence(
        item: ReminderItem,
        referenceEpochMs: Long = System.currentTimeMillis()
    ): Long? {
        if (item.recurrence == RecurrenceType.NONE) return null

        val cal = Calendar.getInstance().apply {
            timeInMillis = item.originalTriggerEpochMs
        }

        // If the base trigger time is already in the future compared to reference, return it
        if (cal.timeInMillis > referenceEpochMs) {
            return cal.timeInMillis
        }

        when (item.recurrence) {
            RecurrenceType.DAILY -> {
                while (cal.timeInMillis <= referenceEpochMs) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                return cal.timeInMillis
            }

            RecurrenceType.WEEKDAYS -> {
                do {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                } while (cal.timeInMillis <= referenceEpochMs || dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)
                return cal.timeInMillis
            }

            RecurrenceType.WEEKENDS -> {
                do {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                } while (cal.timeInMillis <= referenceEpochMs || (dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY))
                return cal.timeInMillis
            }

            RecurrenceType.WEEKLY -> {
                while (cal.timeInMillis <= referenceEpochMs) {
                    cal.add(Calendar.WEEK_OF_YEAR, 1)
                }
                return cal.timeInMillis
            }

            RecurrenceType.MONTHLY -> {
                val originalDayOfMonth = Calendar.getInstance().apply { timeInMillis = item.originalTriggerEpochMs }.get(Calendar.DAY_OF_MONTH)
                while (cal.timeInMillis <= referenceEpochMs) {
                    cal.add(Calendar.MONTH, 1)
                    // Clamp to max days in month (e.g. Jan 31 -> Feb 28/29)
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.DAY_OF_MONTH, originalDayOfMonth.coerceAtMost(maxDay))
                }
                return cal.timeInMillis
            }

            RecurrenceType.YEARLY -> {
                val originalMonth = Calendar.getInstance().apply { timeInMillis = item.originalTriggerEpochMs }.get(Calendar.MONTH)
                val originalDay = Calendar.getInstance().apply { timeInMillis = item.originalTriggerEpochMs }.get(Calendar.DAY_OF_MONTH)
                while (cal.timeInMillis <= referenceEpochMs) {
                    cal.add(Calendar.YEAR, 1)
                    val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    cal.set(Calendar.MONTH, originalMonth)
                    cal.set(Calendar.DAY_OF_MONTH, originalDay.coerceAtMost(maxDay))
                }
                return cal.timeInMillis
            }

            RecurrenceType.CUSTOM_DAYS -> {
                val allowedDays = item.customDaysOfWeek.ifEmpty { listOf(cal.get(Calendar.DAY_OF_WEEK)) }
                do {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    val day = cal.get(Calendar.DAY_OF_WEEK)
                } while (cal.timeInMillis <= referenceEpochMs || day !in allowedDays)
                return cal.timeInMillis
            }

            RecurrenceType.NONE -> return null
        }
    }

    /**
     * Calculates epoch milliseconds for all pending pre-alerts for a given trigger time.
     */
    fun computePreAlertTimestamps(
        triggerEpochMs: Long,
        preAlertMinutes: List<Int>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): List<Long> {
        return preAlertMinutes
            .map { minutes -> triggerEpochMs - (minutes * 60 * 1000L) }
            .filter { it > nowEpochMs }
            .distinct()
            .sorted()
    }
}

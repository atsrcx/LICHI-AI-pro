package com.lichiai.time.parser

import com.lichiai.time.model.RecurrenceType
import com.lichiai.time.model.ReminderCategory
import com.lichiai.time.model.ReminderItem
import com.lichiai.time.model.ReminderType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

sealed class ParsedTimeAction {
    data class Create(
        val item: ReminderItem,
        val naturalSpeech: String
    ) : ParsedTimeAction()

    data class ListReminders(
        val filterQuery: String = "",
        val naturalSpeech: String
    ) : ParsedTimeAction()

    data class Delete(
        val targetQuery: String,
        val naturalSpeech: String
    ) : ParsedTimeAction()

    data class Complete(
        val targetQuery: String,
        val naturalSpeech: String
    ) : ParsedTimeAction()

    data class Snooze(
        val targetQuery: String = "",
        val minutes: Int = 10,
        val naturalSpeech: String
    ) : ParsedTimeAction()

    data class AskClarification(
        val question: String
    ) : ParsedTimeAction()

    object NotRecognized : ParsedTimeAction()
}

object OfflineReminderIntentParser {

    fun parse(rawInput: String): ParsedTimeAction {
        val input = rawInput.trim()
        val lower = input.lowercase(Locale.ROOT)

        if (lower.isBlank()) return ParsedTimeAction.NotRecognized

        // 1. Check for List / Show Queries
        if (isListQuery(lower)) {
            return ParsedTimeAction.ListReminders(
                filterQuery = extractFilterKeyword(lower),
                naturalSpeech = "Aapke alarms aur reminders open kar raha hoon."
            )
        }

        // 2. Check for Snooze Queries
        if (isSnoozeQuery(lower)) {
            val minutes = extractDurationMinutes(lower) ?: 10
            return ParsedTimeAction.Snooze(
                minutes = minutes,
                naturalSpeech = "$minutes minute ke liye snooze kar diya."
            )
        }

        // 3. Check for Complete / Dismiss Queries
        if (isCompleteQuery(lower)) {
            val target = extractTargetSubject(lower, listOf("complete", "done", "khatam", "dismiss", "hatao", "ho gaya"))
            return ParsedTimeAction.Complete(
                targetQuery = target,
                naturalSpeech = if (target.isNotBlank()) "'$target' complete mark kar diya." else "Reminder complete mark kar diya."
            )
        }

        // 4. Check for Delete / Cancel Queries
        if (isDeleteQuery(lower)) {
            val target = extractTargetSubject(lower, listOf("delete", "cancel", "hata do", "remove", "band karo"))
            return ParsedTimeAction.Delete(
                targetQuery = target,
                naturalSpeech = if (target.isNotBlank()) "'$target' cancel kar diya gaya hai." else "Reminder delete kar diya."
            )
        }

        // 5. Check for Alarm / Reminder / Routine / Task Creation Queries
        if (isCreationQuery(lower)) {
            return parseCreation(input, lower)
        }

        return ParsedTimeAction.NotRecognized
    }

    private fun isListQuery(lower: String): Boolean {
        return lower.contains("reminders dikhao") || lower.contains("show reminders") ||
                lower.contains("alarm list") || lower.contains("alarms dikhao") ||
                lower.contains("kya reminder") || lower.contains("list reminders") ||
                lower.contains("my reminders") || lower.contains("mere alarms") ||
                lower.contains("today's schedule") || lower.contains("aaj ka schedule")
    }

    private fun isSnoozeQuery(lower: String): Boolean {
        return lower.startsWith("snooze") || lower.contains("snooze karo") ||
                lower.contains("minute baad bajana") || lower.contains("minute baad bajao")
    }

    private fun isCompleteQuery(lower: String): Boolean {
        return lower.contains("mark done") || lower.contains("complete kar do") ||
                lower.contains("done mark karo") || lower.contains("dismiss karo") ||
                lower.contains("dismiss alarm")
    }

    private fun isDeleteQuery(lower: String): Boolean {
        return (lower.contains("delete") || lower.contains("cancel") || lower.contains("remove") || lower.contains("hata do")) &&
                (lower.contains("alarm") || lower.contains("reminder") || lower.contains("routine") || lower.contains("task"))
    }

    private fun isCreationQuery(lower: String): Boolean {
        return lower.contains("alarm") || lower.contains("reminder") || lower.contains("remind me") ||
                lower.contains("yaad dilana") || lower.contains("yaad dilao") || lower.contains("wake me up") ||
                lower.contains("jaga dena") || lower.contains("task add karo") || lower.contains("routine") ||
                lower.contains("minute baad") || lower.contains("ghante baad") || lower.contains("after ") ||
                lower.contains("in ") && (lower.contains("mins") || lower.contains("minutes") || lower.contains("hours"))
    }

    private fun parseCreation(input: String, lower: String): ParsedTimeAction {
        val isAlarm = lower.contains("alarm") || lower.contains("wake me up") || lower.contains("jaga dena") || lower.contains("uthana")
        val isRoutine = lower.contains("routine") || lower.contains("har roz") || lower.contains("everyday") || lower.contains("daily")
        val isTask = lower.contains("task") || lower.contains("todo") || lower.contains("to-do")

        val reminderType = when {
            isAlarm -> ReminderType.ALARM
            isRoutine -> ReminderType.ROUTINE
            isTask -> ReminderType.TASK
            else -> ReminderType.REMINDER
        }

        // Determine recurrence
        val (recurrence, customDays) = extractRecurrence(lower)

        // Determine trigger time
        val triggerEpochMs = extractTriggerTimestamp(lower)

        if (triggerEpochMs == null) {
            return ParsedTimeAction.AskClarification(
                question = "Aap kis time ka ${if (isAlarm) "alarm" else "reminder"} set karna chahte hain?"
            )
        }

        // Extract Title / Description
        val title = extractTitle(input, lower, isAlarm)
        val category = extractCategory(lower)

        val item = ReminderItem(
            title = title,
            type = reminderType,
            triggerEpochMs = triggerEpochMs,
            originalTriggerEpochMs = triggerEpochMs,
            recurrence = recurrence,
            customDaysOfWeek = customDays,
            category = category,
            vibrate = true,
            ttsAnnounce = true
        )

        val timeFormatted = SimpleDateFormat("h:mm a, d MMM", Locale.getDefault()).format(Date(triggerEpochMs))
        val recSpeech = when (recurrence) {
            RecurrenceType.DAILY -> " (Har roz)"
            RecurrenceType.WEEKDAYS -> " (Monday to Friday)"
            RecurrenceType.WEEKLY -> " (Har hafte)"
            else -> ""
        }

        val speech = if (isAlarm) {
            "$timeFormatted ke liye alarm set kar diya gaya hai.$recSpeech"
        } else {
            "'$title' ka reminder $timeFormatted ke liye set ho gaya hai.$recSpeech"
        }

        return ParsedTimeAction.Create(item = item, naturalSpeech = speech)
    }

    private fun extractRecurrence(lower: String): Pair<RecurrenceType, List<Int>> {
        if (lower.contains("har roz") || lower.contains("everyday") || lower.contains("every day") || lower.contains("daily")) {
            return RecurrenceType.DAILY to emptyList()
        }
        if (lower.contains("weekday") || lower.contains("working day") || lower.contains("somwar se shukrawar")) {
            return RecurrenceType.WEEKDAYS to emptyList()
        }
        if (lower.contains("weekend") || lower.contains("shanivar ravivar")) {
            return RecurrenceType.WEEKENDS to emptyList()
        }
        if (lower.contains("har hafte") || lower.contains("weekly") || lower.contains("every week")) {
            return RecurrenceType.WEEKLY to emptyList()
        }
        if (lower.contains("har mahine") || lower.contains("monthly") || lower.contains("every month")) {
            return RecurrenceType.MONTHLY to emptyList()
        }
        if (lower.contains("har saal") || lower.contains("yearly") || lower.contains("every year")) {
            return RecurrenceType.YEARLY to emptyList()
        }
        return RecurrenceType.NONE to emptyList()
    }

    private fun extractTriggerTimestamp(lower: String): Long? {
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance()

        // 1. Relative minutes/hours: "10 minute baad", "in 15 minutes", "2 ghante baad", "aadhe ghante baad"
        if (lower.contains("aadhe ghante") || lower.contains("half an hour") || lower.contains("30 minute")) {
            cal.add(Calendar.MINUTE, 30)
            return cal.timeInMillis
        }

        val relativeMinRegex = Regex("(\\d+)\\s*(?:min|mins|minute|minutes)\\s*(?:baad|after|in)")
        relativeMinRegex.find(lower)?.let { match ->
            val mins = match.groupValues[1].toIntOrNull() ?: 10
            cal.add(Calendar.MINUTE, mins)
            return cal.timeInMillis
        }

        val inMinsRegex = Regex("(?:in|after)\\s*(\\d+)\\s*(?:min|mins|minute|minutes)")
        inMinsRegex.find(lower)?.let { match ->
            val mins = match.groupValues[1].toIntOrNull() ?: 10
            cal.add(Calendar.MINUTE, mins)
            return cal.timeInMillis
        }

        val relativeHourRegex = Regex("(\\d+)\\s*(?:ghante|ghanta|hour|hours)\\s*(?:baad|after|in)")
        relativeHourRegex.find(lower)?.let { match ->
            val hrs = match.groupValues[1].toIntOrNull() ?: 1
            cal.add(Calendar.HOUR_OF_DAY, hrs)
            return cal.timeInMillis
        }

        // 2. Determine target day: today, tomorrow (kal), parson (day after tomorrow), weekdays
        var dayOffset = 0
        if (lower.contains("kal ") || lower.contains("kal subah") || lower.contains("tomorrow")) {
            dayOffset = 1
        } else if (lower.contains("parson") || lower.contains("day after tomorrow")) {
            dayOffset = 2
        }

        // Check weekday mentions (somwar / monday, etc.)
        val targetWeekday = parseWeekday(lower)
        if (targetWeekday != null) {
            val currentDay = cal.get(Calendar.DAY_OF_WEEK)
            var diff = targetWeekday - currentDay
            if (diff <= 0) diff += 7
            dayOffset = diff
        }

        cal.add(Calendar.DAY_OF_YEAR, dayOffset)

        // 3. Time of day: "subah 7 baje", "shaam 6:30", "dopahar 2 baje", "raat 9 baje", "7:30 am", "8 pm", "6:00"
        val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje)?")
        val matches = timeRegex.findAll(lower).toList()

        for (m in matches) {
            val hourRaw = m.groupValues[1].toIntOrNull() ?: continue
            val minuteRaw = m.groupValues[2].toIntOrNull() ?: 0
            val modifier = m.groupValues[3]

            var hour = hourRaw
            val isPmContext = lower.contains("shaam") || lower.contains("raat") || lower.contains("dopahar") ||
                    lower.contains("evening") || lower.contains("night") || lower.contains("afternoon") ||
                    modifier == "pm"
            val isAmContext = lower.contains("subah") || lower.contains("morning") || modifier == "am"

            if (isPmContext && hour < 12) {
                hour += 12
            } else if (isAmContext && hour == 12) {
                hour = 0
            }

            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minuteRaw)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            // If time is earlier than now on the same day, bump to tomorrow
            if (dayOffset == 0 && cal.timeInMillis <= now.timeInMillis) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }

            return cal.timeInMillis
        }

        // Default fallbacks for generic time of day keywords without explicit digits
        if (lower.contains("subah") || lower.contains("morning")) {
            cal.set(Calendar.HOUR_OF_DAY, 8)
            cal.set(Calendar.MINUTE, 0)
            return cal.timeInMillis
        }
        if (lower.contains("shaam") || lower.contains("evening")) {
            cal.set(Calendar.HOUR_OF_DAY, 18)
            cal.set(Calendar.MINUTE, 0)
            return cal.timeInMillis
        }
        if (lower.contains("raat") || lower.contains("night")) {
            cal.set(Calendar.HOUR_OF_DAY, 21)
            cal.set(Calendar.MINUTE, 0)
            return cal.timeInMillis
        }

        return null
    }

    private fun parseWeekday(lower: String): Int? {
        return when {
            lower.contains("somwar") || lower.contains("monday") -> Calendar.MONDAY
            lower.contains("mangalwar") || lower.contains("tuesday") -> Calendar.TUESDAY
            lower.contains("budhwar") || lower.contains("wednesday") -> Calendar.WEDNESDAY
            lower.contains("guruwar") || lower.contains("veervar") || lower.contains("thursday") -> Calendar.THURSDAY
            lower.contains("shukrawar") || lower.contains("friday") -> Calendar.FRIDAY
            lower.contains("shanivar") || lower.contains("saturday") -> Calendar.SATURDAY
            lower.contains("ravivar") || lower.contains("itwar") || lower.contains("sunday") -> Calendar.SUNDAY
            else -> null
        }
    }

    private fun extractTitle(input: String, lower: String, isAlarm: Boolean): String {
        // Strip common trigger prefixes and suffixes
        var clean = input
            .replace(Regex("(?i)^(remind me to|reminder for|set reminder for|set an alarm for|set alarm for|alarm lagao|reminder lagao|yaad dilana ki|yaad dilao|wake me up at|wake me up)"), "")
            .replace(Regex("(?i)(alarm|reminder|lagao|set karo|baje|subah|shaam|raat|kal|tomorrow|today|aaj|parson)"), "")
            .replace(Regex("\\b\\d{1,2}(:\\d{2})?\\s*(am|pm)?\\b"), "")
            .trim()

        clean = clean.replace(Regex("^[\\s,:-]+|[\\s,:-]+$"), "").trim()

        return if (clean.isNotBlank()) {
            clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        } else {
            if (isAlarm) "Alarm" else "Reminder"
        }
    }

    private fun extractCategory(lower: String): ReminderCategory {
        return when {
            lower.contains("dawa") || lower.contains("medicine") || lower.contains("tablet") || lower.contains("doctor") -> ReminderCategory.MEDICINE
            lower.contains("health") || lower.contains("gym") || lower.contains("workout") || lower.contains("paani") || lower.contains("water") -> ReminderCategory.HEALTH
            lower.contains("meeting") || lower.contains("office") || lower.contains("client") || lower.contains("project") -> ReminderCategory.WORK
            lower.contains("bill") || lower.contains("recharge") || lower.contains("pay") || lower.contains("rent") || lower.contains("paisa") -> ReminderCategory.BILLS
            lower.contains("call") || lower.contains("phone") || lower.contains("baat karni") -> ReminderCategory.CALL
            lower.contains("padhai") || lower.contains("study") || lower.contains("exam") || lower.contains("homework") || lower.contains("class") -> ReminderCategory.STUDY
            else -> ReminderCategory.GENERAL
        }
    }

    private fun extractFilterKeyword(lower: String): String {
        return when {
            lower.contains("alarm") -> "alarm"
            lower.contains("medicine") || lower.contains("dawa") -> "medicine"
            lower.contains("meeting") || lower.contains("work") -> "work"
            else -> ""
        }
    }

    private fun extractDurationMinutes(lower: String): Int? {
        val regex = Regex("(\\d+)\\s*(?:min|mins|minute|minutes)")
        return regex.find(lower)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractTargetSubject(lower: String, keywords: List<String>): String {
        var clean = lower
        for (k in keywords) {
            clean = clean.replace(k, "")
        }
        return clean.replace(Regex("(alarm|reminder|task|karo|do|hatao|the|my)"), "").trim()
    }
}

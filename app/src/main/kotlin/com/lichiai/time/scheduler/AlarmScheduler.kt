package com.lichiai.time.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lichiai.time.model.ReminderItem
import com.lichiai.time.model.ReminderType
import com.lichiai.time.receiver.AlarmReceiver
import com.lichiai.time.recurrence.RecurrenceEngine

class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun canScheduleExact(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    /**
     * Schedules a reminder or alarm. Idempotent.
     */
    fun schedule(item: ReminderItem) {
        if (!item.isActive || item.triggerEpochMs <= System.currentTimeMillis()) {
            return
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE_ALARM
            putExtra(AlarmReceiver.EXTRA_REMINDER_ID, item.id)
            putExtra(AlarmReceiver.EXTRA_IS_PRE_ALERT, false)
        }

        val requestCode = item.id.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (item.type == ReminderType.ALARM) {
                // For Alarm clock, use setAlarmClock for full-screen UI priority and exact wake-up
                if (canScheduleExact()) {
                    val showIntent = Intent(context, com.lichiai.time.ui.AlarmActivity::class.java).apply {
                        putExtra(AlarmReceiver.EXTRA_REMINDER_ID, item.id)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val showPendingIntent = PendingIntent.getActivity(
                        context,
                        requestCode,
                        showIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val alarmClockInfo = AlarmManager.AlarmClockInfo(item.triggerEpochMs, showPendingIntent)
                    alarmManager?.setAlarmClock(alarmClockInfo, pendingIntent)
                } else {
                    alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerEpochMs, pendingIntent)
                }
            } else {
                // For standard Reminders / Tasks / Routines
                if (canScheduleExact()) {
                    alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerEpochMs, pendingIntent)
                } else {
                    alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerEpochMs, pendingIntent)
                }
            }

            // Schedule any configured pre-alerts (e.g. 15 mins before)
            schedulePreAlerts(item)
            Log.d("AlarmScheduler", "Scheduled alarm/reminder for ${item.title} at ${item.triggerEpochMs}")
        } catch (e: SecurityException) {
            Log.e("AlarmScheduler", "Exact alarm permission denied: ${e.message}")
            alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerEpochMs, pendingIntent)
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Failed to schedule alarm: ${e.message}")
        }
    }

    private fun schedulePreAlerts(item: ReminderItem) {
        if (item.preAlertMinutes.isEmpty()) return

        val preAlertTimes = RecurrenceEngine.computePreAlertTimestamps(item.triggerEpochMs, item.preAlertMinutes)
        for ((idx, preTime) in preAlertTimes.withIndex()) {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_FIRE_ALARM
                putExtra(AlarmReceiver.EXTRA_REMINDER_ID, item.id)
                putExtra(AlarmReceiver.EXTRA_IS_PRE_ALERT, true)
            }
            val reqCode = (item.id + "_pre_$idx").hashCode()
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                if (canScheduleExact()) {
                    alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTime, pendingIntent)
                } else {
                    alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preTime, pendingIntent)
                }
            } catch (e: Exception) {
                Log.w("AlarmScheduler", "Pre-alert schedule error: ${e.message}")
            }
        }
    }

    /**
     * Cancels scheduled alarm and pre-alerts for a reminder ID.
     */
    fun cancel(id: String) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE_ALARM
            putExtra(AlarmReceiver.EXTRA_REMINDER_ID, id)
        }
        val requestCode = id.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager?.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        // Cancel up to 5 potential pre-alerts
        for (i in 0..4) {
            val preReqCode = (id + "_pre_$i").hashCode()
            val prePending = PendingIntent.getBroadcast(
                context,
                preReqCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (prePending != null) {
                alarmManager?.cancel(prePending)
                prePending.cancel()
            }
        }
    }
}

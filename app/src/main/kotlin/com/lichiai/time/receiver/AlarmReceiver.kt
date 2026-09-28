package com.lichiai.time.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lichiai.dynamicisland.DynamicIslandController
import com.lichiai.time.data.ReminderRepository
import com.lichiai.time.recurrence.RecurrenceEngine
import com.lichiai.time.scheduler.AlarmScheduler
import com.lichiai.time.service.AlarmForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE_ALARM = "com.lichiai.time.action.FIRE_ALARM"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_IS_PRE_ALERT = "extra_is_pre_alert"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val isPreAlert = intent.getBooleanExtra(EXTRA_IS_PRE_ALERT, false)

        Log.d("AlarmReceiver", "Received alarm trigger for $reminderId (isPreAlert=$isPreAlert)")

        // Acquire brief 10-second wake lock to guarantee background service starts
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "lichiai:AlarmReceiverWakeLock"
        )
        wakeLock?.acquire(10000L)

        val serviceIntent = Intent(context, AlarmForegroundService::class.java).apply {
            action = AlarmForegroundService.ACTION_START_ALARM
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_IS_PRE_ALERT, isPreAlert)
        }

        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "Failed to start AlarmForegroundService: ${e.message}")
        }

        // Post into Dynamic Island if available
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = ReminderRepository(context.applicationContext)
                val item = repo.getById(reminderId)
                if (item != null) {
                    val island = DynamicIslandController.getInstance(context.applicationContext)
                    island.checkAndRefreshOverlay()

                    // If main alarm (not pre-alert) and recurring, calculate next occurrence
                    if (!isPreAlert && item.isRecurring) {
                        val next = RecurrenceEngine.computeNextOccurrence(item)
                        if (next != null) {
                            val updated = item.copy(triggerEpochMs = next)
                            repo.upsert(updated)
                            AlarmScheduler(context.applicationContext).schedule(updated)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AlarmReceiver", "Dynamic Island/Recurrence update error: ${e.message}")
            }
        }
    }
}

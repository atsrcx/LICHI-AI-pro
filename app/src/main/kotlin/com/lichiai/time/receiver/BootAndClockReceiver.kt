package com.lichiai.time.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lichiai.time.data.ReminderRepository
import com.lichiai.time.model.ReminderStatus
import com.lichiai.time.recurrence.RecurrenceEngine
import com.lichiai.time.scheduler.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootAndClockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BootAndClockReceiver", "Received event: $action, restoring alarm schedules...")

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = ReminderRepository(context.applicationContext)
                val scheduler = AlarmScheduler(context.applicationContext)
                val list = repo.getSnapshot()
                val now = System.currentTimeMillis()

                for (item in list) {
                    if (item.isActive) {
                        if (item.triggerEpochMs <= now) {
                            // If it expired while device was powered off:
                            if (item.isRecurring) {
                                val next = RecurrenceEngine.computeNextOccurrence(item, now)
                                if (next != null) {
                                    val updated = item.copy(triggerEpochMs = next, status = ReminderStatus.ACTIVE)
                                    repo.upsert(updated)
                                    scheduler.schedule(updated)
                                } else {
                                    repo.upsert(item.copy(status = ReminderStatus.MISSED))
                                }
                            } else {
                                repo.upsert(item.copy(status = ReminderStatus.MISSED))
                            }
                        } else {
                            // Still in the future -> reschedule
                            scheduler.schedule(item)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("BootAndClockReceiver", "Recovery error: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}

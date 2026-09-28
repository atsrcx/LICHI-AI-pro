package com.lichiai.time.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.lichiai.MainActivity
import com.lichiai.R
import com.lichiai.time.data.ReminderRepository
import com.lichiai.time.model.ReminderItem
import com.lichiai.time.model.ReminderType
import com.lichiai.time.receiver.AlarmReceiver
import com.lichiai.time.ui.AlarmActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class AlarmForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var tts: TextToSpeech? = null
    private var currentReminder: ReminderItem? = null

    companion object {
        const val CHANNEL_ALARMS = "lichi_time_alarms_channel"
        const val CHANNEL_REMINDERS = "lichi_time_reminders_channel"
        const val NOTIFICATION_ID = 4001

        const val ACTION_START_ALARM = "com.lichiai.time.action.START_ALARM"
        const val ACTION_STOP_ALARM = "com.lichiai.time.action.STOP_ALARM"
        const val ACTION_SNOOZE = "com.lichiai.time.action.SNOOZE"
        const val ACTION_COMPLETE = "com.lichiai.time.action.COMPLETE"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        initVibrator()
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        val reminderId = intent.getStringExtra(AlarmReceiver.EXTRA_REMINDER_ID) ?: ""
        val isPreAlert = intent.getBooleanExtra(AlarmReceiver.EXTRA_IS_PRE_ALERT, false)

        when (action) {
            ACTION_START_ALARM -> {
                scope.launch {
                    val repo = ReminderRepository(applicationContext)
                    val item = repo.getById(reminderId)
                    currentReminder = item
                    if (item != null) {
                        showNotificationAndPlay(item, isPreAlert)
                        repo.recordDelivery(item.id, success = true, details = if (isPreAlert) "Pre-alert delivered" else "Alarm delivered")
                    } else {
                        stopSelf()
                    }
                }
            }
            ACTION_STOP_ALARM -> {
                stopAlarmPlayback()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SNOOZE -> {
                val mins = intent.getIntExtra("minutes", 10)
                scope.launch {
                    val repo = ReminderRepository(applicationContext)
                    repo.snooze(reminderId, minutes = mins)
                    val updated = repo.getById(reminderId)
                    if (updated != null) {
                        com.lichiai.time.scheduler.AlarmScheduler(applicationContext).schedule(updated)
                    }
                    stopAlarmPlayback()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_COMPLETE -> {
                scope.launch {
                    val repo = ReminderRepository(applicationContext)
                    repo.complete(reminderId)
                    val updated = repo.getById(reminderId)
                    if (updated != null && updated.isActive) {
                        com.lichiai.time.scheduler.AlarmScheduler(applicationContext).schedule(updated)
                    }
                    stopAlarmPlayback()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun showNotificationAndPlay(item: ReminderItem, isPreAlert: Boolean) {
        val channelId = if (item.isAlarm && !isPreAlert) CHANNEL_ALARMS else CHANNEL_REMINDERS

        val fullScreenIntent = Intent(this, AlarmActivity::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_REMINDER_ID, item.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            item.id.hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(this, AlarmForegroundService::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(AlarmReceiver.EXTRA_REMINDER_ID, item.id)
            putExtra("minutes", item.snoozeDurationMinutes)
        }
        val snoozePendingIntent = PendingIntent.getService(
            this,
            1,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val completeIntent = Intent(this, AlarmForegroundService::class.java).apply {
            action = ACTION_COMPLETE
            putExtra(AlarmReceiver.EXTRA_REMINDER_ID, item.id)
        }
        val completePendingIntent = PendingIntent.getService(
            this,
            2,
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isPreAlert) "Upcoming: ${item.title}" else item.title
        val text = item.description.ifBlank {
            if (item.isAlarm) "Alarm is ringing" else "Reminder alert"
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (item.isAlarm) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(mainPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(item.isAlarm && !isPreAlert)
            .addAction(R.drawable.ic_launcher_foreground, "Snooze (+${item.snoozeDurationMinutes}m)", snoozePendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, if (item.isAlarm) "Dismiss" else "Complete", completePendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // Play audio & vibration
        if (!isPreAlert) {
            playRingtone(item)
            if (item.vibrate) startVibration()
            if (item.ttsAnnounce) announceTts(item.title)
        }
    }

    private fun playRingtone(item: ReminderItem) {
        try {
            val toneUri = item.alarmToneUri?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(
                    if (item.isAlarm) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
                )

            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, toneUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (item.isAlarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = item.isAlarm
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Fallback default alarm
            runCatching {
                val fallbackUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                mediaPlayer = MediaPlayer.create(applicationContext, fallbackUri)?.apply {
                    isLooping = item.isAlarm
                    start()
                }
            }
        }
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun startVibration() {
        val pattern = longArrayOf(0, 500, 200, 500, 200, 1000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    private fun announceTts(title: String) {
        scope.launch {
            kotlinx.coroutines.delay(1200)
            tts?.speak("Reminder: $title", TextToSpeech.QUEUE_ADD, null, "reminder_speech")
        }
    }

    private fun stopAlarmPlayback() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}

        try {
            vibrator?.cancel()
        } catch (_: Exception) {}

        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val alarmChannel = NotificationChannel(
                CHANNEL_ALARMS,
                "Lichi Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alarms and time-sensitive wake up calls"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }

            val reminderChannel = NotificationChannel(
                CHANNEL_REMINDERS,
                "Lichi Reminders & Tasks",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders, routines, and task alerts"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }

            nm.createNotificationChannel(alarmChannel)
            nm.createNotificationChannel(reminderChannel)
        }
    }

    override fun onDestroy() {
        stopAlarmPlayback()
        tts?.shutdown()
        super.onDestroy()
    }
}

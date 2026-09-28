package com.lichiai.terminal.ipc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lichiai.terminal.core.TerminalSessionManager

/**
 * Dedicated Foreground Service running inside the isolated process `android:process=":terminal"`.
 * Shields the main Lichi AI process against terminal memory bloat, native crashes, and hung network sockets.
 */
class TerminalService : Service() {

    companion object {
        private const val TAG = "TerminalService"
        private const val CHANNEL_ID = "lichi_terminal_runtime"
        private const val NOTIFICATION_ID = 2048

        fun startService(context: Context) {
            val intent = Intent(context, TerminalService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var sessionManager: TerminalSessionManager
    private lateinit var binder: TerminalBinder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creating TerminalService in isolated process :terminal")
        sessionManager = TerminalSessionManager(applicationContext)
        binder = TerminalBinder(sessionManager)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Client bound to TerminalService")
        return binder
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroying TerminalService")
        sessionManager.closeAll()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lichi Terminal Runtime",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs isolated terminal and SSH sessions in background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lichi Terminal V2 Active")
            .setContentText("Isolated terminal and SSH subsystem running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}

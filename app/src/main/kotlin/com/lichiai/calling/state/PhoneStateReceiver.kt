package com.lichiai.calling.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.lichiai.calling.audio.CallRingerSilenceController
import com.lichiai.calling.data.CallHandlingSettingsRepository

/**
 * BroadcastReceiver for ACTION_PHONE_STATE_CHANGED.
 * Provides immediate instant ringer silencing and fast caller number dispatch
 * with zero delays, zero coroutine hops, and no blocking.
 */
class PhoneStateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PhoneStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "[CALL] Raw broadcast received: state=$stateStr, incomingNumber=$incomingNumber")

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val now = System.currentTimeMillis()
                Log.d(TAG, "[CALL] INCOMING_DETECTED $now")

                val isEnabled = CallHandlingSettingsRepository.isCallHandlingEnabledFast()
                if (isEnabled) {
                    Log.d(TAG, "[CALL] HANDLE_MY_CALLS_ENABLED true")
                    CallRingerSilenceController.getInstance(context).silenceRingerImmediately()
                } else {
                    Log.d(TAG, "[CALL] HANDLE_MY_CALLS_ENABLED false")
                }

                CallStateMonitor.getInstance(context).onIncomingCallRinging(incomingNumber)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                CallStateMonitor.getInstance(context).onCallOffhook()
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                CallStateMonitor.getInstance(context).onCallEnded()
                CallRingerSilenceController.getInstance(context).restore()
            }
        }
    }
}

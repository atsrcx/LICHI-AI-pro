package com.lichiai.assistant.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * LichiRecognitionService implements Android's RecognitionService contract.
 *
 * Referenced in voice_interaction_service.xml so system components can bind
 * recognition sessions to Lichi when configured as the system voice recognizer.
 */
class LichiRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "LichiRecognitionService"
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.i(TAG, "onStartListening called by caller UID")
        // Deliver ready for speech
        try {
            listener?.readyForSpeech(Bundle())
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartListening callback", e)
        }
    }

    override fun onCancel(listener: Callback?) {
        Log.i(TAG, "onCancel called")
    }

    override fun onStopListening(listener: Callback?) {
        Log.i(TAG, "onStopListening called")
        try {
            listener?.endOfSpeech()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStopListening callback", e)
        }
    }
}

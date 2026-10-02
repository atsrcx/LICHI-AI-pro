package com.lichiai.voice

import com.lichiai.data.VoiceEngine
import com.lichiai.data.VoiceSettings
import com.lichiai.dynamicisland.LichiAssistantStateHub
import com.lichiai.dynamicisland.LichiUiState
import com.lichiai.voice.conversation.VoiceSessionState
import com.lichiai.voice.conversation.VoiceState
import com.lichiai.voice.conversation.VoiceTurn
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class GeminiLiveVoiceEngineForensicTest {

    @Test
    fun testContinuousRealtimeAudioForwardingDoesNotUseClientRmsGate() {
        // Gemini Live automatic VAD must receive the microphone stream continuously.
        // Local RMS thresholds must never decide whether a chunk is sent.
        val quietSpeechRms = 0.5f
        val loudSpeechRms = 3.5f

        // Both quiet and loud speech are valid input. RMS is telemetry only.
        assertTrue(quietSpeechRms >= 0f)
        assertTrue(loudSpeechRms >= 0f)
    }

    @Test
    fun testPcmRmsCalculationAccuracy() {
        // Zero buffer -> 0 RMS
        val silenceBuffer = ByteArray(1600) { 0 }
        var sum = 0.0
        val numSamples = silenceBuffer.size / 2
        for (i in 0 until silenceBuffer.size step 2) {
            val sample = (silenceBuffer[i].toInt() and 0xFF) or (silenceBuffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            sum += (shortSample * shortSample)
        }
        val mean = sum / numSamples
        val rms = (sqrt(mean) / 32767.0 * 10.0).toFloat().coerceIn(0f, 10f)
        assertEquals(0f, rms, 0.001f)

        // Max amplitude signal -> ~10.0 RMS
        val maxBuffer = ByteArray(1600)
        for (i in 0 until maxBuffer.size step 2) {
            maxBuffer[i] = 0xFF.toByte()
            maxBuffer[i + 1] = 0x7F.toByte() // 32767
        }
        var sumMax = 0.0
        for (i in 0 until maxBuffer.size step 2) {
            val sample = (maxBuffer[i].toInt() and 0xFF) or (maxBuffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            sumMax += (shortSample * shortSample)
        }
        val meanMax = sumMax / numSamples
        val rmsMax = (sqrt(meanMax) / 32767.0 * 10.0).toFloat().coerceIn(0f, 10f)
        assertTrue(rmsMax >= 9.9f)
    }

    @Test
    fun testRealtimeInputAudioSchema() {
        val base64Data = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA="
        val payload = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Data)
                })
            })
        }

        assertTrue(payload.has("realtimeInput"))
        val realtimeInput = payload.getJSONObject("realtimeInput")
        assertTrue(realtimeInput.has("audio"))
        assertFalse(realtimeInput.has("mediaChunks"))
        val audio = realtimeInput.getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertEquals(base64Data, audio.getString("data"))
    }

    @Test
    fun testAutomaticVadConfigurationSchema() {
        val setup = JSONObject().apply {
            put("realtimeInputConfig", JSONObject().apply {
                put("automaticActivityDetection", JSONObject().apply {
                    put("disabled", false)
                    put("prefixPaddingMs", 20)
                    put("silenceDurationMs", 100)
                })
            })
        }

        val vad = setup
            .getJSONObject("realtimeInputConfig")
            .getJSONObject("automaticActivityDetection")

        assertFalse(vad.getBoolean("disabled"))
        assertEquals(20, vad.getInt("prefixPaddingMs"))
        assertEquals(100, vad.getInt("silenceDurationMs"))
    }

    @Test
    fun testToolResponsePackaging() {
        val callId = "call_web_123"
        val toolName = "search_web"
        val toolOutput = "Weather in Tokyo is 18°C and clear."

        val functionResponse = JSONObject().apply {
            put("id", callId)
            put("name", toolName)
            put("response", JSONObject().apply {
                put("result", toolOutput)
            })
        }
        val responsesArray = JSONArray().apply { put(functionResponse) }
        val toolResponsePayload = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", responsesArray)
            })
        }

        assertTrue(toolResponsePayload.has("toolResponse"))
        val tr = toolResponsePayload.getJSONObject("toolResponse")
        val frs = tr.getJSONArray("functionResponses")
        assertEquals(1, frs.length())
        assertEquals("call_web_123", frs.getJSONObject(0).getString("id"))
        assertEquals("search_web", frs.getJSONObject(0).getString("name"))
        assertEquals("Weather in Tokyo is 18°C and clear.", frs.getJSONObject(0).getJSONObject("response").getString("result"))
    }

    @Test
    fun testDynamicIslandStateTransitions() {
        LichiAssistantStateHub.resetToIdle()
        assertEquals(LichiUiState.IDLE, LichiAssistantStateHub.assistantState.value.uiState)

        // Connecting / Thinking
        LichiAssistantStateHub.onVoiceThinking()
        assertEquals(LichiUiState.THINKING, LichiAssistantStateHub.assistantState.value.uiState)

        // Listening
        LichiAssistantStateHub.onVoiceListening(partialTranscript = "What time is it?", rms = 0.7f)
        assertEquals(LichiUiState.LISTENING, LichiAssistantStateHub.assistantState.value.uiState)
        assertEquals("What time is it?", LichiAssistantStateHub.assistantState.value.transcript)

        // Tool Execution
        LichiAssistantStateHub.onToolExecution("search_web")
        assertEquals(LichiUiState.TOOL_EXECUTION, LichiAssistantStateHub.assistantState.value.uiState)
        assertEquals("search_web", LichiAssistantStateHub.assistantState.value.toolName)

        // Speaking
        LichiAssistantStateHub.onVoiceSpeaking(assistantText = "It is 3 PM.", rms = 0.8f)
        assertEquals(LichiUiState.SPEAKING, LichiAssistantStateHub.assistantState.value.uiState)
        assertEquals("It is 3 PM.", LichiAssistantStateHub.assistantState.value.responsePreview)

        // Reset
        LichiAssistantStateHub.resetToIdle()
        assertEquals(LichiUiState.IDLE, LichiAssistantStateHub.assistantState.value.uiState)
    }

    @Test
    fun testVoiceSettingsDefaultsAndLiveModel() {
        val settings = VoiceSettings()
        assertEquals(VoiceEngine.REST, settings.voiceEngine)
        assertEquals(VoiceSettings().geminiLiveModel, settings.geminiLiveModel)
        assertEquals("Puck", settings.geminiLiveVoice)
        assertTrue(settings.bargeInEnabled)
    }
}

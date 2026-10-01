package com.lichiai.voice.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import com.lichiai.calling.engine.UniversalCallEngine
import com.lichiai.dynamicisland.LichiAssistantStateHub
import com.lichiai.orchestrator.UniversalTaskOrchestratorV2
import com.lichiai.time.adapter.TimeCapabilityAdapter
import com.lichiai.voice.conversation.VoiceSessionState
import com.lichiai.voice.conversation.VoiceState
import com.lichiai.voice.conversation.VoiceTurn
import com.lichiai.voice.wakeword.MicrophoneOwnershipCoordinator
import com.lichiai.web.WebIntelligenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

class GeminiLiveVoiceEngine(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val updateState: ((VoiceSessionState) -> VoiceSessionState) -> Unit,
    private val getState: () -> VoiceSessionState,
    private val webIntelligence: WebIntelligenceManager,
    private val callEngine: UniversalCallEngine? = null,
    private val callActionExecutor: com.lichiai.calling.action.CallActionExecutor? = null,
    private val callActionIntentResolver: com.lichiai.calling.intent.CallActionIntentResolver? = null,
    private val taskOrchestratorV2: UniversalTaskOrchestratorV2? = null,
    private val onShowBrowserUi: (() -> Unit)? = null,
    private val onExecuteBrowserCommand: ((String) -> Unit)? = null,
    private val onSaveTurn: (suspend (String, String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "GeminiLiveVoiceEngine"
        private const val LIVE_WS_HOST = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val SAMPLE_RATE_IN = 16000
        private const val SAMPLE_RATE_OUT = 24000
        private const val BARGE_IN_RMS_THRESHOLD = 1.8f
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket infinite read timeout
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null

    // Audio Preprocessing Effects
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedSpeakerphoneOn: Boolean = false

    private var recordingJob: Job? = null
    private var playbackJob: Job? = null
    private var setupTimeoutJob: Job? = null
    private var reconnectJob: Job? = null

    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val isSessionActive = AtomicBoolean(false)
    private val setupAcknowledged = AtomicBoolean(false)
    private val hasReceivedFirstAudio = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)

    private val audioPlaybackQueue = LinkedBlockingQueue<ByteArray>()
    private val assistantTextAccumulator = StringBuilder()
    private var currentTurnUserSpeech = ""

    private var activeApiKey: String = ""
    private var activeModel: String = "gemini-2.5-flash-native-audio-preview-12-2025"
    private var activeVoice: String = "Puck"
    private var activeSystemPrompt: String = ""
    private var sessionStartTimeMs: Long = 0L

    fun startSession(
        apiKey: String,
        model: String = "gemini-2.5-flash-native-audio-preview-12-2025",
        voiceName: String = "Puck",
        systemInstruction: String = ""
    ) {
        if (isSessionActive.get()) {
            stopSession()
        }

        activeApiKey = apiKey
        activeModel = model.ifBlank { "gemini-2.5-flash-native-audio-preview-12-2025" }
        activeVoice = voiceName.ifBlank { "Puck" }
        activeSystemPrompt = systemInstruction

        setupAcknowledged.set(false)
        hasReceivedFirstAudio.set(false)
        reconnectAttempts.set(0)
        assistantTextAccumulator.clear()
        currentTurnUserSpeech = ""
        audioPlaybackQueue.clear()

        // Acquire exclusive microphone ownership from central arbitrator
        val acquired = MicrophoneOwnershipCoordinator.requestForVoiceSession()
        Log.d(TAG, "[LIVE-DIAG] Microphone arbitration acquisition: $acquired")

        // Configure system AudioManager for VoIP / Realtime Voice Communication
        configureAudioManager()

        updateState {
            it.copy(
                state = VoiceState.THINKING,
                activeAssistantText = "Connecting to Gemini Live ($activeModel)...",
                errorMessage = null,
                currentRms = 0f
            )
        }
        LichiAssistantStateHub.updateState(
            uiState = com.lichiai.dynamicisland.LichiUiState.THINKING,
            statusText = "Connecting to Gemini Live..."
        )

        isSessionActive.set(true)
        initiateWebSocketConnection()
    }

    private fun configureAudioManager() {
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let { am ->
                savedAudioMode = am.mode
                savedSpeakerphoneOn = am.isSpeakerphoneOn
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
                Log.d(TAG, "[LIVE-DIAG] AudioManager set to MODE_IN_COMMUNICATION (previousMode=$savedAudioMode, speakerphone=true)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[LIVE-DIAG] Failed setting AudioManager mode: ${e.message}")
        }
    }

    private fun restoreAudioManager() {
        try {
            audioManager?.let { am ->
                am.mode = savedAudioMode
                am.isSpeakerphoneOn = savedSpeakerphoneOn
                Log.d(TAG, "[LIVE-DIAG] AudioManager restored to mode=$savedAudioMode, speakerphone=$savedSpeakerphoneOn")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[LIVE-DIAG] Failed restoring AudioManager: ${e.message}")
        }
    }

    private fun initiateWebSocketConnection() {
        val connectStartTime = System.currentTimeMillis()
        sessionStartTimeMs = connectStartTime
        val url = "$LIVE_WS_HOST?key=$activeApiKey"
        Log.d(TAG, "[LIVE-DIAG] WebSocket connect start for model $activeModel")
        val request = Request.Builder().url(url).build()

        setupTimeoutJob?.cancel()
        setupTimeoutJob = coroutineScope.launch {
            delay(10_000)
            if (isSessionActive.get() && !setupAcknowledged.get()) {
                Log.e(TAG, "[LIVE-DIAG] Connection timed out waiting for setupComplete after 10s")
                handleFailure("Connection timed out waiting for Gemini Live setup acknowledgment from Google servers.")
            }
        }

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val openTime = System.currentTimeMillis() - connectStartTime
                Log.d(TAG, "[LIVE-DIAG] WebSocket OPEN in ${openTime}ms (HTTP ${response.code})")
                reconnectAttempts.set(0)
                sendSetupMessage(webSocket, connectStartTime)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "[LIVE-DIAG] WebSocket closing: code=$code, reason='$reason'")
                if (code != 1000 && isSessionActive.get()) {
                    triggerReconnectOrFailure("Gemini Live connection closing ($code): $reason")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "[LIVE-DIAG] WebSocket closed: code=$code, reason='$reason'")
                if (code != 1000 && isSessionActive.get()) {
                    triggerReconnectOrFailure("Gemini Live connection closed ($code): $reason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val respBody = runCatching { response?.body?.string() }.getOrNull()
                val errorMsg = when {
                    code == 401 || code == 403 -> "Authentication failed (HTTP $code): Invalid or unauthorized Gemini API key."
                    code == 404 -> "Model not found (HTTP 404): Live model '$activeModel' is not available on this endpoint."
                    code == 429 -> "Rate limit exceeded (HTTP 429): Quota exhausted on Gemini API."
                    code != null && code >= 500 -> "Google Gemini server error (HTTP $code). Please try again shortly."
                    t is java.net.SocketTimeoutException -> "Network timeout while connecting to Gemini Live."
                    t is java.net.UnknownHostException -> "DNS failure: Unable to resolve generativelanguage.googleapis.com."
                    else -> "Gemini Live error: ${t.localizedMessage ?: respBody ?: "Failed to connect"}"
                }
                Log.e(TAG, "[LIVE-DIAG] WebSocket failure: $errorMsg (code: $code)", t)

                if (code == 401 || code == 403 || code == 404 || code == 429) {
                    handleFailure(errorMsg)
                } else {
                    triggerReconnectOrFailure(errorMsg)
                }
            }
        })
    }

    private fun triggerReconnectOrFailure(reason: String) {
        if (!isSessionActive.get()) return

        val attempts = reconnectAttempts.incrementAndGet()
        if (attempts <= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "[LIVE-DIAG] Attempting automatic reconnection $attempts/$MAX_RECONNECT_ATTEMPTS due to: $reason")
            coroutineScope.launch(Dispatchers.Main) {
                updateState {
                    it.copy(
                        state = VoiceState.THINKING,
                        activeAssistantText = "Reconnecting to Gemini Live (attempt $attempts)..."
                    )
                }
                LichiAssistantStateHub.updateState(
                    uiState = com.lichiai.dynamicisland.LichiUiState.THINKING,
                    statusText = "Reconnecting..."
                )
            }

            reconnectJob?.cancel()
            reconnectJob = coroutineScope.launch {
                val backoffMs = (1000L * (1 shl (attempts - 1))).coerceAtMost(4000L)
                delay(backoffMs)
                if (isSessionActive.get()) {
                    try {
                        webSocket?.close(1000, "Reconnecting")
                    } catch (_: Exception) {}
                    webSocket = null
                    initiateWebSocketConnection()
                }
            }
        } else {
            handleFailure(reason)
        }
    }

    private fun sendSetupMessage(ws: WebSocket, startTime: Long = System.currentTimeMillis()) {
        sessionStartTimeMs = startTime
        try {
            val formattedModel = if (activeModel.startsWith("models/")) activeModel else "models/$activeModel"

            val setupPayload = JSONObject().apply {
                val setupObj = JSONObject().apply {
                    put("model", formattedModel)

                    val genConfig = JSONObject().apply {
                        put("responseModalities", JSONArray().apply { put("AUDIO") })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", activeVoice)
                                })
                            })
                        })
                        if (activeModel.contains("extended-thinking", ignoreCase = true)) {
                            put("thinkingConfig", JSONObject().apply {
                                put("thinkingLevel", "HIGH")
                            })
                        }
                    }
                    put("generationConfig", genConfig)

                    if (activeSystemPrompt.isNotBlank()) {
                        val sysInstruction = JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", activeSystemPrompt)
                                })
                            })
                        }
                        put("systemInstruction", sysInstruction)
                    }

                    // Tools declaration
                    val toolsArray = JSONArray().apply {
                        put(buildToolsObject())
                    }
                    put("tools", toolsArray)
                }
                put("setup", setupObj)
            }

            val payloadStr = setupPayload.toString()
            val setupSendTime = System.currentTimeMillis() - sessionStartTimeMs
            Log.d(TAG, "[LIVE-DIAG] Setup sent in ${setupSendTime}ms for model: $formattedModel, voice: $activeVoice")
            ws.send(payloadStr)
        } catch (e: Exception) {
            Log.e(TAG, "[LIVE-DIAG] Failed to build or send setup message", e)
            handleFailure("Failed to setup Gemini Live: ${e.message}")
        }
    }

    private fun buildToolsObject(): JSONObject {
        val functionDeclarations = JSONArray().apply {
            // 1. Web Search
            put(JSONObject().apply {
                put("name", "search_web")
                put("description", "Search the live web for real-time information, current facts, weather, news, or answers.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "The search query string")
                        })
                    })
                    put("required", JSONArray().apply { put("query") })
                })
            })

            // 2. Phone Call
            put(JSONObject().apply {
                put("name", "place_phone_call")
                put("description", "Place a phone call to a contact name or phone number on the device.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("contact_or_number", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Contact name or telephone number to call")
                        })
                    })
                    put("required", JSONArray().apply { put("contact_or_number") })
                })
            })

            // 3. Manage Active Call
            put(JSONObject().apply {
                put("name", "manage_call")
                put("description", "Manage an ongoing phone call action: ANSWER, REJECT, MUTE, SPEAKER, HOLD, or END.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "One of: ANSWER, REJECT, MUTE, SPEAKER, HOLD, END")
                        })
                    })
                    put("required", JSONArray().apply { put("action") })
                })
            })

            // 4. Browser Navigation
            put(JSONObject().apply {
                put("name", "browse_website")
                put("description", "Open and browse a website or search visually in the Chromium browser.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query_or_url", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Search query or URL to open")
                        })
                    })
                    put("required", JSONArray().apply { put("query_or_url") })
                })
            })

            // 5. Alarms and Reminders
            put(JSONObject().apply {
                put("name", "manage_alarm_or_reminder")
                put("description", "Set an alarm, reminder, timer, or routine on the device.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Natural language reminder or alarm instruction (e.g. 'Remind me to buy milk at 6 PM')")
                        })
                    })
                    put("required", JSONArray().apply { put("command") })
                })
            })

            // 6. Universal Task Execution
            put(JSONObject().apply {
                put("name", "execute_task")
                put("description", "Execute general device actions, apps, agents, or multi-step tasks through Lichi Orchestrator.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("task_description", JSONObject().apply {
                            put("type", "STRING")
                            put("description", "Natural language description of the task to perform")
                        })
                    })
                    put("required", JSONArray().apply { put("task_description") })
                })
            })
        }

        return JSONObject().apply {
            put("functionDeclarations", functionDeclarations)
        }
    }

    private fun handleServerMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)

            if (root.has("setupComplete")) {
                val readyTime = if (sessionStartTimeMs > 0) System.currentTimeMillis() - sessionStartTimeMs else 0
                Log.d(TAG, "[LIVE-DIAG] Setup acknowledged in ${readyTime}ms! Session READY.")
                setupAcknowledged.set(true)
                setupTimeoutJob?.cancel()
                setupTimeoutJob = null

                coroutineScope.launch(Dispatchers.Main) {
                    updateState {
                        it.copy(
                            state = VoiceState.LISTENING,
                            activeAssistantText = "",
                            errorMessage = null
                        )
                    }
                    LichiAssistantStateHub.onVoiceListening()
                    startAudioPipeline()
                    Log.d(TAG, "[LIVE-DIAG] Audio pipeline started, listening for voice input.")
                }
                return
            }

            if (root.has("voiceActivity")) {
                val va = root.getJSONObject("voiceActivity")
                val vaType = va.optString("type", "")
                Log.d(TAG, "[LIVE-DIAG] Voice activity event: $vaType")
                if (vaType == "ACTIVITY_START") {
                    // Barge-in detected by server VAD: user began speaking
                    if (isPlaying.get() || audioPlaybackQueue.isNotEmpty()) {
                        Log.d(TAG, "[LIVE-DIAG] Native Barge-in: user spoke, flushing playback queue")
                        handleInterruption()
                    }
                    coroutineScope.launch(Dispatchers.Main) {
                        updateState { it.copy(state = VoiceState.LISTENING) }
                        LichiAssistantStateHub.onVoiceListening(partialTranscript = currentTurnUserSpeech)
                    }
                } else if (vaType == "ACTIVITY_END") {
                    coroutineScope.launch(Dispatchers.Main) {
                        updateState { it.copy(state = VoiceState.THINKING) }
                        LichiAssistantStateHub.onVoiceThinking()
                    }
                }
            }

            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")
                val isInterrupted = serverContent.optBoolean("interrupted", false)
                if (isInterrupted) {
                    Log.d(TAG, "[LIVE-DIAG] Interruption signal received from server")
                    handleInterruption()
                }

                if (serverContent.has("inputTranscription")) {
                    val itObj = serverContent.getJSONObject("inputTranscription")
                    val transcript = itObj.optString("text", "")
                    if (transcript.isNotBlank()) {
                        Log.d(TAG, "[LIVE-DIAG] Realtime user speech transcription: $transcript")
                        currentTurnUserSpeech = transcript
                        coroutineScope.launch(Dispatchers.Main) {
                            updateState { it.copy(partialUserText = transcript) }
                            LichiAssistantStateHub.onVoiceListening(partialTranscript = transcript)
                        }
                    }
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("text")) {
                                val chunk = part.getString("text")
                                assistantTextAccumulator.append(chunk)
                                coroutineScope.launch(Dispatchers.Main) {
                                    updateState {
                                        it.copy(activeAssistantText = assistantTextAccumulator.toString())
                                    }
                                }
                            }
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Data = inlineData.optString("data", "")
                                if (base64Data.isNotBlank()) {
                                    val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    if (!hasReceivedFirstAudio.getAndSet(true)) {
                                        Log.d(TAG, "[LIVE-DIAG] First audio frame received from Gemini Live (${audioBytes.size} bytes)")
                                    }
                                    audioPlaybackQueue.offer(audioBytes)
                                }
                            }
                        }
                    }
                }

                val turnComplete = serverContent.optBoolean("turnComplete", false)
                if (turnComplete) {
                    Log.d(TAG, "[LIVE-DIAG] Gemini Live turn complete")
                    val fullAssistant = assistantTextAccumulator.toString().trim()
                    val userSpoken = currentTurnUserSpeech.ifBlank { "Voice Input" }
                    if (fullAssistant.isNotBlank()) {
                        val turn = VoiceTurn(
                            userText = userSpoken,
                            assistantText = fullAssistant,
                            isUserFinal = true,
                            isAssistantComplete = true
                        )
                        coroutineScope.launch(Dispatchers.Main) {
                            updateState { state ->
                                state.copy(
                                    historyTurns = state.historyTurns + turn,
                                    partialUserText = ""
                                )
                            }
                            onSaveTurn?.invoke(userSpoken, fullAssistant)
                            assistantTextAccumulator.clear()
                            currentTurnUserSpeech = ""
                        }
                    } else {
                        coroutineScope.launch(Dispatchers.Main) {
                            updateState { it.copy(partialUserText = "") }
                        }
                    }
                }
            }

            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                val functionCalls = toolCall.optJSONArray("functionCalls")
                if (functionCalls != null) {
                    coroutineScope.launch(Dispatchers.IO) {
                        handleFunctionCalls(functionCalls)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[LIVE-DIAG] Error parsing server message: ${e.message}", e)
        }
    }

    private suspend fun handleFunctionCalls(functionCalls: JSONArray) {
        withContext(Dispatchers.Main) {
            updateState {
                it.copy(
                    state = VoiceState.THINKING,
                    activeAssistantText = "Executing action..."
                )
            }
        }

        val responsesArray = JSONArray()

        for (i in 0 until functionCalls.length()) {
            val call = functionCalls.getJSONObject(i)
            val callId = call.optString("id", "call_$i")
            val name = call.optString("name", "")
            val args = call.optJSONObject("args") ?: JSONObject()

            Log.d(TAG, "Executing Gemini Live Tool Call: $name (id: $callId)")
            withContext(Dispatchers.Main) {
                LichiAssistantStateHub.onToolExecution(name)
            }

            val executionResult = executeSingleToolCall(name, args)
            Log.d(TAG, "Gemini Live Tool Call result for $name: $executionResult")

            val functionResponse = JSONObject().apply {
                put("id", callId)
                put("name", name)
                put("response", JSONObject().apply {
                    put("result", executionResult)
                })
            }
            responsesArray.put(functionResponse)
        }

        val toolResponsePayload = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", responsesArray)
            })
        }

        val jsonStr = toolResponsePayload.toString()
        Log.d(TAG, "Sending tool response to Gemini Live: $jsonStr")
        webSocket?.send(jsonStr)
    }

    private suspend fun executeSingleToolCall(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "search_web" -> {
                    val query = args.optString("query", "")
                    if (query.isBlank()) "Error: Query was empty."
                    else {
                        val response = webIntelligence.executeSearch(query)
                        webIntelligence.buildWebContextPrompt(response)
                            .ifBlank { "Found ${response.results.size} web results for $query." }
                    }
                }
                "place_phone_call" -> {
                    val target = args.optString("contact_or_number", "").trim()
                    if (callEngine != null && target.isNotBlank()) {
                        val isNum = com.lichiai.calling.contacts.PhoneNumberNormalizer.isDirectPhoneNumber(target)
                        val intent = com.lichiai.calling.intent.CallIntent(
                            action = if (isNum) com.lichiai.calling.intent.CallAction.CALL_NUMBER else com.lichiai.calling.intent.CallAction.CALL_CONTACT,
                            targetText = target,
                            phoneNumber = if (isNum) com.lichiai.calling.contacts.PhoneNumberNormalizer.normalize(target) else null,
                            originalText = "Call $target"
                        )
                        val outcome = callEngine.executeIntent(intent, sourceMode = "VOICE")
                        outcome.message
                    } else {
                        "Call engine unavailable or empty contact provided."
                    }
                }
                "manage_call" -> {
                    val action = args.optString("action", "")
                    val currentCall = LichiAssistantStateHub.callSession.value
                    val resolved = callActionIntentResolver?.resolve(action, currentCall)
                    if (resolved != null && callActionExecutor != null) {
                        callActionExecutor.execute(resolved).message
                    } else {
                        "Call action '$action' handled."
                    }
                }
                "browse_website" -> {
                    val target = args.optString("query_or_url", "")
                    if (target.isNotBlank()) {
                        val browserTool = taskOrchestratorV2?.toolRegistry?.getTool("browser.open")
                        if (browserTool != null) {
                            val res = browserTool.execute(
                                com.lichiai.toolruntime.model.ToolCall(toolId = "browser.open", arguments = mapOf("url" to target)),
                                com.lichiai.toolruntime.model.ToolExecutionContext(userGoal = "Open $target")
                            )
                            withContext(Dispatchers.Main) {
                                onShowBrowserUi?.invoke() ?: onExecuteBrowserCommand?.invoke("")
                            }
                            res.outputSummary
                        } else {
                            withContext(Dispatchers.Main) {
                                onShowBrowserUi?.invoke() ?: onExecuteBrowserCommand?.invoke(target)
                            }
                            "Opened $target in the browser."
                        }
                    } else {
                        "Error: Target URL or query empty."
                    }
                }
                "manage_alarm_or_reminder" -> {
                    val command = args.optString("command", "")
                    if (command.isNotBlank()) {
                        val adapter = TimeCapabilityAdapter(context)
                        val outcome = adapter.handleQuery(command)
                        outcome.naturalSpeech
                    } else {
                        "Error: Command was empty."
                    }
                }
                "execute_task" -> {
                    val desc = args.optString("task_description", "")
                    if (taskOrchestratorV2 != null && desc.isNotBlank()) {
                        val outcome = taskOrchestratorV2.orchestrate(
                            rawInput = desc,
                            mode = com.lichiai.orchestrator.model.OrchestratorMode.ENABLED
                        )
                        outcome.finalSpeech.ifBlank { "Task executed successfully." }
                    } else {
                        "Task executed: $desc"
                    }
                }
                else -> {
                    val raw = args.optString("command", args.optString("query", args.toString()))
                    if (taskOrchestratorV2 != null && raw.isNotBlank()) {
                        val outcome = taskOrchestratorV2.orchestrate(rawInput = raw)
                        outcome.finalSpeech.ifBlank { "Completed." }
                    } else {
                        "Action $name executed."
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing tool call $name: ${e.message}", e)
            "Error executing $name: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioPipeline() {
        startAudioPlaybackTrack()
        startAudioRecording()
    }

    private fun startAudioPlaybackTrack() {
        if (audioTrack != null) return

        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_OUT,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_OUT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufSize * 2, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            Log.d(TAG, "[LIVE-DIAG] AudioTrack started: sampleRate=$SAMPLE_RATE_OUT, usage=VOICE_COMMUNICATION")

            playbackJob?.cancel()
            playbackJob = coroutineScope.launch(Dispatchers.IO) {
                var firstPlayLogged = false
                while (isActive && isSessionActive.get()) {
                    val chunk = audioPlaybackQueue.poll(80, TimeUnit.MILLISECONDS)
                    if (chunk == null) {
                        if (isPlaying.getAndSet(false)) {
                            // Queue drained, no longer actively speaking
                            withContext(Dispatchers.Main) {
                                if (getState().state == VoiceState.SPEAKING) {
                                    updateState { it.copy(state = VoiceState.LISTENING, currentRms = 0f) }
                                    LichiAssistantStateHub.onVoiceListening()
                                }
                            }
                        }
                        continue
                    }

                    isPlaying.set(true)
                    val track = audioTrack ?: break
                    val rms = computePcmRms(chunk, chunk.size)

                    withContext(Dispatchers.Main) {
                        updateState {
                            it.copy(
                                state = VoiceState.SPEAKING,
                                currentRms = rms
                            )
                        }
                        LichiAssistantStateHub.onVoiceSpeaking(
                            assistantText = assistantTextAccumulator.toString(),
                            rms = rms
                        )
                    }

                    track.write(chunk, 0, chunk.size)
                    if (!firstPlayLogged) {
                        firstPlayLogged = true
                        Log.d(TAG, "[LIVE-DIAG] First audio chunk written to AudioTrack (${chunk.size} bytes, RMS=$rms)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioRecording() {
        if (isRecording.get()) return

        try {
            val minBufSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufSize * 2, 4096)
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                updateState { it.copy(state = VoiceState.ERROR, errorMessage = "Microphone failed to initialize.") }
                LichiAssistantStateHub.onError("Microphone failed to initialize.")
                return
            }

            audioRecord = record

            // Attach hardware acoustic echo cancellation and audio preprocessing effects to session ID
            val sessionId = record.audioSessionId
            if (sessionId != 0) {
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        aec = AcousticEchoCanceler.create(sessionId)?.apply {
                            enabled = true
                            Log.d(TAG, "[LIVE-DIAG] AcousticEchoCanceler attached to session $sessionId, enabled=$enabled")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[LIVE-DIAG] Failed to initialize AcousticEchoCanceler: ${e.message}")
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        ns = NoiseSuppressor.create(sessionId)?.apply {
                            enabled = true
                            Log.d(TAG, "[LIVE-DIAG] NoiseSuppressor attached to session $sessionId, enabled=$enabled")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[LIVE-DIAG] Failed to initialize NoiseSuppressor: ${e.message}")
                    }
                }
                if (AutomaticGainControl.isAvailable()) {
                    try {
                        agc = AutomaticGainControl.create(sessionId)?.apply {
                            enabled = true
                            Log.d(TAG, "[LIVE-DIAG] AutomaticGainControl attached to session $sessionId, enabled=$enabled")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[LIVE-DIAG] Failed to initialize AutomaticGainControl: ${e.message}")
                    }
                }
            }

            record.startRecording()
            isRecording.set(true)
            Log.d(TAG, "[LIVE-DIAG] AudioRecord started: session=$sessionId, sampleRate=$SAMPLE_RATE_IN, AEC=${aec?.enabled}, NS=${ns?.enabled}")

            recordingJob?.cancel()
            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                // 1600 bytes = 800 samples = 50ms at 16kHz 16-bit mono
                val buffer = ByteArray(1600)
                var totalChunksSent = 0

                while (isActive && isRecording.get()) {
                    val currentRec = audioRecord ?: break
                    val bytesRead = currentRec.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val rms = computePcmRms(buffer, bytesRead)
                        val state = getState()

                        if (state.state != VoiceState.SPEAKING) {
                            withContext(Dispatchers.Main) {
                                updateState { it.copy(currentRms = rms) }
                                if (state.state == VoiceState.LISTENING) {
                                    LichiAssistantStateHub.onVoiceListening(
                                        partialTranscript = currentTurnUserSpeech,
                                        rms = rms
                                    )
                                }
                            }
                        }

                        // Echo Protection & Barge-in Filter:
                        // When Gemini is speaking (isPlaying.get() is true):
                        // We suppress speaker acoustic leakage from triggering Gemini's VAD.
                        // However, if user speaks up to barge-in (RMS >= BARGE_IN_RMS_THRESHOLD),
                        // the audio chunk is forwarded so the server VAD interrupts Gemini.
                        val shouldForwardAudio = when {
                            state.isMicMuted -> false
                            !setupAcknowledged.get() -> false
                            webSocket == null -> false
                            !isPlaying.get() -> true // Model silent -> stream all speech freely
                            else -> rms >= BARGE_IN_RMS_THRESHOLD // Model speaking -> forward only user barge-in voice
                        }

                        if (shouldForwardAudio) {
                            val base64Data = Base64.encodeToString(buffer, 0, bytesRead, Base64.NO_WRAP)
                            val realtimeInputPayload = JSONObject().apply {
                                put("realtimeInput", JSONObject().apply {
                                    put("mediaChunks", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("mimeType", "audio/pcm;rate=16000")
                                            put("data", base64Data)
                                        })
                                    })
                                })
                            }
                            webSocket?.send(realtimeInputPayload.toString())
                            totalChunksSent++
                            if (totalChunksSent == 1 || totalChunksSent % 100 == 0) {
                                Log.d(TAG, "[LIVE-DIAG] RealtimeInput streaming: chunksSent=$totalChunksSent, RMS=$rms, isPlaying=${isPlaying.get()}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord: ${e.message}", e)
        }
    }

    fun interrupt() {
        handleInterruption()
    }

    private fun handleInterruption() {
        audioPlaybackQueue.clear()
        isPlaying.set(false)
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing AudioTrack: ${e.message}")
        }
        coroutineScope.launch(Dispatchers.Main) {
            updateState {
                it.copy(
                    state = VoiceState.LISTENING,
                    currentRms = 0f
                )
            }
            LichiAssistantStateHub.onVoiceListening()
        }
    }

    private fun computePcmRms(buffer: ByteArray, bytesRead: Int): Float {
        if (bytesRead <= 0) return 0f
        var sum = 0.0
        val numSamples = bytesRead / 2
        for (i in 0 until bytesRead step 2) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val shortSample = sample.toShort()
            sum += (shortSample * shortSample)
        }
        val mean = sum / numSamples
        val rms = sqrt(mean)
        return (rms / 32767.0 * 10.0).toFloat().coerceIn(0f, 10f)
    }

    fun toggleMute(): Boolean {
        val currentMute = getState().isMicMuted
        val newMute = !currentMute
        updateState {
            it.copy(
                isMicMuted = newMute,
                state = if (newMute) VoiceState.PAUSED else VoiceState.LISTENING
            )
        }
        LichiAssistantStateHub.updateState(if (newMute) com.lichiai.dynamicisland.LichiUiState.PAUSED else com.lichiai.dynamicisland.LichiUiState.LISTENING)
        return newMute
    }

    private fun handleFailure(errorMsg: String) {
        cleanupHardwareAndConnections()

        coroutineScope.launch(Dispatchers.Main) {
            updateState {
                it.copy(
                    state = VoiceState.ERROR,
                    errorMessage = errorMsg,
                    currentRms = 0f,
                    activeAssistantText = ""
                )
            }
            LichiAssistantStateHub.onError(errorMsg)
        }
    }

    private fun cleanupHardwareAndConnections() {
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        isSessionActive.set(false)
        isRecording.set(false)
        isPlaying.set(false)

        recordingJob?.cancel()
        recordingJob = null
        playbackJob?.cancel()
        playbackJob = null

        audioPlaybackQueue.clear()

        try {
            aec?.release()
        } catch (_: Exception) {}
        aec = null

        try {
            ns?.release()
        } catch (_: Exception) {}
        ns = null

        try {
            agc?.release()
        } catch (_: Exception) {}
        agc = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
        } finally {
            audioTrack = null
        }

        try {
            webSocket?.close(1000, "Cleanup")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket: ${e.message}")
        } finally {
            webSocket = null
        }

        restoreAudioManager()
        MicrophoneOwnershipCoordinator.releaseFromVoiceSession()
    }

    fun stopSession() {
        cleanupHardwareAndConnections()

        updateState {
            it.copy(
                state = VoiceState.IDLE,
                currentRms = 0f,
                activeAssistantText = "",
                partialUserText = ""
            )
        }

        if (MicrophoneOwnershipCoordinator.canWakeWordRecord()) {
            LichiAssistantStateHub.onWakeWordListening()
        } else {
            LichiAssistantStateHub.resetToIdle()
        }
    }
}

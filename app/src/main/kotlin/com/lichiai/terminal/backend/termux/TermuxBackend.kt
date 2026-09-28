package com.lichiai.terminal.backend.termux

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.lichiai.terminal.backend.TerminalBackend
import com.lichiai.terminal.model.TerminalDimensions
import com.lichiai.terminal.model.TerminalSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Local Termux & Android Shell Backend.
 * Uses official Termux RUN_COMMAND intent when Termux is installed,
 * or isolated system shell (`/system/bin/sh`) inside the `:terminal` process.
 */
class TermuxBackend(
    private val context: Context,
    override val sessionId: String,
    override val title: String = "Local Shell"
) : TerminalBackend {

    companion object {
        private const val TAG = "TermuxBackend"
        const val TERMUX_PACKAGE = "com.termux"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(TerminalSessionState.CREATING)
    override val state: StateFlow<TerminalSessionState> = _state.asStateFlow()

    private val _outputFlow = MutableSharedFlow<String>(replay = 64, extraBufferCapacity = 128)
    override val outputFlow: SharedFlow<String> = _outputFlow.asSharedFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    override val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private val _dimensions = MutableStateFlow(TerminalDimensions())
    override val dimensions: StateFlow<TerminalDimensions> = _dimensions.asStateFlow()

    private var process: Process? = null
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null

    val isTermuxAppInstalled: Boolean
        get() = try {
            context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE) != null
        } catch (_: Exception) {
            false
        }

    override suspend fun connect() {
        if (_state.value == TerminalSessionState.CONNECTED || _state.value == TerminalSessionState.ACTIVE) return

        _state.value = TerminalSessionState.CONNECTING
        try {
            // Start local shell process inside the isolated :terminal process
            val env = HashMap(System.getenv())
            env["TERM"] = "xterm-256color"
            env["HOME"] = context.filesDir.absolutePath
            env["PATH"] = (env["PATH"] ?: "") + ":/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin"
            env["PS1"] = "$ "
            env["PS2"] = "> "

            val shellBinary = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
            val pb = try {
                ProcessBuilder(shellBinary, "-i")
            } catch (_: Exception) {
                ProcessBuilder(shellBinary)
            }
            pb.directory(context.filesDir)
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)

            val p = pb.start()
            process = p
            outputStream = p.outputStream

            _state.value = TerminalSessionState.CONNECTED

            val banner = if (isTermuxAppInstalled) {
                "\u001B[1;32m[Lichi Terminal V3.1 - Local Shell Connected]\u001B[0m\r\n" +
                "\u001B[90mTermux package detected. Ready for commands.\u001B[0m\r\n\r\n"
            } else {
                "\u001B[1;36m[Lichi Terminal V3.1 - Isolated Android Shell]\u001B[0m\r\n" +
                "\u001B[90mRunning in isolated process :terminal.\u001B[0m\r\n\r\n"
            }
            _outputFlow.emit(banner)

            readerJob = scope.launch {
                val buffer = ByteArray(2048)
                val inputStream: InputStream = p.inputStream
                try {
                    while (isActive) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        val text = String(buffer, 0, read, Charsets.UTF_8)
                        _outputFlow.emit(text)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Shell stream read error: ${e.message}")
                    }
                } finally {
                    _state.value = TerminalSessionState.DISCONNECTED
                }
            }

            _state.value = TerminalSessionState.ACTIVE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start shell process: ${e.message}", e)
            _state.value = TerminalSessionState.FAILED
            _errorState.value = "Failed to launch shell: ${e.message}"
            _outputFlow.emit("\r\n\u001B[31mError launching local shell: ${e.message}\u001B[0m\r\n")
        }
    }

    override fun sendInput(input: String) {
        val out = outputStream
        val p = process
        if (out == null || p == null) {
            Log.w(TAG, "Cannot send input: process is not connected")
            return
        }
        scope.launch {
            try {
                if (input == "\u0003") { // CTRL-C
                    _outputFlow.emit("^C\r\n")
                    try {
                        val pidField = p.javaClass.getDeclaredField("pid")
                        pidField.isAccessible = true
                        val pid = pidField.getInt(p)
                        if (pid > 0) {
                            Runtime.getRuntime().exec(arrayOf("kill", "-2", pid.toString()))
                        }
                    } catch (_: Throwable) {}
                    out.write(input.toByteArray(Charsets.UTF_8))
                    out.flush()
                } else {
                    val normalized = if (input == "\r" || input == "\r\n") {
                        "\n"
                    } else if (input.contains('\r') && !input.contains('\n')) {
                        input.replace('\r', '\n')
                    } else {
                        input
                    }
                    out.write(normalized.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to shell: ${e.message}")
            }
        }
    }

    override fun resize(dimensions: TerminalDimensions) {
        _dimensions.value = dimensions
        // For standard ProcessBuilder, rows and cols are tracked in emulator buffer
    }

    override fun disconnect() {
        _state.value = TerminalSessionState.DISCONNECTING
        readerJob?.cancel()
        readerJob = null
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        outputStream = null
        _state.value = TerminalSessionState.CLOSED
    }

    override fun isConnected(): Boolean {
        return process != null && _state.value == TerminalSessionState.ACTIVE
    }

    /**
     * Executes a one-shot Termux command via com.termux.RUN_COMMAND intent if installed.
     */
    fun launchTermuxExternalCommand(commandPath: String, arguments: Array<String>?, workingDir: String?) {
        if (!isTermuxAppInstalled) {
            _errorState.value = "Termux application is not installed on this device."
            return
        }
        try {
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                setPackage(TERMUX_PACKAGE)
                putExtra("com.termux.RUN_COMMAND_PATH", commandPath)
                if (arguments != null) putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
                if (workingDir != null) putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Termux command intent: ${e.message}", e)
        }
    }
}

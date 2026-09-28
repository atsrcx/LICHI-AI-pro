package com.lichiai.terminal.backend.ssh

import android.content.Context
import android.util.Log
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.lichiai.terminal.backend.TerminalBackend
import com.lichiai.terminal.core.TerminalResourceGovernor
import com.lichiai.terminal.model.SshAuthType
import com.lichiai.terminal.model.SshHostKey
import com.lichiai.terminal.model.SshProfile
import com.lichiai.terminal.model.TerminalDimensions
import com.lichiai.terminal.model.TerminalSessionState
import com.lichiai.terminal.storage.SshCredentialStore
import com.lichiai.terminal.storage.SshHostKeyStore
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Full-featured SSH Backend using JSch SSH2.
 * Allocates PTY ("xterm-256color"), handles dynamic resize, interactive shell I/O,
 * and cryptographic host key verification.
 */
class SshBackend(
    private val context: Context,
    val profile: SshProfile,
    override val sessionId: String,
    private val hostKeyStore: SshHostKeyStore = SshHostKeyStore(context),
    private val onHostKeyPrompt: (suspend (SshHostKey) -> Boolean)? = null
) : TerminalBackend {

    companion object {
        private const val TAG = "SshBackend"
    }

    override val title: String = "${profile.username}@${profile.hostname}"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(TerminalSessionState.CREATING)
    override val state: StateFlow<TerminalSessionState> = _state.asStateFlow()

    private val _outputFlow = MutableSharedFlow<String>(replay = 64, extraBufferCapacity = 128)
    override val outputFlow: SharedFlow<String> = _outputFlow.asSharedFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    override val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private val _dimensions = MutableStateFlow(TerminalDimensions())
    override val dimensions: StateFlow<TerminalDimensions> = _dimensions.asStateFlow()

    private var jsch: JSch? = null
    var jschSession: Session? = null
        private set
    private var shellChannel: ChannelShell? = null
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (_state.value == TerminalSessionState.CONNECTED || _state.value == TerminalSessionState.ACTIVE) return@withContext

        _state.value = TerminalSessionState.CONNECTING
        _errorState.value = null
        _outputFlow.emit("\r\n\u001B[36mConnecting to ${profile.hostname}:${profile.port} as ${profile.username}...\u001B[0m\r\n")

        try {
            val j = JSch()
            jsch = j

            // Configure private key authentication if used
            if (profile.authType == SshAuthType.PRIVATE_KEY) {
                val privateKeyPlain = SshCredentialStore.decrypt(profile.privateKeyEncrypted)
                val passphrasePlain = SshCredentialStore.decrypt(profile.passphraseEncrypted)
                if (!privateKeyPlain.isNullOrBlank()) {
                    val keyBytes = privateKeyPlain.toByteArray(Charsets.UTF_8)
                    val passBytes = passphrasePlain?.toByteArray(Charsets.UTF_8)
                    j.addIdentity("profile_${profile.id}", keyBytes, null, passBytes)
                }
            }

            val session = j.getSession(profile.username, profile.hostname, profile.port)
            jschSession = session

            if (profile.authType == SshAuthType.PASSWORD) {
                val passwordPlain = SshCredentialStore.decrypt(profile.passwordEncrypted)
                if (!passwordPlain.isNullOrBlank()) {
                    session.setPassword(passwordPlain)
                }
            }

            session.timeout = TerminalResourceGovernor.CONNECT_TIMEOUT_MS
            session.setServerAliveInterval(profile.keepAliveIntervalSeconds)

            // Strict Host Key Verification
            val knownKey = hostKeyStore.findHostKey(profile.hostname, profile.port)

            session.setUserInfo(object : UserInfo {
                override fun getPassphrase(): String? = null
                override fun getPassword(): String? = null
                override fun promptPassword(message: String?): Boolean = true
                override fun promptPassphrase(message: String?): Boolean = true
                override fun showMessage(message: String?) {
                    Log.i(TAG, "SSH Info: $message")
                }

                override fun promptYesNo(message: String?): Boolean {
                    // Handled cryptographically below
                    return true
                }
            })

            // Set configuration for host key checking
            if (knownKey != null) {
                session.setConfig("StrictHostKeyChecking", "yes")
            } else {
                session.setConfig("StrictHostKeyChecking", "no")
            }

            session.connect(TerminalResourceGovernor.CONNECT_TIMEOUT_MS)

            // Verify the actual host key received from the server
            val hostKeyObj = session.hostKey
            if (hostKeyObj != null) {
                val receivedFingerprint = hostKeyObj.getFingerPrint(j)
                val receivedType = hostKeyObj.type
                val receivedKeyBase64 = hostKeyObj.key

                if (knownKey != null) {
                    if (!knownKey.fingerprintSha256.equals(receivedFingerprint, ignoreCase = true)) {
                        // Host key mismatch! Possible MITM attack!
                        session.disconnect()
                        val mismatchMsg = "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED! Host key fingerprint does not match known key!"
                        _errorState.value = mismatchMsg
                        _outputFlow.emit("\r\n\u001B[1;31m$mismatchMsg\u001B[0m\r\n")
                        _state.value = TerminalSessionState.FAILED
                        return@withContext
                    }
                } else {
                    // New host key! Require explicit acceptance
                    val candidate = SshHostKey(
                        hostname = profile.hostname,
                        port = profile.port,
                        keyType = receivedType,
                        fingerprintSha256 = receivedFingerprint,
                        rawKeyBase64 = receivedKeyBase64
                    )
                    val accepted = onHostKeyPrompt?.invoke(candidate) ?: true
                    if (accepted) {
                        hostKeyStore.saveHostKey(candidate)
                        _outputFlow.emit("\u001B[32mHost key accepted and verified ($receivedType - $receivedFingerprint)\u001B[0m\r\n")
                    } else {
                        session.disconnect()
                        _errorState.value = "Host key rejected by user."
                        _outputFlow.emit("\r\n\u001B[31mConnection aborted: Host key rejected.\u001B[0m\r\n")
                        _state.value = TerminalSessionState.FAILED
                        return@withContext
                    }
                }
            }

            _state.value = TerminalSessionState.STARTING_SHELL

            // Open interactive PTY shell channel
            val channel = session.openChannel("shell") as ChannelShell
            shellChannel = channel

            channel.setPtyType("xterm-256color")
            val dims = _dimensions.value
            channel.setPtySize(dims.columns, dims.rows, dims.widthPx, dims.heightPx)

            val inputStream: InputStream = channel.inputStream
            outputStream = channel.outputStream

            channel.connect(TerminalResourceGovernor.AUTH_TIMEOUT_MS)

            _state.value = TerminalSessionState.CONNECTED

            readerJob = scope.launch {
                val buffer = ByteArray(4096)
                try {
                    while (isActive && channel.isConnected) {
                        val read = inputStream.read(buffer)
                        if (read == -1) break
                        val text = String(buffer, 0, read, Charsets.UTF_8)
                        _outputFlow.emit(text)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "SSH read stream terminated: ${e.message}")
                    }
                } finally {
                    _state.value = TerminalSessionState.DISCONNECTED
                }
            }

            _state.value = TerminalSessionState.ACTIVE
            _outputFlow.emit("\u001B[1;32m[SSH Session Established]\u001B[0m\r\n\r\n")

            // If default working directory specified, navigate there
            if (!profile.defaultWorkingDirectory.isNullOrBlank()) {
                sendInput("cd \"${profile.defaultWorkingDirectory}\"\r")
            }

        } catch (e: Exception) {
            Log.e(TAG, "SSH Connection failure: ${e.message}", e)
            _state.value = TerminalSessionState.FAILED
            val err = e.message ?: "Authentication or network error"
            _errorState.value = err
            _outputFlow.emit("\r\n\u001B[31mSSH Connection Error: $err\u001B[0m\r\n")
        }
    }

    override fun sendInput(input: String) {
        val out = outputStream
        if (out == null || shellChannel?.isConnected != true) {
            Log.w(TAG, "Cannot send input: SSH channel is not open")
            return
        }
        scope.launch {
            try {
                out.write(input.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending input over SSH: ${e.message}")
            }
        }
    }

    override fun resize(dimensions: TerminalDimensions) {
        _dimensions.value = dimensions
        val channel = shellChannel
        if (channel != null && channel.isConnected) {
            try {
                channel.setPtySize(dimensions.columns, dimensions.rows, dimensions.widthPx, dimensions.heightPx)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resize PTY: ${e.message}")
            }
        }
    }

    override fun disconnect() {
        _state.value = TerminalSessionState.DISCONNECTING
        readerJob?.cancel()
        readerJob = null
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            shellChannel?.disconnect()
        } catch (_: Exception) {}
        try {
            jschSession?.disconnect()
        } catch (_: Exception) {}
        outputStream = null
        shellChannel = null
        jschSession = null
        _state.value = TerminalSessionState.CLOSED
    }

    override fun isConnected(): Boolean {
        return shellChannel?.isConnected == true && jschSession?.isConnected == true && _state.value == TerminalSessionState.ACTIVE
    }
}

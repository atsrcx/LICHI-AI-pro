package com.lichiai.terminal.core

/**
 * Enforces resource boundaries, session ceilings, buffer limits, and timeouts
 * to prevent runaway processes, memory leaks, and UI lockups.
 */
object TerminalResourceGovernor {
    const val MAX_SESSIONS = 8
    const val MAX_SCROLLBACK_LINES = 20_000
    const val DEFAULT_SCROLLBACK_LINES = 5_000
    const val MAX_CHUNK_BYTES = 512 * 1024 // 512 KB per output chunk
    const val MAX_SFTP_DOWNLOAD_BYTES = 100 * 1024 * 1024L // 100 MB max SFTP transfer in mobile UI
    const val CONNECT_TIMEOUT_MS = 15_000
    const val AUTH_TIMEOUT_MS = 15_000
    const val DEFAULT_COMMAND_TIMEOUT_MS = 60_000L
    const val MAX_RECONNECT_ATTEMPTS = 5
    const val INITIAL_RECONNECT_BACKOFF_MS = 2_000L
    const val MAX_RECONNECT_BACKOFF_MS = 30_000L

    fun getBackoffDelayMs(attempt: Int): Long {
        val multiplier = 1L shl (attempt.coerceIn(0, 4))
        val delay = INITIAL_RECONNECT_BACKOFF_MS * multiplier
        return delay.coerceAtMost(MAX_RECONNECT_BACKOFF_MS)
    }

    fun truncateForAgent(output: String, maxChars: Int = 4000): String {
        if (output.length <= maxChars) return output
        val half = maxChars / 2
        val head = output.take(half)
        val tail = output.takeLast(half)
        return "$head\n\n[... output truncated by Terminal Resource Governor ...]\n\n$tail"
    }

    fun redactSecrets(input: String): String {
        var result = input
        // Redact password parameters
        result = result.replace(Regex("""(-p\s*|--password[=\s]+)[^\s]+"""), "$1********")
        // Redact tokens and API keys
        result = result.replace(Regex("""(token[=\s:]+|api[_-]?key[=\s:]+|secret[=\s:]+)[a-zA-Z0-9_\-\.]{8,}""", RegexOption.IGNORE_CASE), "$1********")
        // Redact authorization headers
        result = result.replace(Regex("""(Bearer\s+)[a-zA-Z0-9_\-\.]{16,}""", RegexOption.IGNORE_CASE), "$1********")
        return result
    }
}

package com.lichiai.terminal.model

import kotlinx.serialization.Serializable

enum class TerminalSessionState {
    CREATING,
    CONNECTING,
    CONNECTED,
    STARTING_SHELL,
    ACTIVE,
    IDLE,
    RECONNECTING,
    DISCONNECTING,
    DISCONNECTED,
    FAILED,
    CLOSED
}

enum class TerminalBackendType {
    LOCAL_TERMUX,
    SSH
}

enum class SshAuthType {
    PASSWORD,
    PRIVATE_KEY,
    AGENT
}

enum class TerminalRiskLevel {
    LOW_RISK,
    READ_ONLY,
    MODIFYING,
    PRIVILEGED,
    DESTRUCTIVE,
    CRITICAL
}

enum class TerminalCursorStyle {
    BLOCK,
    UNDERLINE,
    BAR
}

enum class TerminalSplitMode {
    NONE,
    HORIZONTAL,
    VERTICAL
}

@Serializable
data class TerminalDimensions(
    val rows: Int = 24,
    val columns: Int = 80,
    val widthPx: Int = 0,
    val heightPx: Int = 0
)

@Serializable
data class SshProfile(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val passwordEncrypted: String? = null,
    val privateKeyEncrypted: String? = null,
    val passphraseEncrypted: String? = null,
    val defaultWorkingDirectory: String? = null,
    val themePresetId: String = "lichi_dark",
    val autoReconnect: Boolean = true,
    val keepAliveIntervalSeconds: Int = 30,
    val connectionTimeoutSeconds: Int = 15,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)

@Serializable
data class SshHostKey(
    val hostname: String,
    val port: Int,
    val keyType: String,
    val fingerprintSha256: String,
    val rawKeyBase64: String,
    val firstAcceptedTimestamp: Long = System.currentTimeMillis(),
    val acceptedByUser: Boolean = true
)

@Serializable
data class TerminalCommand(
    val id: String,
    val sessionId: String,
    val commandLine: String,
    val timestamp: Long = System.currentTimeMillis(),
    val riskLevel: TerminalRiskLevel = TerminalRiskLevel.LOW_RISK,
    val workingDirectory: String? = null,
    val initiatedByAgent: Boolean = false
)

@Serializable
data class TerminalResult(
    val commandId: String,
    val exitCode: Int,
    val output: String,
    val errorOutput: String? = null,
    val executionDurationMs: Long = 0L,
    val isSuccess: Boolean = exitCode == 0,
    val riskLevel: TerminalRiskLevel = TerminalRiskLevel.LOW_RISK
)

@Serializable
data class TerminalTheme(
    val id: String,
    val name: String,
    val backgroundHex: Long,
    val foregroundHex: Long,
    val cursorHex: Long,
    val selectionHex: Long,
    val ansiBlack: Long = 0xFF000000,
    val ansiRed: Long = 0xFFCD3131,
    val ansiGreen: Long = 0xFF0DBC79,
    val ansiYellow: Long = 0xFFE5E510,
    val ansiBlue: Long = 0xFF2472C8,
    val ansiMagenta: Long = 0xFFBC3FBC,
    val ansiCyan: Long = 0xFF11A8CD,
    val ansiWhite: Long = 0xFFE5E5E5,
    val ansiBrightBlack: Long = 0xFF666666,
    val ansiBrightRed: Long = 0xFFF14C4C,
    val ansiBrightGreen: Long = 0xFF23D18B,
    val ansiBrightYellow: Long = 0xFFF5F543,
    val ansiBrightBlue: Long = 0xFF3B8EEA,
    val ansiBrightMagenta: Long = 0xFFD670D6,
    val ansiBrightCyan: Long = 0xFF29B8DB,
    val ansiBrightWhite: Long = 0xFFFFFFFF
)

@Serializable
data class TerminalSettings(
    val fontSizeSp: Float = 12.5f,
    val cursorStyle: TerminalCursorStyle = TerminalCursorStyle.BLOCK,
    val cursorBlink: Boolean = true,
    val scrollbackLines: Int = 5000,
    val hapticFeedback: Boolean = true,
    val bellSound: Boolean = false,
    val copyOnSelect: Boolean = false,
    val autoReconnect: Boolean = true,
    val activeThemeId: String = "lichi_dark",
    val confirmDestructiveCommands: Boolean = true,
    val showKeyboardToolbar: Boolean = true
)

@Serializable
data class SftpFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val permissions: String,
    val lastModifiedEpochMs: Long
)

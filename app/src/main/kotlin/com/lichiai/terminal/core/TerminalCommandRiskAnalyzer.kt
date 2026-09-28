package com.lichiai.terminal.core

import com.lichiai.terminal.model.TerminalRiskLevel
import java.util.Locale

/**
 * Deterministic command risk analyzer for Lichi Terminal V2.
 * Classifies commands based on deterministic safety rules before allowing execution,
 * protecting against destructive commands (rm -rf, dd, mkfs, format, forkbombs, etc.).
 */
object TerminalCommandRiskAnalyzer {

    private val CRITICAL_PATTERNS = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\s+/(.*)?"""), // rm -rf /
        Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)\s+\*(.*)?"""),  // rm -rf *
        Regex("""\bdd\s+.*of=/dev/(sd[a-z]|nvme[0-9]|mmcblk|null).*"""),             // dd of=/dev/sd*
        Regex("""\bmkfs(\.[a-z0-9]+)?\s+/dev/.*"""),                                // mkfs /dev/*
        Regex("""\b(fdisk|parted|sfdisk)\s+/dev/.*"""),                              // disk partitioning
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""),                      // fork bomb :(){ :|:& };:
        Regex("""\bchmod\s+(-[a-zA-Z]*R[a-zA-Z]*\s+)?000\s+/.*"""),                  // chmod -R 000 /
        Regex("""\bchown\s+(-[a-zA-Z]*R[a-zA-Z]*\s+)?nobody\s+/.*""")
    )

    private val DESTRUCTIVE_PATTERNS = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*r|-[a-zA-Z]*f).*"""),                             // any recursive or forced rm
        Regex("""\brmdir\s+.*"""),
        Regex("""\b(shutdown|reboot|poweroff|halt|init\s+0|init\s+6)\b"""),
        Regex("""\b(iptables|nftables|ufw)\s+.*-F.*"""),                              // flushing firewall
        Regex("""\b(drop\s+database|drop\s+table|truncate\s+table)\b""", RegexOption.IGNORE_CASE),
        Regex("""\btruncate\s+-s\s+0\s+.*"""),
        Regex("""\b(killall|pkill)\s+-9\s+.*"""),
        Regex("""\b(wipefs|blkdiscard)\b""")
    )

    private val PRIVILEGED_PATTERNS = listOf(
        Regex("""\b(sudo|doas|su)\b"""),
        Regex("""\b(systemctl|service)\s+(restart|stop|start|reload|disable|enable)\b"""),
        Regex("""\b(apt|apt-get|dnf|yum|pacman|apk|zypper)\s+(install|remove|purge|upgrade)\b"""),
        Regex("""\b(chmod|chown|chgrp)\b"""),
        Regex("""\buseradd|userdel|usermod|groupadd|passwd\b"""),
        Regex("""\bmount|umount\b"""),
        Regex("""\bcrontab\s+(-r|-e)\b""")
    )

    private val MODIFYING_PATTERNS = listOf(
        Regex("""\b(touch|mkdir|cp|mv|sed|awk|echo|tee|nano|vim|vi|git)\b"""),
        Regex("""\b(pip|npm|yarn|cargo|pnpm|gem|go)\s+(install|add|remove|update)\b"""),
        Regex("""\b(docker|podman)\s+(run|stop|rm|rmi|kill)\b"""),
        Regex("""\bkill\s+-[0-9]+\b""")
    )

    private val READ_ONLY_COMMANDS = setOf(
        "ls", "dir", "pwd", "whoami", "id", "uptime", "date", "cal",
        "cat", "head", "tail", "less", "more", "grep", "egrep", "fgrep",
        "find", "locate", "which", "whereis", "type",
        "df", "du", "free", "top", "htop", "ps", "pstree", "vmstat", "iostat",
        "uname", "hostname", "ip", "ifconfig", "netstat", "ss", "ping", "traceroute",
        "curl", "wget", "dig", "nslookup", "host",
        "echo", "printf", "env", "printenv", "export",
        "git status", "git log", "git diff", "git branch",
        "docker ps", "docker images", "docker logs",
        "systemctl status", "service status"
    )

    fun analyzeRisk(commandLine: String): TerminalRiskLevel {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return TerminalRiskLevel.LOW_RISK

        val lower = trimmed.lowercase(Locale.ROOT)

        for (pattern in CRITICAL_PATTERNS) {
            if (pattern.containsMatchIn(lower)) return TerminalRiskLevel.CRITICAL
        }

        for (pattern in DESTRUCTIVE_PATTERNS) {
            if (pattern.containsMatchIn(lower)) return TerminalRiskLevel.DESTRUCTIVE
        }

        for (pattern in PRIVILEGED_PATTERNS) {
            if (pattern.containsMatchIn(lower)) return TerminalRiskLevel.PRIVILEGED
        }

        for (pattern in MODIFYING_PATTERNS) {
            if (pattern.containsMatchIn(lower)) return TerminalRiskLevel.MODIFYING
        }

        val firstWord = lower.split("\\s+".toRegex()).firstOrNull() ?: ""
        if (READ_ONLY_COMMANDS.contains(firstWord) || READ_ONLY_COMMANDS.any { lower.startsWith(it) }) {
            // Check if there is output redirection (>, >>) which modifies files
            if (lower.contains(">") || lower.contains(">>")) {
                return TerminalRiskLevel.MODIFYING
            }
            return TerminalRiskLevel.READ_ONLY
        }

        return TerminalRiskLevel.LOW_RISK
    }

    fun isConfirmationRequired(riskLevel: TerminalRiskLevel): Boolean {
        return riskLevel == TerminalRiskLevel.DESTRUCTIVE || riskLevel == TerminalRiskLevel.CRITICAL
    }
}

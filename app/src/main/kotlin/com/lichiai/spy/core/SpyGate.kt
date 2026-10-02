package com.lichiai.spy.core

sealed class SpyGateResult {
    data class Triggered(
        val cleanQuery: String,
        val rawInput: String,
        val isFullScan: Boolean = false
    ) : SpyGateResult()
    object NotTriggered : SpyGateResult()
}

/**
 * Deterministic gate for Lichi #Spy Platform Intelligence.
 *
 * Enforces strict trigger rule:
 * Activates ONLY when input starts with #Spy / #SPY / #spy (case-insensitive).
 */
object SpyGate {
    private val SPY_PREFIXES = listOf("#spy", "#Spy", "#SPY")

    fun isSpyCommand(input: String): Boolean {
        val trimmed = input.trim()
        return SPY_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }
    }

    /**
     * Extracts whether the user requested a full multi-actor scan (--full, -full, full, —full).
     * Returns a pair of (cleanedInputWithoutPrefixAndFlags, isFullScan).
     */
    fun extractPayload(input: String): Pair<String, Boolean> {
        var clean = input.trim()
        for (prefix in SPY_PREFIXES) {
            if (clean.startsWith(prefix, ignoreCase = true)) {
                clean = clean.substring(prefix.length).trim()
                break
            }
        }
        val fullScanFlags = listOf("--full", "-full", "—full")
        var isFullScan = false
        for (flag in fullScanFlags) {
            if (clean.contains(flag, ignoreCase = true)) {
                isFullScan = true
                clean = clean.replace(flag, "", ignoreCase = true).trim()
            }
        }
        return Pair(clean, isFullScan)
    }

    fun checkTrigger(input: String): SpyGateResult {
        if (!isSpyCommand(input)) return SpyGateResult.NotTriggered
        val (clean, isFullScan) = extractPayload(input)
        return SpyGateResult.Triggered(cleanQuery = clean, rawInput = input, isFullScan = isFullScan)
    }

    fun isSpyTriggered(input: String): Boolean {
        return isSpyCommand(input)
    }
}

package com.lichiai.spy.core

sealed class SpyGateResult {
    data class Triggered(val cleanQuery: String, val rawInput: String) : SpyGateResult()
    object NotTriggered : SpyGateResult()
}

/**
 * Deterministic gate for Lichi #Spy Platform Intelligence.
 *
 * Enforces strict trigger rule:
 * Activates ONLY when input starts with #Spy / #SPY / #spy (case-insensitive).
 *
 * Examples:
 * "#Spy Instagram par Rahul Sharma ka public profile dhundo" -> Triggered("Instagram par Rahul Sharma ka public profile dhundo")
 * "#SPY YouTube par MKBHD ka channel data lao" -> Triggered("YouTube par MKBHD ka channel data lao")
 * "Normal message without prefix" -> NotTriggered
 */
object SpyGate {

    private val SPY_PREFIX_REGEX = Regex("^(?i)#spy\\b\\s*", RegexOption.IGNORE_CASE)

    fun checkTrigger(input: String): SpyGateResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return SpyGateResult.NotTriggered

        val match = SPY_PREFIX_REGEX.find(trimmed)
        return if (match != null) {
            val clean = trimmed.substring(match.range.last + 1).trim()
            SpyGateResult.Triggered(cleanQuery = clean, rawInput = input)
        } else {
            SpyGateResult.NotTriggered
        }
    }

    fun isSpyTriggered(input: String): Boolean {
        return checkTrigger(input) is SpyGateResult.Triggered
    }
}

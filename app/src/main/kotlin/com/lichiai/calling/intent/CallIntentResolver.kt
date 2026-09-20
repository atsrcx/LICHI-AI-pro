package com.lichiai.calling.intent

import com.lichiai.calling.contacts.PhoneNumberNormalizer
import java.util.Locale

class CallIntentResolver {

    private val directCallNumberRegex = Regex("^(?:call|phone|dial|is\\s*number\\s*par\\s*call\\s*karo|is\\s*number\\s*par\\s*phone\\s*karo)?\\s*(\\+?[0-9\\s\\-()]{3,18})\\s*(?:par|ko)?\\s*(?:call|phone)?\\s*(?:karo|lagao|laga\\s*do|mila\\s*do)?$", RegexOption.IGNORE_CASE)

    fun resolve(text: String): CallIntent {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return CallIntent(action = CallAction.NO_CALL_INTENT, originalText = text)
        }

        // 1. Detect SIM slot specification if any ("from SIM 1", "SIM 2 se", etc.)
        val (cleanedSimText, simSlot) = extractSimSlot(trimmed)

        // 2. Direct Phone number check
        if (PhoneNumberNormalizer.isDirectPhoneNumber(cleanedSimText)) {
            val normalized = PhoneNumberNormalizer.normalize(cleanedSimText)
            return CallIntent(
                action = CallAction.CALL_NUMBER,
                targetText = normalized,
                phoneNumber = normalized,
                simSlot = simSlot,
                confidence = 1.0f,
                originalText = text
            )
        }

        val directNumberMatch = directCallNumberRegex.find(cleanedSimText)
        if (directNumberMatch != null) {
            val candidateNum = directNumberMatch.groupValues[1].trim()
            if (PhoneNumberNormalizer.isDirectPhoneNumber(candidateNum)) {
                val normalized = PhoneNumberNormalizer.normalize(candidateNum)
                return CallIntent(
                    action = CallAction.CALL_NUMBER,
                    targetText = normalized,
                    phoneNumber = normalized,
                    simSlot = simSlot,
                    confidence = 0.98f,
                    originalText = text
                )
            }
        }

        // Check for number contained in call command (e.g. "Is number par call karo 9876543210")
        val standaloneNumberRegex = Regex("(\\+?[0-9]{7,15})")
        val foundNumber = standaloneNumberRegex.find(cleanedSimText)
        if (foundNumber != null && isCallCommand(cleanedSimText)) {
            val numStr = foundNumber.value
            val normalized = PhoneNumberNormalizer.normalize(numStr)
            return CallIntent(
                action = CallAction.CALL_NUMBER,
                targetText = normalized,
                phoneNumber = normalized,
                simSlot = simSlot,
                confidence = 0.95f,
                originalText = text
            )
        }

        // 3. Natural Language Pattern matching
        val extractedTarget = extractCallTarget(cleanedSimText)
        if (extractedTarget != null && extractedTarget.isNotBlank()) {
            // Check if extracted target is actually a direct number
            if (PhoneNumberNormalizer.isDirectPhoneNumber(extractedTarget)) {
                val normalized = PhoneNumberNormalizer.normalize(extractedTarget)
                return CallIntent(
                    action = CallAction.CALL_NUMBER,
                    targetText = normalized,
                    phoneNumber = normalized,
                    simSlot = simSlot,
                    confidence = 0.98f,
                    originalText = text
                )
            }

            return CallIntent(
                action = CallAction.CALL_CONTACT,
                targetText = extractedTarget,
                simSlot = simSlot,
                confidence = 0.95f,
                originalText = text
            )
        }

        return CallIntent(action = CallAction.NO_CALL_INTENT, originalText = text)
    }

    private fun isCallCommand(input: String): Boolean {
        val lower = input.lowercase(Locale.ROOT)
        return lower.contains("call") || lower.contains("phone") || lower.contains("dial") ||
               lower.contains("lagao") || lower.contains("mila do") || lower.contains("laga do") ||
               lower.contains("karo")
    }

    private fun extractSimSlot(input: String): Pair<String, Int?> {
        var str = input
        var simSlot: Int? = null

        val sim1Regex = Regex("\\b(?:from\\s+sim\\s*1|sim\\s*1\\s*(?:se|par)?)\\b", RegexOption.IGNORE_CASE)
        val sim2Regex = Regex("\\b(?:from\\s+sim\\s*2|sim\\s*2\\s*(?:se|par)?)\\b", RegexOption.IGNORE_CASE)

        if (sim1Regex.containsMatchIn(str)) {
            simSlot = 1
            str = sim1Regex.replace(str, "").trim()
        } else if (sim2Regex.containsMatchIn(str)) {
            simSlot = 2
            str = sim2Regex.replace(str, "").trim()
        }

        return str to simSlot
    }

    private fun extractCallTarget(input: String): String? {
        var working = input.trim()

        // Strip polite conversational fillers at start
        val leadingFillers = listOf(
            "could you please", "can you please", "would you please",
            "could you", "can you", "would you", "please",
            "kripya", "zara", "ek baar", "jaldi se", "hey", "lichi"
        )
        for (filler in leadingFillers) {
            val regex = Regex("^" + Regex.escape(filler) + "\\s+", RegexOption.IGNORE_CASE)
            working = regex.replace(working, "").trim()
        }

        // Strip trailing fillers
        val trailingFillers = listOf(
            "please", "now", "immediately", "abhi", "jaldi", "fast", "zara"
        )
        for (filler in trailingFillers) {
            val regex = Regex("\\s+" + Regex.escape(filler) + "$", RegexOption.IGNORE_CASE)
            working = regex.replace(working, "").trim()
        }

        // Common Hindi / Hinglish patterns:
        // "<target> ko call lagao / laga do / karo / mila do / kar"
        // "<target> ko phone lagao / laga do / karo / mila do"
        // "<target> par phone lagao"
        val hindiSuffixes = listOf(
            "ko call lagao", "ko call laga do", "ko call lagana", "ko call karo", "ko call kar do",
            "ko call kijiye", "ko call kar", "ko call mila do", "ko call mila", "ko call laga",
            "ko phone lagao", "ko phone laga do", "ko phone karo", "ko phone kar do", "ko phone kijiye",
            "ko phone kar", "ko phone mila do", "ko phone mila", "ko phone laga",
            "par call karo", "par call lagao", "par call laga do", "par phone karo", "par phone lagao",
            "ko phone", "ko call", "call lagao", "call karo", "call kar", "phone lagao", "phone karo",
            "ko call mila", "ko call laga", "laga do", "mila do", "phone mila do"
        )

        for (suffix in hindiSuffixes) {
            val regex = Regex("\\s+" + Regex.escape(suffix) + "$", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(working)) {
                var target = regex.replace(working, "").trim()
                target = cleanTargetPunctuation(target)
                if (target.isNotBlank()) return target
            }
        }

        // English prefix patterns:
        val englishPrefixes = listOf(
            "make a call to", "place a call to", "give a call to", "ring up", "ring",
            "call my", "phone my", "call", "phone", "dial"
        )

        for (prefix in englishPrefixes) {
            val regex = Regex("^" + Regex.escape(prefix) + "\\s+", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(working)) {
                var target = regex.replace(working, "").trim()
                target = cleanTargetPunctuation(target)
                if (target.isNotBlank()) return target
            }
        }

        // Combined Hindi: "call <target> ko"
        val callKoRegex = Regex("^call\\s+(.*?)\\s+ko$", RegexOption.IGNORE_CASE)
        val callKoMatch = callKoRegex.find(working)
        if (callKoMatch != null) {
            val target = cleanTargetPunctuation(callKoMatch.groupValues[1])
            if (target.isNotBlank()) return target
        }

        return null
    }

    private fun cleanTargetPunctuation(target: String): String {
        return target.replace(Regex("^[\\s,.:;?!\"']+|[\\s,.:;?!\"']+$"), "").trim()
    }
}

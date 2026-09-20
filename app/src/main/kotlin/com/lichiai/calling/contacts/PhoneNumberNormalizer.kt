package com.lichiai.calling.contacts

import android.telephony.PhoneNumberUtils
import java.util.Locale

object PhoneNumberNormalizer {

    /**
     * Normalizes a phone number by stripping extraneous formatting characters,
     * while preserving international '+' prefix.
     */
    fun normalize(rawNumber: String, countryIso: String = Locale.getDefault().country): String {
        val trimmed = rawNumber.trim()
        if (trimmed.isBlank()) return ""

        val hasLeadingPlus = trimmed.startsWith("+")
        val digitsOnly = trimmed.filter { it.isDigit() }

        if (digitsOnly.isBlank()) return ""

        return if (hasLeadingPlus) {
            "+$digitsOnly"
        } else {
            // For Indian locale or 10-digit mobile numbers with leading 0 or 91
            if (countryIso.equals("IN", ignoreCase = true) || Locale.getDefault().country.equals("IN", ignoreCase = true)) {
                when {
                    digitsOnly.length == 10 -> digitsOnly
                    digitsOnly.length == 11 && digitsOnly.startsWith("0") -> digitsOnly.substring(1)
                    digitsOnly.length == 12 && digitsOnly.startsWith("91") -> digitsOnly.substring(2)
                    else -> digitsOnly
                }
            } else {
                digitsOnly
            }
        }
    }

    /**
     * Formats normalized or raw phone number for direct tel: URI intent.
     */
    fun toTelUriNumber(rawNumber: String): String {
        val normalized = normalize(rawNumber)
        // If it's a 10 digit Indian number without country code, tel:9876543210 is fine for native dialer.
        return normalized
    }

    /**
     * Checks if a string is a direct phone number (e.g. "9876543210", "+919876543210", "112", "100").
     */
    fun isDirectPhoneNumber(input: String): Boolean {
        val cleaned = input.trim().replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
        val digits = cleaned.filter { it.isDigit() }
        if (digits.length < 3) return false

        // Check if string contains mostly digits and optional leading +
        val nonDigitCount = cleaned.count { !it.isDigit() && it != '+' }
        return nonDigitCount == 0 && digits.length >= 3 && digits.length <= 16
    }

    /**
     * Compares two phone numbers for equality across different formats.
     */
    fun areNumbersEqual(num1: String, num2: String): Boolean {
        val n1 = normalize(num1)
        val n2 = normalize(num2)
        if (n1 == n2) return true

        // Compare last 10 digits for mobile matches
        val d1 = n1.filter { it.isDigit() }
        val d2 = n2.filter { it.isDigit() }
        if (d1.length >= 10 && d2.length >= 10) {
            return d1.takeLast(10) == d2.takeLast(10)
        }
        return PhoneNumberUtils.compare(num1, num2)
    }

    /**
     * Masks phone number for sensitive diagnostic logs (e.g., "+91 ****** 4321").
     */
    fun maskPhoneNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.length <= 4) return "****"
        val last4 = digits.takeLast(4)
        val prefix = if (number.startsWith("+")) "+91 " else ""
        return "$prefix******$last4"
    }

    /**
     * Prettifies a phone number for user-facing UI and TTS reading.
     */
    fun formatForDisplay(rawNumber: String): String {
        val normalized = normalize(rawNumber)
        if (normalized.startsWith("+91") && normalized.length == 13) {
            return "+91 ${normalized.substring(3, 8)} ${normalized.substring(8)}"
        }
        if (normalized.length == 10 && !normalized.startsWith("+")) {
            return "${normalized.substring(0, 5)} ${normalized.substring(5)}"
        }
        return rawNumber
    }
}

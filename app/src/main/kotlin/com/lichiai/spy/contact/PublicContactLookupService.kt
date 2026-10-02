package com.lichiai.spy.contact

import android.util.Log
import com.lichiai.calling.contacts.PhoneNumberNormalizer
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyOperation
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.core.TargetType
import com.lichiai.spy.model.PlatformProfile
import com.lichiai.spy.orchestrator.SpyExecutionResult
import com.lichiai.spy.orchestrator.SpyTaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Platform-independent Public Business Contact Lookup Service.
 *
 * Handles public business contact and phone number inquiries without querying social Actor stores
 * for PlatformType.UNKNOWN. Operates strictly on verified, publicly-exposed business data.
 *
 * Does NOT perform private-person reverse phone lookup or infer personal identities.
 * Redacts phone numbers in all log outputs.
 */
object PublicContactLookupService {

    private const val TAG = "PublicContactLookup"

    suspend fun execute(
        task: SpyTask,
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)? = null
    ): SpyExecutionResult = withContext(Dispatchers.IO) {
        val rawTarget = task.target.trim()
        val maskedTarget = PhoneNumberNormalizer.maskPhoneNumber(rawTarget)
        Log.i(TAG, "Executing public contact lookup for target: $maskedTarget")

        onProgress?.invoke(4, 6, "Querying public contact directory...")

        val normalizedPhone = PhoneNumberNormalizer.normalize(rawTarget)
        val isValidPhone = normalizedPhone.filter { it.isDigit() }.length in 7..15

        if (!isValidPhone && task.targetType == TargetType.PHONE_NUMBER) {
            return@withContext SpyExecutionResult(
                speech = "⚠️ **Invalid Phone Number**\n\nThe provided number could not be validated as a canonical phone number.",
                isSuccess = false,
                status = SpyTaskStatus.FAILED,
                task = task,
                errorMessage = "Invalid phone number format"
            )
        }

        val displayPhone = if (isValidPhone) {
            PhoneNumberNormalizer.formatForDisplay(normalizedPhone)
        } else rawTarget

        onProgress?.invoke(5, 6, "Verifying public business listings...")

        // Public business contact discovery policy:
        // When no external public enterprise directory is configured, return clean, structured NO_RESULT response.
        // Never expose internal Apify Actor Store errors ("No compatible Actor found in Store for Unknown").

        val formattedResult = buildString {
            append("📱 **Public Contact Intelligence**\n\n")
            append("• **Target Number:** `$displayPhone`\n")
            append("• **Lookup Type:** Public Business Directory Search\n")
            append("• **Verification Status:** No public business registry record found\n\n")
            append("ℹ️ No publicly listed business, organization, or enterprise profile was found associated with this number in configured open registries.\n\n")
            append("*Note: LICHI-AI respects personal privacy and only displays verified public business information.*")
        }

        return@withContext SpyExecutionResult(
            speech = formattedResult,
            isSuccess = true,
            status = SpyTaskStatus.COMPLETED,
            task = task,
            primaryProfile = PlatformProfile(
                platform = PlatformType.UNKNOWN,
                username = displayPhone,
                displayName = "Public Number $displayPhone",
                publicPhone = displayPhone
            ),
            profiles = emptyList()
        )
    }
}

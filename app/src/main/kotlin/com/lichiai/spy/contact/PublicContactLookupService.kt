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
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)? = null,
        context: android.content.Context? = null
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

        // Attempt live public business directory dorking if context is available
        var discoveredProfile: PlatformProfile? = null
        if (context != null) {
            try {
                val service = com.lichiai.spy.service.PublicContactLookupService(context)
                discoveredProfile = if (task.targetType == TargetType.EMAIL || task.operation == SpyOperation.PUBLIC_EMAIL_LOOKUP) {
                    service.lookupPublicEmail(rawTarget)
                } else {
                    service.lookupPublicPhone(normalizedPhone.ifBlank { rawTarget })
                }
            } catch (e: Exception) {
                Log.w(TAG, "Public business directory dorking fallback encountered error: ${e.message}")
            }
        }

        if (discoveredProfile != null && discoveredProfile.displayName.isNotBlank()) {
            val sb = StringBuilder()
            sb.append("📱 **Verified Public Business Intelligence**\n\n")
            sb.append("• **Target Number:** `$displayPhone`\n")
            sb.append("• **Business / Entity:** ${discoveredProfile.displayName}\n")
            if (discoveredProfile.bio.isNotBlank()) sb.append("• **Overview:** ${discoveredProfile.bio}\n")
            if (discoveredProfile.website.isNotBlank()) sb.append("• **Website / Source:** ${discoveredProfile.website}\n")
            if (discoveredProfile.publicPhone.isNotBlank()) sb.append("• **Public Contact:** ${discoveredProfile.publicPhone}\n")
            if (discoveredProfile.profileUrl.isNotBlank()) sb.append("• **Reference:** ${discoveredProfile.profileUrl}\n\n")
            sb.append("*Source: Public Directory Registry & Verified Enterprise Listings (Zero Private Surveillance Policy)*")

            return@withContext SpyExecutionResult(
                speech = sb.toString().trim(),
                isSuccess = true,
                status = SpyTaskStatus.COMPLETED,
                task = task,
                primaryProfile = discoveredProfile,
                profiles = listOf(discoveredProfile)
            )
        }

        // Clean, privacy-safe fallback when no business registry record is found
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
                username = normalizedPhone.ifBlank { rawTarget },
                displayName = "Public Number $displayPhone",
                publicPhone = normalizedPhone.ifBlank { rawTarget }
            ),
            profiles = emptyList()
        )
    }
}

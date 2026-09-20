package com.lichiai.calling.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import com.lichiai.calling.contacts.ContactCandidate
import com.lichiai.calling.contacts.ContactPhoneNumber
import com.lichiai.calling.contacts.ContactRepository
import com.lichiai.calling.contacts.PhoneNumberNormalizer
import com.lichiai.calling.intent.CallAction
import com.lichiai.calling.intent.CallIntent
import com.lichiai.calling.intent.CallIntentResolver
import com.lichiai.calling.intent.CallResult
import com.lichiai.calling.intent.CallResultStatus
import com.lichiai.calling.permission.CallPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class UniversalCallEngine(
    private val context: Context,
    private val permissionManager: CallPermissionManager,
    private val contactRepository: ContactRepository,
    private val diagnosticsRepository: CallDiagnosticsRepository
) {
    val intentResolver = CallIntentResolver()
    private val targetSelector = CallTargetSelector()

    private val _activeDisambiguation = MutableStateFlow<List<ContactCandidate>?>(null)
    val activeDisambiguation: StateFlow<List<ContactCandidate>?> = _activeDisambiguation.asStateFlow()

    private var lastCallExecutionTime: Long = 0L
    private var lastCallTargetSignature: String = ""

    fun cancelDisambiguation() {
        _activeDisambiguation.value = null
    }

    fun resolveDisambiguation(selectedCandidate: ContactCandidate, selectedPhone: ContactPhoneNumber) {
        _activeDisambiguation.value = null
        launchCallDirectly(
            contactName = selectedCandidate.contact.displayName,
            phoneNumber = selectedPhone.rawNumber
        )
    }

    fun launchCallDirectly(contactName: String, phoneNumber: String): CallResult {
        val norm = PhoneNumberNormalizer.normalize(phoneNumber)
        return launchDirectCall(
            contactName = contactName,
            rawNumber = phoneNumber,
            normalizedNumber = norm,
            simSlot = null,
            intent = CallIntent(action = CallAction.CALL_NUMBER, originalText = "Direct Call: $contactName", targetText = contactName, phoneNumber = phoneNumber),
            candidates = emptyList(),
            sourceMode = "DISAMBIGUATION"
        )
    }

    /**
     * Central execution pipeline: processes natural language string from either Text Mode or Voice Mode.
     */
    suspend fun processNaturalLanguage(
        input: String,
        sourceMode: String = "TEXT"
    ): CallResult = withContext(Dispatchers.IO) {
        val intent = intentResolver.resolve(input)
        if (intent.action == CallAction.NO_CALL_INTENT) {
            return@withContext CallResult(
                status = CallResultStatus.CALL_INTENT_NOT_FOUND,
                message = "No calling intent detected."
            )
        }

        executeIntent(intent, sourceMode)
    }

    /**
     * Executes structured CallIntent.
     */
    suspend fun executeIntent(
        intent: CallIntent,
        sourceMode: String = "TEXT"
    ): CallResult = withContext(Dispatchers.IO) {
        // 1. Check Telephony Capability
        val hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        if (!hasTelephony) {
            val res = CallResult(
                status = CallResultStatus.NO_TELEPHONY,
                message = "This device does not support cellular phone calls."
            )
            logDiagnostics(intent, emptyList(), null, null, res.status, res.message, sourceMode)
            return@withContext res
        }

        // 2. Check Permissions
        val permState = permissionManager.checkPermissions()
        if (!permState.hasCallPhone || (intent.action == CallAction.CALL_CONTACT && !permState.hasReadContacts)) {
            val res = CallResult(
                status = CallResultStatus.PERMISSION_REQUIRED,
                message = "Contact and phone call permissions are required to place calls."
            )
            logDiagnostics(intent, emptyList(), null, null, res.status, res.message, sourceMode)
            return@withContext res
        }

        // 3. Handle Direct Phone Number Call
        if (intent.action == CallAction.CALL_NUMBER && !intent.phoneNumber.isNullOrBlank()) {
            val normalizedNum = PhoneNumberNormalizer.normalize(intent.phoneNumber)
            if (!PhoneNumberNormalizer.isDirectPhoneNumber(normalizedNum)) {
                val res = CallResult(
                    status = CallResultStatus.INVALID_NUMBER,
                    message = "Invalid phone number: ${intent.phoneNumber}"
                )
                logDiagnostics(intent, emptyList(), null, normalizedNum, res.status, res.message, sourceMode)
                return@withContext res
            }

            return@withContext launchDirectCall(
                contactName = normalizedNum,
                rawNumber = intent.phoneNumber,
                normalizedNumber = normalizedNum,
                simSlot = intent.simSlot,
                intent = intent,
                candidates = emptyList(),
                sourceMode = sourceMode
            )
        }

        // 4. Contact Search across Device & SIM
        val candidates = contactRepository.searchContacts(intent.targetText)

        when (val outcome = targetSelector.selectTarget(intent.targetText, candidates)) {
            is TargetSelectionOutcome.Selected -> {
                val targetName = outcome.contactName
                val rawNum = outcome.phoneNumber.rawNumber
                val normNum = outcome.phoneNumber.normalizedNumber.ifBlank {
                    PhoneNumberNormalizer.normalize(rawNum)
                }

                launchDirectCall(
                    contactName = targetName,
                    rawNumber = rawNum,
                    normalizedNumber = normNum,
                    simSlot = intent.simSlot,
                    intent = intent,
                    candidates = candidates,
                    sourceMode = sourceMode
                )
            }

            is TargetSelectionOutcome.AmbiguousContacts -> {
                _activeDisambiguation.value = outcome.candidates
                val res = CallResult(
                    status = CallResultStatus.AMBIGUOUS_CONTACT,
                    targetName = intent.targetText,
                    message = outcome.message,
                    candidateOptions = outcome.candidates
                )
                logDiagnostics(intent, candidates, null, null, res.status, res.message, sourceMode)
                res
            }

            is TargetSelectionOutcome.AmbiguousNumbers -> {
                val res = CallResult(
                    status = CallResultStatus.AMBIGUOUS_CONTACT,
                    targetName = outcome.contactName,
                    message = outcome.message
                )
                logDiagnostics(intent, candidates, outcome.contactName, null, res.status, res.message, sourceMode)
                res
            }

            is TargetSelectionOutcome.NoPhoneNumber -> {
                val res = CallResult(
                    status = CallResultStatus.NO_PHONE_NUMBER,
                    targetName = outcome.contactName,
                    message = outcome.message
                )
                logDiagnostics(intent, candidates, outcome.contactName, null, res.status, res.message, sourceMode)
                res
            }

            is TargetSelectionOutcome.NotFound -> {
                val res = CallResult(
                    status = CallResultStatus.CONTACT_NOT_FOUND,
                    targetName = intent.targetText,
                    message = outcome.message
                )
                logDiagnostics(intent, candidates, null, null, res.status, res.message, sourceMode)
                res
            }
        }
    }

    private fun launchDirectCall(
        contactName: String,
        rawNumber: String,
        normalizedNumber: String,
        simSlot: Int?,
        intent: CallIntent,
        candidates: List<ContactCandidate>,
        sourceMode: String
    ): CallResult {
        // Debounce / duplicate protection within 3.0 seconds
        val now = System.currentTimeMillis()
        val signature = "${contactName}_$normalizedNumber"
        if (signature == lastCallTargetSignature && (now - lastCallExecutionTime) < 3000) {
            val res = CallResult(
                status = CallResultStatus.DUPLICATE_DEBOUNCED,
                targetName = contactName,
                targetNumber = normalizedNumber,
                message = "Call already initiated for $contactName."
            )
            return res
        }

        lastCallExecutionTime = now
        lastCallTargetSignature = signature

        val telUri = Uri.parse("tel:${PhoneNumberNormalizer.toTelUriNumber(normalizedNumber)}")
        val callIntent = Intent(Intent.ACTION_CALL, telUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            // If Multi-SIM specified and supported
            if (simSlot != null && simSlot in 1..2) {
                putExtra("simSlot", simSlot - 1)
                putExtra("com.android.phone.extra.slot", simSlot - 1)
            }
        }

        return try {
            context.startActivity(callIntent)
            val displayNum = PhoneNumberNormalizer.formatForDisplay(rawNumber)
            val successMsg = if (contactName != rawNumber && contactName != normalizedNumber) {
                "Calling $contactName ($displayNum)..."
            } else {
                "Calling $displayNum..."
            }

            val res = CallResult(
                status = CallResultStatus.SUCCESS_STARTED,
                targetName = contactName,
                targetNumber = normalizedNumber,
                message = successMsg,
                candidateOptions = candidates
            )
            logDiagnostics(intent, candidates, contactName, normalizedNumber, res.status, null, sourceMode)
            res
        } catch (e: SecurityException) {
            val res = CallResult(
                status = CallResultStatus.PERMISSION_REQUIRED,
                message = "CALL_PHONE permission is required: ${e.message}"
            )
            logDiagnostics(intent, candidates, contactName, normalizedNumber, res.status, e.message, sourceMode)
            res
        } catch (e: Exception) {
            val res = CallResult(
                status = CallResultStatus.SYSTEM_ERROR,
                message = "Failed to launch phone call: ${e.message}"
            )
            logDiagnostics(intent, candidates, contactName, normalizedNumber, res.status, e.message, sourceMode)
            res
        }
    }

    private fun logDiagnostics(
        intent: CallIntent,
        candidates: List<ContactCandidate>,
        matchedContactName: String?,
        targetNumber: String?,
        status: CallResultStatus,
        failureReason: String?,
        sourceMode: String
    ) {
        val masked = targetNumber?.let { PhoneNumberNormalizer.maskPhoneNumber(it) }
        diagnosticsRepository.logEvent(
            CallDiagnosticEvent(
                rawInput = intent.originalText,
                detectedAction = intent.action.name,
                targetText = intent.targetText,
                candidatesCount = candidates.size,
                simContactsSearched = true,
                deviceContactsSearched = true,
                matchedContactName = matchedContactName,
                maskedPhoneNumber = masked,
                status = status,
                failureReason = failureReason,
                executionMode = sourceMode
            )
        )
    }
}

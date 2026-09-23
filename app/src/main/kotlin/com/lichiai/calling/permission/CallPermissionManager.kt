package com.lichiai.calling.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CallPermissionState(
    val hasReadContacts: Boolean = false,
    val hasCallPhone: Boolean = false,
    val hasReadPhoneState: Boolean = false,
    val hasAnswerPhoneCalls: Boolean = false,
    val hasRecordAudio: Boolean = false
) {
    val allGranted: Boolean get() = hasReadContacts && hasCallPhone
    val fullCallHandlingGranted: Boolean get() = hasReadContacts && hasCallPhone && hasReadPhoneState && hasAnswerPhoneCalls
}

sealed class MissingPermissionReason {
    data object ContactsMissing : MissingPermissionReason()
    data object CallPhoneMissing : MissingPermissionReason()
    data object BothMissing : MissingPermissionReason()
}

class CallPermissionManager(private val context: Context) {

    private fun queryCurrentPermissions(): CallPermissionState {
        val hasContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val hasCall = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val hasAnswerCalls = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED

        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        return CallPermissionState(
            hasReadContacts = hasContacts,
            hasCallPhone = hasCall,
            hasReadPhoneState = hasPhoneState,
            hasAnswerPhoneCalls = hasAnswerCalls,
            hasRecordAudio = hasAudio
        )
    }

    private val _permissionState = MutableStateFlow(queryCurrentPermissions())
    val permissionState: StateFlow<CallPermissionState> = _permissionState.asStateFlow()

    fun checkPermissions(): CallPermissionState {
        val state = queryCurrentPermissions()
        _permissionState.value = state
        return state
    }

    fun hasReadContacts(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCallPhone(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasReadPhoneState(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAnswerPhoneCalls(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasRecordAudio(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getMissingPermissionReason(): MissingPermissionReason? {
        val hasContacts = hasReadContacts()
        val hasCall = hasCallPhone()
        return when {
            !hasContacts && !hasCall -> MissingPermissionReason.BothMissing
            !hasContacts -> MissingPermissionReason.ContactsMissing
            !hasCall -> MissingPermissionReason.CallPhoneMissing
            else -> null
        }
    }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )

        val ALL_CALL_HANDLING_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.RECORD_AUDIO
        )
    }
}

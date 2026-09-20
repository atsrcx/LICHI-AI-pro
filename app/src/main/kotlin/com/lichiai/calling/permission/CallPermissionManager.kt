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
    val hasCallPhone: Boolean = false
) {
    val allGranted: Boolean get() = hasReadContacts && hasCallPhone
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

        return CallPermissionState(
            hasReadContacts = hasContacts,
            hasCallPhone = hasCall
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
    }
}

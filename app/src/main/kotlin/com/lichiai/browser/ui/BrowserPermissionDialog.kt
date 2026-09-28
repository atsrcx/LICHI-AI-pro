package com.lichiai.browser.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.lichiai.browser.permissions.BrowserPermissionPrompt

@Composable
fun BrowserPermissionDialog(
    prompt: BrowserPermissionPrompt,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        title = {
            Text("Website Permission Request")
        },
        text = {
            Text(
                "\"${prompt.origin}\" is requesting permission to access: ${prompt.resources.joinToString(", ")}.\n\nDo you want to allow this site access?"
            )
        },
        confirmButton = {
            Button(onClick = prompt.onGrant) {
                Text("Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = prompt.onDeny) {
                Text("Block")
            }
        }
    )
}

package com.lichiai.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.R
import com.lichiai.data.Attachment
import com.lichiai.util.AttachmentLoader

@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<Attachment>,
    onAttachmentsChange: (List<Attachment>) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    enabled: Boolean = true,
    placeholder: String = "Message LICHI–AI...",
    modifier: Modifier = Modifier
) {
    val focus = LocalFocusManager.current
    val ctx = LocalContext.current
    var attachMenuOpen by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = AttachmentLoader.queryNameSize(ctx.contentResolver, uri)
            if (size in 1..AttachmentLoader.MAX_ATTACHMENT_BYTES || size <= 0) {
                ctx.contentResolver.runCatching {
                    takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
                onAttachmentsChange(
                    attachments + Attachment(
                        type = "image", uri = uri.toString(),
                        mimeType = mime, name = name, sizeBytes = size
                    )
                )
            } else {
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(
                        R.string.attachment_too_large,
                        AttachmentLoader.MAX_ATTACHMENT_BYTES / 1024 / 1024
                    ),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val (name, size) = AttachmentLoader.queryNameSize(ctx.contentResolver, uri)
            if (size in 1..AttachmentLoader.MAX_ATTACHMENT_BYTES || size <= 0) {
                ctx.contentResolver.runCatching {
                    takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                val isImage = mime.startsWith("image/")
                onAttachmentsChange(
                    attachments + Attachment(
                        type = if (isImage) "image" else "file",
                        uri = uri.toString(),
                        mimeType = mime, name = name, sizeBytes = size
                    )
                )
            } else {
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(
                        R.string.attachment_too_large,
                        AttachmentLoader.MAX_ATTACHMENT_BYTES / 1024 / 1024
                    ),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        // Pending Attachments row
        if (attachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(attachments, key = { it.uri }) { att ->
                    AttachmentChip(att = att, onRemove = {
                        onAttachmentsChange(attachments - att)
                    })
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // Pill Capsule Message Composer
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = LichiVisualTokens.SurfaceWhite,
            shadowElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(32.dp),
                    spotColor = LichiVisualTokens.SoftPillShadow,
                    ambientColor = LichiVisualTokens.SoftPillShadow
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Attachment Plus Button
                Box {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true),
                                onClick = { attachMenuOpen = true }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Attach media or files",
                            tint = LichiVisualTokens.TextNavyMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = attachMenuOpen,
                        onDismissRequest = { attachMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_image)) },
                            leadingIcon = { Icon(Icons.Default.Image, null, tint = LichiVisualTokens.BrandPurple) },
                            onClick = {
                                attachMenuOpen = false
                                pickImage.launch("image/*")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_file)) },
                            leadingIcon = { Icon(Icons.Default.AttachFile, null, tint = LichiVisualTokens.BrandPurple) },
                            onClick = {
                                attachMenuOpen = false
                                pickFile.launch("*/*")
                            }
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // Text Field with Hint
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp, max = 140.dp)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = LichiVisualTokens.TextGraySubtle,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal
                            )
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = LichiVisualTokens.TextNavy,
                            fontSize = 15.sp,
                            lineHeight = 21.sp
                        ),
                        cursorBrush = SolidColor(LichiVisualTokens.BrandPurple),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        maxLines = 5,
                        enabled = enabled
                    )
                }

                Spacer(Modifier.width(6.dp))

                // Right Send / Stop Dark Navy Circular Button
                val canSend = (value.trim().isNotEmpty() || attachments.isNotEmpty()) && !isStreaming && enabled
                val buttonBg = when {
                    isStreaming -> LichiVisualTokens.BrandPurple
                    canSend -> LichiVisualTokens.TextNavy
                    else -> LichiVisualTokens.TextNavy
                }

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(buttonBg)
                        .clickable(
                            enabled = isStreaming || canSend,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = Color.White)
                        ) {
                            if (isStreaming) {
                                onStop()
                            } else if (canSend) {
                                focus.clearFocus()
                                onSend()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.ArrowUpward,
                        contentDescription = if (isStreaming) "Stop generating" else "Send message",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(att: Attachment, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LichiVisualTokens.BrandPurpleSoftBg)
            .border(0.8.dp, LichiVisualTokens.BrandPurple.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (att.type == "image") Icons.Default.Image else Icons.Default.AttachFile,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = LichiVisualTokens.BrandPurple
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = att.name,
                color = LichiVisualTokens.TextNavy,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
            Text(
                text = AttachmentLoader.formatBytes(att.sizeBytes),
                color = LichiVisualTokens.TextGray,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove attachment",
                modifier = Modifier.size(12.dp),
                tint = LichiVisualTokens.TextGray
            )
        }
    }
}

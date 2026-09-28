package com.lichiai.ui.spy

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.model.PlatformMediaItem
import com.lichiai.spy.model.PlatformProfile
import com.lichiai.ui.LichiVisualTokens

/**
 * Reusable Production-grade Profile Intelligence Card for Lichi Platform Intelligence (#Spy).
 * Supports full rich profile headers, compact live preview, overview statistics, bio expansion,
 * media gallery thumbnails, public email/phone cards, and website actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformProfileCard(
    profile: PlatformProfile,
    modifier: Modifier = Modifier,
    onAnalyzeWebsite: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var isBioExpanded by remember { mutableStateOf(false) }
    var isPreviewExpanded by remember { mutableStateOf(false) }
    var copiedItem by remember { mutableStateOf<String?>(null) }

    val platformColor = when (profile.platform) {
        PlatformType.INSTAGRAM -> Color(0xFFE1306C)
        PlatformType.YOUTUBE, PlatformType.YOUTUBE_SHORTS -> Color(0xFFFF0000)
        PlatformType.TIKTOK -> Color(0xFF000000)
        PlatformType.TWITTER_X -> Color(0xFF1DA1F2)
        PlatformType.REDDIT -> Color(0xFFFF4500)
        PlatformType.GITHUB -> Color(0xFF24292E)
        PlatformType.LINKEDIN -> Color(0xFF0077B5)
        PlatformType.FACEBOOK -> Color(0xFF1877F2)
        PlatformType.SPOTIFY -> Color(0xFF1DB954)
        else -> LichiVisualTokens.BrandPurple
    }

    val primaryUrl = profile.profileUrl.ifBlank {
        when (profile.platform) {
            PlatformType.INSTAGRAM -> "https://www.instagram.com/${profile.username}/"
            PlatformType.YOUTUBE -> "https://www.youtube.com/@${profile.username}"
            PlatformType.REDDIT -> "https://www.reddit.com/r/${profile.username}"
            PlatformType.TIKTOK -> "https://www.tiktok.com/@${profile.username}"
            PlatformType.TWITTER_X -> "https://twitter.com/${profile.username}"
            PlatformType.GITHUB -> "https://github.com/${profile.username}"
            else -> ""
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(platformColor.copy(alpha = 0.5f), LichiVisualTokens.BrandPurple.copy(alpha = 0.3f))
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Intelligence Top Label
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(platformColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = profile.platform.displayName.take(1),
                            color = platformColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "🔎 LICHI INTELLIGENCE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = platformColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = profile.platform.displayName,
                        color = platformColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // 2. Profile Header (Avatar, Names, Verified, Category)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Profile Avatar with Coil
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .border(2.dp, platformColor.copy(alpha = 0.4f), CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    if (profile.avatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(profile.avatarUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "${profile.username} avatar",
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else if (profile.publicPhone.isNotBlank() && profile.username.isBlank()) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Phone Intelligence",
                            tint = platformColor,
                            modifier = Modifier.size(30.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Avatar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    val headerTitle = when {
                        profile.displayName.isNotBlank() -> profile.displayName
                        profile.username.isNotBlank() -> "@${profile.username}"
                        profile.publicPhone.isNotBlank() -> profile.publicPhone
                        else -> "Public Intelligence"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (profile.isVerified == true) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = "Verified",
                                tint = Color(0xFF1DA1F2),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (profile.username.isNotBlank() && profile.displayName != "@${profile.username}" && headerTitle != "@${profile.username}") {
                        Text(
                            text = "@${profile.username}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (profile.publicPhone.isNotBlank() && headerTitle != profile.publicPhone) {
                        Text(
                            text = profile.publicPhone,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (profile.category.isNotBlank()) {
                        Text(
                            text = profile.category,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1
                        )
                    }
                }

                if (primaryUrl.isNotBlank()) {
                    IconButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(primaryUrl))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Open profile link",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 3. Compact Live Profile Preview Panel
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                ),
                border = borderBrush(platformColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = platformColor
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Profile Preview",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (primaryUrl.isNotBlank()) {
                                Text(
                                    text = "Open",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = platformColor,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier
                                        .clickable {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(primaryUrl))
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            Text(
                                text = if (isPreviewExpanded) "Compact" else "Expand",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier
                                    .clickable { isPreviewExpanded = !isPreviewExpanded }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Visible bio snippet in preview
                    if (profile.bio.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = profile.bio,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp
                            ),
                            maxLines = if (isPreviewExpanded) 8 else 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Recent Media Thumbnails in preview
                    if (profile.recentMedia.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            profile.recentMedia.take(if (isPreviewExpanded) 6 else 3).forEach { media ->
                                MediaThumbnailItem(media = media, platformColor = platformColor)
                            }
                        }
                    }
                }
            }

            // 4. Statistics Overview Grid
            val statsList = buildList {
                if (profile.followers.isNotBlank()) add("Followers" to profile.followers)
                if (profile.following.isNotBlank()) add("Following" to profile.following)
                if (profile.postCount.isNotBlank()) add("Posts" to profile.postCount)
                if (profile.subscriberCount.isNotBlank()) add("Subscribers" to profile.subscriberCount)
                if (profile.views.isNotBlank()) add("Views" to profile.views)
                if (profile.isVerified != null) add("Verified" to if (profile.isVerified) "Yes" else "No")
            }

            if (statsList.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    statsList.forEach { (label, value) ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            border = CardDefaults.outlinedCardBorder()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 5. Bio Section (if not expanded in preview)
            if (profile.bio.isNotBlank() && !isPreviewExpanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "📝 Bio",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = profile.bio,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp
                        ),
                        maxLines = if (isBioExpanded) 20 else 3,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (profile.bio.length > 120) {
                        Text(
                            text = if (isBioExpanded) "Show less" else "Show more",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier
                                .clickable { isBioExpanded = !isBioExpanded }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }

            // 6. Public Contact Intelligence Cards (Email & Phone)
            if (profile.publicEmail.isNotBlank() || profile.publicPhone.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Text(
                    text = "💼 Public Business Contacts",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (profile.publicEmail.isNotBlank()) {
                    PublicContactCardItem(
                        icon = Icons.Default.Email,
                        title = "Public Email",
                        value = profile.publicEmail,
                        onCopy = {
                            clipboard.setText(AnnotatedString(profile.publicEmail))
                            copiedItem = "Email"
                        },
                        onAction = {
                            try {
                                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${profile.publicEmail}"))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        actionLabel = "Mail"
                    )
                }

                if (profile.publicPhone.isNotBlank()) {
                    PublicContactCardItem(
                        icon = Icons.Default.Phone,
                        title = "Public Phone",
                        value = profile.publicPhone,
                        onCopy = {
                            clipboard.setText(AnnotatedString(profile.publicPhone))
                            copiedItem = "Phone"
                        },
                        onAction = {
                            try {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${profile.publicPhone}"))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        actionLabel = "Call"
                    )
                }
            }

            // 7. Website & Link Section
            if (profile.website.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🔗 Website",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = profile.website,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.5.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (onAnalyzeWebsite != null) {
                            OutlinedButton(
                                onClick = { onAnalyzeWebsite(profile.website) },
                                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Analyze", fontSize = 11.5.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    val url = if (!profile.website.startsWith("http")) "https://${profile.website}" else profile.website
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Visit", fontSize = 11.5.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaThumbnailItem(
    media: PlatformMediaItem,
    platformColor: Color
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(width = 80.dp, height = 80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                if (media.mediaUrl.isNotBlank()) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(media.mediaUrl))
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (media.thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(media.thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = media.caption.ifBlank { "Recent post" },
                modifier = Modifier
                    .size(width = 80.dp, height = 80.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Default.Public,
                contentDescription = null,
                tint = platformColor.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }

        if (media.likesCount.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "❤️ ${media.likesCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                )
            }
        }
    }
}

@Composable
private fun PublicContactCardItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    onCopy: () -> Unit,
    onAction: () -> Unit,
    actionLabel: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier.height(28.dp),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                ) {
                    Text(actionLabel, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun borderBrush(color: Color): androidx.compose.foundation.BorderStroke {
    return CardDefaults.outlinedCardBorder().copy(
        brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.15f)))
    )
}

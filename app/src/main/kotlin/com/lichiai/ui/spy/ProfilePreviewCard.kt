package com.lichiai.ui.spy

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
 * Production-grade Profile Preview Card component for Lichi Platform Intelligence (#Spy).
 * Conditionally renders the avatar, stats, and a horizontal layout of media,
 * integrated directly into the chat message stream when previewRequested is true.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilePreviewCard(
    profile: PlatformProfile,
    modifier: Modifier = Modifier,
    onOpenProfile: (() -> Unit)? = null
) {
    val context = LocalContext.current

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
            .padding(vertical = 6.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(platformColor.copy(alpha = 0.6f), LichiVisualTokens.BrandPurple.copy(alpha = 0.3f))
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Platform Badge & Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(platformColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = profile.platform.displayName.take(1),
                            color = platformColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "🔎 PROFILE PREVIEW",
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            // 1. Avatar and Names (Conditionally rendered)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Conditionally render avatar
                if (profile.avatarUrl.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .border(2.dp, platformColor.copy(alpha = 0.4f), CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(profile.avatarUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = "${profile.username} avatar",
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (profile.displayName.isNotBlank()) profile.displayName else "@${profile.username}",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
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
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    if (profile.username.isNotBlank()) {
                        Text(
                            text = "@${profile.username}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (primaryUrl.isNotBlank()) {
                    IconButton(
                        onClick = {
                            try {
                                onOpenProfile?.invoke()
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(primaryUrl))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Open profile",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Bio preview if present
            if (profile.bio.isNotBlank()) {
                Text(
                    text = profile.bio,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 2. Stats (Conditionally rendered)
            val statsList = buildList {
                if (profile.followers.isNotBlank()) add("Followers" to profile.followers)
                if (profile.following.isNotBlank()) add("Following" to profile.following)
                if (profile.postCount.isNotBlank()) add("Posts" to profile.postCount)
                if (profile.subscriberCount.isNotBlank()) add("Subscribers" to profile.subscriberCount)
                if (profile.views.isNotBlank()) add("Views" to profile.views)
            }

            if (statsList.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    statsList.forEach { (label, value) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                            border = CardDefaults.outlinedCardBorder()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 3. Horizontal layout of media (Conditionally rendered)
            if (profile.recentMedia.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "📸 Recent Media",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    profile.recentMedia.take(6).forEach { media ->
                        PreviewMediaThumbnail(media = media, platformColor = platformColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewMediaThumbnail(
    media: PlatformMediaItem,
    platformColor: Color
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 72.dp)
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
                contentDescription = media.caption.ifBlank { "Media preview" },
                modifier = Modifier
                    .size(width = 72.dp, height = 72.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Default.Public,
                contentDescription = null,
                tint = platformColor.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }

        if (media.likesCount.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "❤️ ${media.likesCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp)
                )
            }
        }
    }
}

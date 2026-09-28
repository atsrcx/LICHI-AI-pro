package com.lichiai.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// SEMANTIC COLOR SYSTEM FOR LICHI-AI
// ============================================================================
object LichiVisualTokens {
    val BackgroundLight = Color(0xFFF7F8FD)
    val SurfaceWhite = Color(0xFFFFFFFF)
    val SurfacePill = Color(0xFFFFFFFF)

    val BrandPurple = Color(0xFF7C3AED)
    val BrandPurpleGlow = Color(0x669333EA)
    val BrandPurpleLight = Color(0xFF8B5CF6)
    val BrandPurpleSoftBg = Color(0xFFF5F3FF)

    val TextNavy = Color(0xFF12131A)
    val TextNavyMuted = Color(0xFF4B5563)
    val TextGray = Color(0xFF6B7280)
    val TextGraySubtle = Color(0xFF9CA3AF)

    val StatusGreen = Color(0xFF10B981)
    val StatusPink = Color(0xFFEC4899)

    // Suggestion Cards Pastels
    val CardConceptsBg = Color(0xFFF3E8FF)
    val CardConceptsFg = Color(0xFF7C3AED)
    val CardConceptsIcon = Color(0xFFD97706)

    val CardCreativityBg = Color(0xFFEFF6FF)
    val CardCreativityFg = Color(0xFF2563EB)
    val CardCreativityIcon = Color(0xFF3B82F6)

    val CardProductivityBg = Color(0xFFECFDF5)
    val CardProductivityFg = Color(0xFF059669)
    val CardProductivityIcon = Color(0xFF10B981)

    val CardLanguagesBg = Color(0xFFFDF2F8)
    val CardLanguagesFg = Color(0xFFDB2777)
    val CardLanguagesIcon = Color(0xFFF43F5E)

    // Pill Shadows
    val SoftPillShadow = Color(0x0F000000)
    val CardElevationShadow = Color(0x0A000000)
}

/**
 * Top Left Brand Pill:
 * [ ☰ ]  LICHI-AI  ●
 */
@Composable
fun LichiBrandPill(
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = LichiVisualTokens.SurfaceWhite,
        shadowElevation = 2.dp,
        modifier = modifier
            .height(44.dp)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(26.dp),
                spotColor = LichiVisualTokens.SoftPillShadow,
                ambientColor = LichiVisualTokens.SoftPillShadow
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, color = LichiVisualTokens.BrandPurple),
                    onClick = onMenu
                )
                .padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Open Navigation Drawer",
                tint = LichiVisualTokens.TextNavy,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "LICHI–AI",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    fontSize = 14.5.sp
                ),
                color = LichiVisualTokens.TextNavy
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(LichiVisualTokens.BrandPurple)
            )
        }
    }
}

/**
 * Top Right Control Pill:
 * [ ● provider/model ▾ | 🌐 | 🎧 | ✏️ ]
 */
@Composable
fun LichiControlPill(
    modelLabel: String,
    onPickModel: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenVoiceMode: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = LichiVisualTokens.SurfaceWhite,
        shadowElevation = 2.dp,
        modifier = modifier
            .height(44.dp)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(26.dp),
                spotColor = LichiVisualTokens.SoftPillShadow,
                ambientColor = LichiVisualTokens.SoftPillShadow
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            // Model Selector Segment
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = onPickModel
                    )
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                // Green status dot
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(LichiVisualTokens.StatusGreen)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = modelLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    ),
                    color = LichiVisualTokens.TextNavyMuted,
                    modifier = Modifier.widthIn(max = 110.dp)
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = "Select Model",
                    tint = LichiVisualTokens.TextGraySubtle,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Vertical hairline divider
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(1.dp)
                    .height(18.dp)
                    .background(Color(0xFFE5E7EB))
            )

            // Globe / Browser Action
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = onOpenBrowser
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = "Browser Agent",
                    tint = LichiVisualTokens.TextNavyMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(2.dp))

            // Headphones / Voice Mode Action
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = onOpenVoiceMode
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = "Voice Mode",
                    tint = LichiVisualTokens.TextNavyMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(2.dp))

            // Pencil / New Chat Action
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = onNewChat
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "New Conversation",
                    tint = LichiVisualTokens.TextNavyMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

/**
 * Centered Status Badge:
 * "420 T/S • NEURAL ENGINE ACTIVE"
 */
@Composable
fun LichiStatusBadge(
    text: String = "420 T/S • NEURAL ENGINE ACTIVE",
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = LichiVisualTokens.SurfaceWhite,
        shadowElevation = 1.dp,
        modifier = modifier
            .border(
                width = 1.dp,
                color = LichiVisualTokens.BrandPurple.copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp)
            )
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(20.dp),
                spotColor = LichiVisualTokens.BrandPurpleGlow,
                ambientColor = LichiVisualTokens.BrandPurpleGlow
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(LichiVisualTokens.BrandPurple)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    fontSize = 11.sp
                ),
                color = LichiVisualTokens.BrandPurple
            )
        }
    }
}

/**
 * Custom 4-pointed Sparkle Logo Mark
 */
@Composable
fun LichiSparkleLogo(
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.44f
        val cy = h * 0.54f
        val primaryRadius = w * 0.38f

        // Draw primary dark navy 4-pointed sparkle
        val mainPath = Path().apply {
            moveTo(cx, cy - primaryRadius)
            cubicTo(cx + primaryRadius * 0.08f, cy - primaryRadius * 0.15f, cx + primaryRadius * 0.15f, cy - primaryRadius * 0.08f, cx + primaryRadius, cy)
            cubicTo(cx + primaryRadius * 0.15f, cy + primaryRadius * 0.08f, cx + primaryRadius * 0.08f, cy + primaryRadius * 0.15f, cx, cy + primaryRadius)
            cubicTo(cx - primaryRadius * 0.08f, cy + primaryRadius * 0.15f, cx - primaryRadius * 0.15f, cy + primaryRadius * 0.08f, cx - primaryRadius, cy)
            cubicTo(cx - primaryRadius * 0.15f, cy - primaryRadius * 0.08f, cx - primaryRadius * 0.08f, cy - primaryRadius * 0.15f, cx, cy - primaryRadius)
            close()
        }
        drawPath(path = mainPath, color = LichiVisualTokens.TextNavy)

        // Draw smaller purple sparkle in the upper right quadrant
        val scx = w * 0.72f
        val scy = h * 0.32f
        val smallRadius = w * 0.16f

        val smallPath = Path().apply {
            moveTo(scx, scy - smallRadius)
            cubicTo(scx + smallRadius * 0.08f, scy - smallRadius * 0.15f, scx + smallRadius * 0.15f, scy - smallRadius * 0.08f, scx + smallRadius, scy)
            cubicTo(scx + smallRadius * 0.15f, scy + smallRadius * 0.08f, scx + smallRadius * 0.08f, scy + smallRadius * 0.15f, scx, scy + smallRadius)
            cubicTo(scx - smallRadius * 0.08f, scy + smallRadius * 0.15f, scx - smallRadius * 0.15f, scy + smallRadius * 0.08f, scx - smallRadius, scy)
            cubicTo(scx - smallRadius * 0.15f, scy - smallRadius * 0.08f, scx - smallRadius * 0.08f, scy - smallRadius * 0.15f, scx, scy - smallRadius)
            close()
        }
        drawPath(path = smallPath, color = LichiVisualTokens.BrandPurple)
    }
}

/**
 * Central Lichi Logo Tile with soft ambient purple radial glow and floating indicator dot
 */
@Composable
fun LichiLogoTile(
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "tile_glow")
    val glowPulse by infinite.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )

    Box(
        modifier = modifier.size(150.dp),
        contentAlignment = Alignment.Center
    ) {
        // Ambient Radial Purple Glow Behind
        Box(
            modifier = Modifier
                .size(145.dp)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                LichiVisualTokens.BrandPurple.copy(alpha = 0.28f * glowPulse),
                                LichiVisualTokens.BrandPurpleLight.copy(alpha = 0.12f * glowPulse),
                                Color.Transparent
                            ),
                            center = center,
                            radius = size.width * 0.58f
                        )
                    )
                }
        )

        // The White Rounded Tile
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = LichiVisualTokens.SurfaceWhite,
            shadowElevation = 3.dp,
            modifier = Modifier
                .size(108.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(36.dp),
                    spotColor = LichiVisualTokens.SoftPillShadow,
                    ambientColor = LichiVisualTokens.SoftPillShadow
                )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(20.dp)
            ) {
                LichiSparkleLogo(modifier = Modifier.size(62.dp))
            }
        }

        // Floating circular status indicator at the top right corner
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(LichiVisualTokens.SurfaceWhite)
                .padding(2.dp)
                .clip(CircleShape)
                .background(LichiVisualTokens.StatusPink)
                .border(1.5.dp, Color.White, CircleShape)
        )
    }
}

/**
 * Main Greeting:
 * "Hi, I'm LICHI-AI"
 * and two-line subtitle
 */
@Composable
fun LichiGreetingHeader(
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth()
    ) {
        val headingText = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    color = LichiVisualTokens.TextNavy,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    letterSpacing = (-0.5).sp
                )
            ) {
                append("Hi, I'm ")
            }
            withStyle(
                SpanStyle(
                    color = LichiVisualTokens.BrandPurple,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    letterSpacing = (-0.5).sp
                )
            ) {
                append("LICHI–AI")
            }
        }

        Text(
            text = headingText,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "A fast, lightweight AI assistant client.\nBring your own keys & on-device vision.",
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Normal
            ),
            color = LichiVisualTokens.TextGray,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Suggestion Card Model
 */
data class LichiSuggestionData(
    val category: String,
    val prompt: String,
    val icon: ImageVector,
    val categoryColor: Color,
    val tileBgColor: Color,
    val iconColor: Color
)

/**
 * Interactive Pastel Suggestion Card
 */
@Composable
fun LichiSuggestionCard(
    data: LichiSuggestionData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = LichiVisualTokens.SurfaceWhite,
        shadowElevation = 1.5.dp,
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.dp,
                shape = RoundedCornerShape(22.dp),
                spotColor = LichiVisualTokens.CardElevationShadow,
                ambientColor = LichiVisualTokens.CardElevationShadow
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = data.categoryColor.copy(alpha = 0.2f)),
                onClick = onClick
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Left Pastel Icon Tile
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(data.tileBgColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = data.icon,
                    contentDescription = null,
                    tint = data.iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            // Category & Prompt text
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = data.category,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontSize = 10.5.sp
                    ),
                    color = data.categoryColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = data.prompt,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.5.sp
                    ),
                    color = LichiVisualTokens.TextNavy,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(10.dp))

            // Subtle Right Arrow Indicator
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF3F4F6).copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = LichiVisualTokens.TextGraySubtle,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

/**
 * Bottom Capability Line:
 * [📱] Screen Vision  •  [🌐] Live Search  •  [⚡] Groq 420 t/s
 */
@Composable
fun LichiCapabilityLine(
    speedLabel: String = "Groq 420 t/s",
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Phone / Vision
        Icon(
            imageVector = Icons.Default.PhoneAndroid,
            contentDescription = null,
            tint = Color(0xFF60A5FA),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Screen Vision",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = LichiVisualTokens.TextGray
        )

        // Bullet
        Text(
            text = "  •  ",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = LichiVisualTokens.TextGraySubtle
        )

        // Globe / Live Search
        Icon(
            imageVector = Icons.Default.Public,
            contentDescription = null,
            tint = Color(0xFF38BDF8),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "Live Search",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = LichiVisualTokens.TextGray
        )

        // Bullet
        Text(
            text = "  •  ",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = LichiVisualTokens.TextGraySubtle
        )

        // Lightning / Speed
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = null,
            tint = Color(0xFFFBBF24),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = speedLabel,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = LichiVisualTokens.TextGray
        )
    }
}

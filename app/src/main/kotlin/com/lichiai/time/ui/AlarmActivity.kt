package com.lichiai.time.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.time.data.ReminderRepository
import com.lichiai.time.model.ReminderItem
import com.lichiai.time.receiver.AlarmReceiver
import com.lichiai.time.service.AlarmForegroundService
import com.lichiai.ui.LichiSparkleLogo
import com.lichiai.ui.LichiVisualTokens
import com.lichiai.ui.theme.LichiAITheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureLockScreenVisibility()

        val reminderId = intent.getStringExtra(AlarmReceiver.EXTRA_REMINDER_ID) ?: ""

        setContent {
            LichiAITheme {
                AlarmScreenContent(
                    reminderId = reminderId,
                    onSnooze = { mins ->
                        val snoozeIntent = Intent(this, AlarmForegroundService::class.java).apply {
                            action = AlarmForegroundService.ACTION_SNOOZE
                            putExtra(AlarmReceiver.EXTRA_REMINDER_ID, reminderId)
                            putExtra("minutes", mins)
                        }
                        startService(snoozeIntent)
                        finish()
                    },
                    onDismiss = {
                        val completeIntent = Intent(this, AlarmForegroundService::class.java).apply {
                            action = AlarmForegroundService.ACTION_COMPLETE
                            putExtra(AlarmReceiver.EXTRA_REMINDER_ID, reminderId)
                        }
                        startService(completeIntent)
                        finish()
                    }
                )
            }
        }
    }

    private fun configureLockScreenVisibility() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }
}

@Composable
fun AlarmScreenContent(
    reminderId: String,
    onSnooze: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var item by remember { mutableStateOf<ReminderItem?>(null) }

    LaunchedEffect(reminderId) {
        val repo = ReminderRepository(context.applicationContext)
        item = repo.getById(reminderId)
    }

    val timeFormatted = remember {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }
    val dateFormatted = remember {
        SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
    }

    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infinite.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0E17))
            .padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Brand & Time
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LichiSparkleLogo(modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "LICHI TIME ENGINE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        fontSize = 12.sp
                    ),
                    color = LichiVisualTokens.BrandPurpleLight
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = timeFormatted,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 54.sp,
                    letterSpacing = (-1).sp
                ),
                color = Color.White
            )
            Text(
                text = dateFormatted,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF9CA3AF)
            )
        }

        // Center Animated Alarm Ring Indicator
        Box(
            modifier = Modifier.size(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(190.dp * pulseScale)
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    LichiVisualTokens.BrandPurple.copy(alpha = 0.45f),
                                    Color.Transparent
                                )
                            )
                        )
                    }
            )

            Surface(
                shape = CircleShape,
                color = Color(0xFF1E1B2E),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .size(120.dp)
                    .border(2.dp, LichiVisualTokens.BrandPurpleLight, CircleShape)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (item?.isAlarm == true) Icons.Default.Alarm else Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = LichiVisualTokens.BrandPurpleLight,
                        modifier = Modifier.size(54.dp)
                    )
                }
            }
        }

        // Bottom Title & Action Buttons
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = item?.title ?: "Alarm",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                ),
                color = Color.White,
                textAlign = TextAlign.Center
            )

            if (!item?.description.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item?.description ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD1D5DB),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(36.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Snooze Button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF2E2A40),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .clickable { onSnooze(item?.snoozeDurationMinutes ?: 10) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Snooze,
                                contentDescription = "Snooze",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Snooze (+${item?.snoozeDurationMinutes ?: 10}m)",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF9CA3AF)
                    )
                }

                // Dismiss / Complete Button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = LichiVisualTokens.BrandPurple,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (item?.isAlarm == true) Icons.Default.Close else Icons.Default.Check,
                                contentDescription = "Dismiss",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (item?.isAlarm == true) "Dismiss" else "Complete",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

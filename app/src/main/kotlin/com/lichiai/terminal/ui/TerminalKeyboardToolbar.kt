package com.lichiai.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.terminal.emulator.TerminalInputEncoder

@Composable
fun TerminalKeyboardToolbar(
    onSendKey: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton(text = "ESC") { onSendKey(TerminalInputEncoder.ESCAPE) }
            KeyButton(text = "TAB") { onSendKey(TerminalInputEncoder.TAB) }
            KeyButton(text = "CTRL-C") { onSendKey(TerminalInputEncoder.CTRL_C) }
            KeyButton(text = "CTRL-D") { onSendKey(TerminalInputEncoder.CTRL_D) }
            KeyButton(text = "CTRL-Z") { onSendKey(TerminalInputEncoder.CTRL_Z) }
            KeyButton(text = "CTRL-L") { onSendKey(TerminalInputEncoder.CTRL_L) }

            KeyIconButton(icon = Icons.Default.ArrowUpward, desc = "Up") { onSendKey(TerminalInputEncoder.ARROW_UP) }
            KeyIconButton(icon = Icons.Default.ArrowDownward, desc = "Down") { onSendKey(TerminalInputEncoder.ARROW_DOWN) }
            KeyIconButton(icon = Icons.Default.ArrowBack, desc = "Left") { onSendKey(TerminalInputEncoder.ARROW_LEFT) }
            KeyIconButton(icon = Icons.Default.ArrowForward, desc = "Right") { onSendKey(TerminalInputEncoder.ARROW_RIGHT) }

            KeyButton(text = "|") { onSendKey("|") }
            KeyButton(text = "~") { onSendKey("~") }
            KeyButton(text = "/") { onSendKey("/") }
            KeyButton(text = "-") { onSendKey("-") }
            KeyButton(text = "_") { onSendKey("_") }
            KeyButton(text = "$") { onSendKey("$") }
            KeyButton(text = ">") { onSendKey(">") }
            KeyButton(text = "<") { onSendKey("<") }
            KeyButton(text = "&") { onSendKey("&") }
            KeyButton(text = ";") { onSendKey(";") }
            KeyButton(text = "\"") { onSendKey("\"") }
            KeyButton(text = "'") { onSendKey("'") }
        }
    }
}

@Composable
private fun KeyButton(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.height(32.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun KeyIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.size(32.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = desc,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

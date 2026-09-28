package com.lichiai.terminal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.terminal.core.TerminalSession
import com.lichiai.terminal.emulator.TerminalLine
import com.lichiai.terminal.model.TerminalSettings
import com.lichiai.terminal.model.TerminalTheme
import kotlinx.coroutines.launch

@Composable
fun TerminalOutputView(
    session: TerminalSession,
    theme: TerminalTheme,
    settings: TerminalSettings,
    searchQuery: String = "",
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val bufferVersion by session.bufferVersion.collectAsState()
    val lines = remember(bufferVersion) { session.getLinesSnapshot() }
    val listState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()

    var autoScrollEnabled by remember { mutableStateOf(true) }
    var unreadCountSinceScrollUp by remember { mutableIntStateOf(0) }
    var previousLinesCount by remember { mutableIntStateOf(lines.size) }

    // Check whether user is near the very bottom
    val isAtBottom by remember {
        derivedStateOf {
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems == 0) true
            else {
                val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleItem >= totalItems - 2
            }
        }
    }

    // Monitor manual scrolling
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom }.collect { atBottom ->
            if (atBottom) {
                autoScrollEnabled = true
                unreadCountSinceScrollUp = 0
            } else if (listState.isScrollInProgress) {
                autoScrollEnabled = false
            }
        }
    }

    // Handle auto-scrolling vs unread count tracking
    LaunchedEffect(bufferVersion) {
        val newLines = lines.size - previousLinesCount
        previousLinesCount = lines.size
        if (lines.isNotEmpty()) {
            if (autoScrollEnabled) {
                listState.scrollToItem(lines.size - 1)
            } else if (newLines > 0) {
                unreadCountSinceScrollUp += newLines
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(theme.backgroundHex))
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .horizontalScroll(horizontalScrollState)
            ) {
                itemsIndexed(lines, key = { index, _ -> "${session.id}_ln_$index" }) { _, line ->
                    TerminalLineRow(
                        line = line,
                        theme = theme,
                        settings = settings,
                        searchQuery = searchQuery
                    )
                }
            }
        }

        // Floating "Jump to bottom" pill when scrolled up
        AnimatedVisibility(
            visible = !isAtBottom && lines.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 12.dp, end = 12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        autoScrollEnabled = true
                        unreadCountSinceScrollUp = 0
                        scope.launch {
                            listState.animateScrollToItem(lines.size - 1)
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        contentDescription = "Jump to Bottom",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (unreadCountSinceScrollUp > 0) "Bottom (+$unreadCountSinceScrollUp)" else "Bottom",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalLineRow(
    line: TerminalLine,
    theme: TerminalTheme,
    settings: TerminalSettings,
    searchQuery: String
) {
    val lineText = remember(line) { line.toPlainText() }
    val searchHighlightIndices = remember(lineText, searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else {
            val list = mutableListOf<IntRange>()
            var start = 0
            while (start < lineText.length) {
                val index = lineText.indexOf(searchQuery, start, ignoreCase = true)
                if (index == -1) break
                list.add(index until (index + searchQuery.length))
                start = index + searchQuery.length
            }
            list
        }
    }

    val annotated = remember(line, theme, searchHighlightIndices) {
        buildAnnotatedString {
            val lastSignificant = line.cells.indexOfLast { it.char != ' ' || it.style != com.lichiai.terminal.emulator.AnsiStyle.DEFAULT }
            if (line.cells.isEmpty() || lastSignificant == -1) {
                append(" ")
            } else {
                for (colIndex in 0..lastSignificant) {
                    val cell = line.cells[colIndex]
                    val fg = cell.style.resolveFgColor(theme)
                    val bg = cell.style.resolveBgColor(theme)
                    val isSearchMatch = searchHighlightIndices.any { colIndex in it }

                    val finalFg = if (isSearchMatch) Color.Black else fg
                    val finalBg = if (isSearchMatch) Color(0xFFFFD54F) // Highlight yellow
                    else if (cell.style.bgColorIndex != -1 || cell.style.customBgRgb != null || cell.style.inverse) bg
                    else Color.Transparent

                    withStyle(
                        SpanStyle(
                            color = finalFg,
                            background = finalBg,
                            fontWeight = if (cell.style.bold || isSearchMatch) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (cell.style.italic) FontStyle.Italic else FontStyle.Normal,
                            textDecoration = if (cell.style.underline) TextDecoration.Underline else TextDecoration.None
                        )
                    ) {
                        append(cell.char)
                    }
                }
            }
        }
    }

    Text(
        text = annotated,
        fontFamily = FontFamily.Monospace,
        fontSize = settings.fontSizeSp.sp,
        lineHeight = (settings.fontSizeSp * 1.25f).sp
    )
}

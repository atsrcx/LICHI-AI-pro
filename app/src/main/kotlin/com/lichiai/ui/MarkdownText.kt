package com.lichiai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lichiai.code.BlockExecutionState
import com.lichiai.code.CodeExecutionManager
import com.lichiai.code.CodeExecutionState
import com.lichiai.code.ExecutionResult
import kotlinx.coroutines.delay

/**
 * Modern AI Conversational Document & Structured Response Renderer for LICHI-AI.
 * 
 * Features:
 * - Natural open prose flow without artificial enclosing cards
 * - Real Executable Code Blocks (Python, Shell, Node) with Run, Cancel, and Output panel
 * - Prompt Blocks & Structured Config Blocks (JSON/XML/YAML)
 * - Hanging-indent lists, hierarchical headings, blockquotes, tables
 * - Theme-aware styling for Light & Dark mode
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    messageId: String = "msg",
    color: Color = MaterialTheme.colorScheme.onBackground
) {
    val uriHandler = LocalUriHandler.current
    val blocks = remember(text, messageId) { ResponseBlockParser.parse(text, messageId) }
    val isDark = MaterialTheme.colorScheme.background.red < 0.2f

    Column(modifier = modifier) {
        blocks.forEachIndexed { idx, block ->
            if (idx > 0) {
                val prev = blocks[idx - 1]
                val spacing = when {
                    block is ResponseBlock.Heading -> 14.dp
                    block is ResponseBlock.Code || prev is ResponseBlock.Code -> 12.dp
                    block is ResponseBlock.Prompt || prev is ResponseBlock.Prompt -> 12.dp
                    block is ResponseBlock.StructuredData || prev is ResponseBlock.StructuredData -> 12.dp
                    block is ResponseBlock.Table || prev is ResponseBlock.Table -> 12.dp
                    block is ResponseBlock.Quote || prev is ResponseBlock.Quote -> 10.dp
                    block is ResponseBlock.BulletItem && prev is ResponseBlock.BulletItem -> 4.dp
                    block is ResponseBlock.NumberedItem && prev is ResponseBlock.NumberedItem -> 4.dp
                    else -> 8.dp
                }
                Spacer(Modifier.height(spacing))
            }

            when (block) {
                is ResponseBlock.Heading -> {
                    val (style, topMargin) = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 28.sp,
                            letterSpacing = (-0.3).sp
                        ) to 4.dp
                        2 -> MaterialTheme.typography.titleMedium.copy(
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 25.sp,
                            letterSpacing = (-0.2).sp
                        ) to 3.dp
                        3 -> MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 23.sp
                        ) to 2.dp
                        else -> MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.SemiBold
                        ) to 0.dp
                    }

                    val inline = remember(block.text, isDark) {
                        ResponseInlineParser.parse(block.text, isDark = isDark)
                    }
                    Text(
                        text = inline,
                        color = color,
                        style = style,
                        modifier = Modifier.padding(top = topMargin)
                    )
                }

                is ResponseBlock.Paragraph -> {
                    val inline = remember(block.text, isDark) {
                        ResponseInlineParser.parse(block.text, isDark = isDark)
                    }
                    ClickableFormattedText(
                        annotatedString = inline,
                        color = color,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.5.sp,
                            lineHeight = 23.5.sp,
                            letterSpacing = 0.1.sp
                        ),
                        onUrlClick = { url ->
                            try { uriHandler.openUri(url) } catch (_: Exception) {}
                        }
                    )
                }

                is ResponseBlock.BulletItem -> {
                    val inline = remember(block.text, isDark) {
                        ResponseInlineParser.parse(block.text, isDark = isDark)
                    }
                    val indent = (block.indentLevel * 16).dp
                    val bulletSymbol = when (block.indentLevel % 3) {
                        0 -> "•"
                        1 -> "◦"
                        else -> "▪"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indent)
                    ) {
                        Text(
                            text = bulletSymbol,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        ClickableFormattedText(
                            annotatedString = inline,
                            color = color,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.5.sp,
                                lineHeight = 23.sp
                            ),
                            modifier = Modifier.weight(1f),
                            onUrlClick = { url ->
                                try { uriHandler.openUri(url) } catch (_: Exception) {}
                            }
                        )
                    }
                }

                is ResponseBlock.NumberedItem -> {
                    val inline = remember(block.text, isDark) {
                        ResponseInlineParser.parse(block.text, isDark = isDark)
                    }
                    val indent = (block.indentLevel * 16).dp

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = indent)
                    ) {
                        Text(
                            text = "${block.number}.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.widthIn(min = 24.dp).padding(end = 6.dp)
                        )
                        ClickableFormattedText(
                            annotatedString = inline,
                            color = color,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.5.sp,
                                lineHeight = 23.sp
                            ),
                            modifier = Modifier.weight(1f),
                            onUrlClick = { url ->
                                try { uriHandler.openUri(url) } catch (_: Exception) {}
                            }
                        )
                    }
                }

                is ResponseBlock.Quote -> {
                    val inline = remember(block.text, isDark) {
                        ResponseInlineParser.parse(block.text, isDark = isDark)
                    }
                    val quoteBg = if (isDark) Color(0xFF1C1D2B) else Color(0xFFF3F1FA)
                    val quoteBar = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(quoteBg)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .height(20.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(quoteBar)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = inline,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.sp,
                                fontStyle = FontStyle.Italic,
                                lineHeight = 22.sp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is ResponseBlock.Divider -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }

                is ResponseBlock.Code -> {
                    ExecutableCodeBlock(
                        blockId = block.id,
                        language = block.language,
                        code = block.code,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                is ResponseBlock.Prompt -> {
                    PromptCardBlock(
                        promptText = block.promptText,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                is ResponseBlock.StructuredData -> {
                    StructuredDataCardBlock(
                        type = block.type,
                        content = block.content,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                is ResponseBlock.Table -> {
                    TableBlockView(
                        table = block,
                        isDark = isDark,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * Clickable Text supporting [label](url) hyperlinks
 */
@Composable
private fun ClickableFormattedText(
    annotatedString: AnnotatedString,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onUrlClick: (String) -> Unit
) {
    val hasLinks = remember(annotatedString) {
        annotatedString.getStringAnnotations("URL", 0, annotatedString.length).isNotEmpty()
    }

    if (hasLinks) {
        ClickableText(
            text = annotatedString,
            style = style.copy(color = color),
            modifier = modifier,
            onClick = { offset ->
                val annotation = annotatedString.getStringAnnotations("URL", offset, offset).firstOrNull()
                if (annotation != null) {
                    onUrlClick(annotation.item)
                }
            }
        )
    } else {
        Text(
            text = annotatedString,
            color = color,
            style = style,
            modifier = modifier
        )
    }
}

/**
 * Executable Code Block Component with Toolbar (Language | ▶ Run | Copy)
 * and attached real Execution Output Panel.
 */
@Composable
fun ExecutableCodeBlock(
    blockId: String,
    language: String,
    code: String,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    val blockStates by CodeExecutionManager.blockStates.collectAsState()
    val execState = blockStates[blockId] ?: BlockExecutionState()

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val displayLang = remember(language) {
        if (language.isNotBlank()) language.uppercase() else "CODE"
    }

    val isExecutable = remember(language) {
        CodeExecutionManager.isLanguageExecutable(language)
    }

    // High contrast container colors
    val containerBg = Color(0xFF161722)
    val headerBg = Color(0xFF20212E)
    val borderColor = Color(0xFF2C2D3E)
    val langTextColor = Color(0xFF9E9EAF)

    val highlightedCode = remember(code, language) {
        SyntaxHighlighter.highlight(code, language)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
    ) {
        // 1. Top Header Toolbar: [ LANGUAGE           ▶ Run   Copy ]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Language badge
            Text(
                text = displayLang,
                color = langTextColor,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Real Run Button (only if language is executable)
                if (isExecutable) {
                    when (execState.state) {
                        CodeExecutionState.RUNNING -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF3826A8).copy(alpha = 0.4f))
                                    .clickable {
                                        CodeExecutionManager.cancelBlock(blockId)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(11.dp),
                                    strokeWidth = 1.6.dp,
                                    color = Color(0xFFA78BFA)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "Running...",
                                    color = Color(0xFFA78BFA),
                                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = Color(0xFFF87171),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                        CodeExecutionState.SUCCESS -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = true, color = Color.White)
                                    ) {
                                        CodeExecutionManager.executeBlock(blockId, language, code)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Run again",
                                    tint = Color(0xFF34D399),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Run again",
                                    color = Color(0xFF34D399),
                                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                )
                            }
                        }
                        CodeExecutionState.ERROR, CodeExecutionState.TIMEOUT, CodeExecutionState.CANCELLED -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = true, color = Color.White)
                                    ) {
                                        CodeExecutionManager.executeBlock(blockId, language, code)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Error",
                                    tint = Color(0xFFF87171),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Retry",
                                    color = Color(0xFFF87171),
                                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                )
                            }
                        }
                        CodeExecutionState.IDLE -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = ripple(bounded = true, color = Color.White)
                                    ) {
                                        CodeExecutionManager.executeBlock(blockId, language, code)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Run code",
                                    tint = Color(0xFF60A5FA),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = "Run",
                                    color = Color(0xFF60A5FA),
                                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                }

                // Copy Code Action
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = Color.White)
                        ) {
                            clipboard.setText(AnnotatedString(code))
                            copied = true
                        }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy code",
                        tint = if (copied) Color(0xFF34D399) else langTextColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (copied) "Copied!" else "Copy",
                        color = if (copied) Color(0xFF34D399) else langTextColor,
                        style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                    )
                }
            }
        }

        // 2. Code Body with Horizontal Scrolling
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                text = highlightedCode,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Normal
                )
            )
        }

        // 3. Real Execution Output Panel (appears when executed)
        if (execState.result != null) {
            val res = execState.result
            val isSuccess = res.success
            val outputBg = if (isSuccess) Color(0xFF10111A) else Color(0xFF181014)
            val outputBorder = if (isSuccess) Color(0xFF1E202E) else Color(0xFF3A1A22)
            var outputCopied by remember { mutableStateOf(false) }

            LaunchedEffect(outputCopied) {
                if (outputCopied) {
                    delay(2000)
                    outputCopied = false
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.5.dp,
                        color = outputBorder
                    )
                    .background(outputBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isSuccess) "OUTPUT" else "EXECUTION ERROR",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSuccess) Color(0xFF34D399) else Color(0xFFF87171),
                                letterSpacing = 0.5.sp
                            )
                        )
                        if (res.durationMs > 0) {
                            Text(
                                text = " · ${res.durationMs}ms",
                                style = TextStyle(fontSize = 10.sp, color = Color(0xFF6B7280))
                            )
                        }
                        Text(
                            text = " · exit ${res.exitCode}",
                            style = TextStyle(fontSize = 10.sp, color = Color(0xFF6B7280))
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Copy Output Button
                        Text(
                            text = if (outputCopied) "Copied!" else "Copy output",
                            color = if (outputCopied) Color(0xFF34D399) else Color(0xFF8B949E),
                            style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    val textToCopy = if (res.stdout.isNotBlank()) res.stdout else res.stderr
                                    clipboard.setText(AnnotatedString(textToCopy))
                                    outputCopied = true
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )

                        // Clear Output Button
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear output",
                            tint = Color(0xFF6B7280),
                            modifier = Modifier
                                .size(13.dp)
                                .clip(CircleShape)
                                .clickable {
                                    CodeExecutionManager.clearOutput(blockId)
                                }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                val outputText = when {
                    res.stdout.isNotBlank() -> res.stdout
                    res.stderr.isNotBlank() -> res.stderr
                    else -> "(No output produced)"
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = outputText,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = if (isSuccess) Color(0xFFE2E8F0) else Color(0xFFFCA5A5)
                        )
                    )
                }
            }
        }
    }
}

/**
 * Dedicated Prompt Block Component with Copy button
 */
@Composable
fun PromptCardBlock(
    promptText: String,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val bg = if (isDark) Color(0xFF191B28) else Color(0xFFF5F3FF)
    val border = if (isDark) Color(0xFF2A2C40) else Color(0xFFDDD6FE)
    val headerText = if (isDark) Color(0xFFA78BFA) else Color(0xFF6D28D9)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PROMPT",
                color = headerText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(promptText))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "Copy prompt",
                    tint = if (copied) Color(0xFF10B981) else headerText,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (copied) "Copied!" else "Copy",
                    color = if (copied) Color(0xFF10B981) else headerText,
                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
        ) {
            Text(
                text = promptText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Dedicated Structured Data Card Block for JSON, XML, YAML, SQL, Gradle
 */
@Composable
fun StructuredDataCardBlock(
    type: String,
    content: String,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val isDark = MaterialTheme.colorScheme.background.red < 0.2f
    val bg = if (isDark) Color(0xFF141520) else Color(0xFFF8FAFC)
    val border = if (isDark) Color(0xFF262838) else Color(0xFFE2E8F0)
    val headerBg = if (isDark) Color(0xFF1C1E2D) else Color(0xFFF1F5F9)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = type.uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(content))
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = if (copied) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (copied) "Copied!" else "Copy",
                    color = if (copied) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = content,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.5.sp,
                    color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                )
            )
        }
    }
}

/**
 * Markdown Table View Component
 */
@Composable
private fun TableBlockView(
    table: ResponseBlock.Table,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val tableBorder = if (isDark) Color(0xFF2E3044) else Color(0xFFE5E7EB)
    val headerBg = if (isDark) Color(0xFF212232) else Color(0xFFEDE9FE)
    val altRowBg = if (isDark) Color(0xFF171822) else Color(0xFFF9FAFB)
    val baseRowBg = if (isDark) Color(0xFF13141F) else Color(0xFFFFFFFF)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, tableBorder, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            // Header Row
            if (table.headers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .background(headerBg)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    table.headers.forEach { headerText ->
                        Box(
                            modifier = Modifier
                                .widthIn(min = 90.dp, max = 220.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = ResponseInlineParser.parse(headerText, isDark),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(tableBorder)
                )
            }

            // Body Rows
            table.rows.forEachIndexed { rowIdx, row ->
                val rowBg = if (rowIdx % 2 == 1) altRowBg else baseRowBg
                Row(
                    modifier = Modifier
                        .background(rowBg)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    row.forEach { cellText ->
                        Box(
                            modifier = Modifier
                                .widthIn(min = 90.dp, max = 220.dp)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = ResponseInlineParser.parse(cellText, isDark),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.5.sp,
                                    lineHeight = 18.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                if (rowIdx < table.rows.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(tableBorder.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

// ============================================================================
// STRUCTURED RESPONSE BLOCK MODELS & PARSER
// ============================================================================

sealed class ResponseBlock {
    data class Heading(val level: Int, val text: String) : ResponseBlock()
    data class Paragraph(val text: String) : ResponseBlock()
    data class BulletItem(val indentLevel: Int, val text: String) : ResponseBlock()
    data class NumberedItem(val indentLevel: Int, val number: Int, val text: String) : ResponseBlock()
    data class Quote(val text: String) : ResponseBlock()
    data class Code(val id: String, val language: String, val code: String) : ResponseBlock()
    data class Prompt(val promptText: String) : ResponseBlock()
    data class StructuredData(val type: String, val content: String) : ResponseBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ResponseBlock()
    object Divider : ResponseBlock()
}

object ResponseBlockParser {
    private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$")
    private val BULLET_REGEX = Regex("^(\\s*)[-*+]\\s+(.+)$")
    private val NUMBERED_REGEX = Regex("^(\\s*)(\\d+)[.)]\\s+(.+)$")
    private val TABLE_ROW_REGEX = Regex("^\\|(.+)\\|$")
    private val TABLE_DIVIDER_REGEX = Regex("^\\|[\\s\\-:\\|]+\\|$")

    fun parse(rawText: String, messageId: String): List<ResponseBlock> {
        if (rawText.isEmpty()) return emptyList()
        val result = ArrayList<ResponseBlock>(16)
        val lines = rawText.split("\n")
        var i = 0
        var blockCount = 0
        val paragraph = StringBuilder(128)

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                result.add(ResponseBlock.Paragraph(paragraph.toString().trim()))
            }
            paragraph.setLength(0)
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()

            // 1. Fenced Code Block: ```lang ... ```
            if (trimmed.startsWith("```")) {
                flushParagraph()
                val langTag = trimmed.removePrefix("```").trim().lowercase()
                val codeSb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeSb.append(lines[i]).append('\n')
                    i++
                }
                val rawContent = codeSb.toString().trimEnd('\n')
                val blockId = "${messageId}_code_${blockCount++}"

                when (langTag) {
                    "prompt" -> {
                        result.add(ResponseBlock.Prompt(rawContent))
                    }
                    "json", "xml", "yaml", "yml", "sql", "gradle", "properties", "toml" -> {
                        result.add(ResponseBlock.StructuredData(langTag, rawContent))
                    }
                    else -> {
                        result.add(ResponseBlock.Code(blockId, langTag, rawContent))
                    }
                }
                if (i < lines.size) i++
                continue
            }

            // 2. Horizontal Divider: ---, ***, ___
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                flushParagraph()
                result.add(ResponseBlock.Divider)
                i++
                continue
            }

            // 3. Heading: # to ######
            val headingMatch = HEADING_REGEX.matchEntire(trimmed)
            if (headingMatch != null) {
                flushParagraph()
                val level = headingMatch.groupValues[1].length
                val hText = headingMatch.groupValues[2]
                result.add(ResponseBlock.Heading(level, hText))
                i++
                continue
            }

            // 4. Blockquote: > quote
            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quoteLines = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    val ql = lines[i].trimStart().removePrefix(">").trim()
                    if (quoteLines.isNotEmpty()) quoteLines.append(" ")
                    quoteLines.append(ql)
                    i++
                }
                result.add(ResponseBlock.Quote(quoteLines.toString()))
                continue
            }

            // 5. Markdown Table: | Col1 | Col2 |
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && i + 1 < lines.size && TABLE_DIVIDER_REGEX.matches(lines[i + 1].trim())) {
                flushParagraph()
                val headers = trimmed.trim('|').split('|').map { it.trim() }
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                    val cells = lines[i].trim().trim('|').split('|').map { it.trim() }
                    rows.add(cells)
                    i++
                }
                result.add(ResponseBlock.Table(headers, rows))
                continue
            }

            // 6. Bullet List: - item, * item, + item
            val bulletMatch = BULLET_REGEX.matchEntire(line)
            if (bulletMatch != null) {
                flushParagraph()
                val spaces = bulletMatch.groupValues[1].length
                val indentLevel = (spaces / 2).coerceAtLeast(0)
                val itemText = bulletMatch.groupValues[2]
                result.add(ResponseBlock.BulletItem(indentLevel, itemText))
                i++
                continue
            }

            // 7. Numbered List: 1. item, 2. item
            val numberedMatch = NUMBERED_REGEX.matchEntire(line)
            if (numberedMatch != null) {
                flushParagraph()
                val spaces = numberedMatch.groupValues[1].length
                val indentLevel = (spaces / 2).coerceAtLeast(0)
                val num = numberedMatch.groupValues[2].toIntOrNull() ?: 1
                val itemText = numberedMatch.groupValues[3]
                result.add(ResponseBlock.NumberedItem(indentLevel, num, itemText))
                i++
                continue
            }

            // Blank line terminates active paragraph
            if (line.isBlank()) {
                flushParagraph()
                i++
                continue
            }

            // Normal text accumulation
            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(line.trim())
            i++
        }

        flushParagraph()
        return result
    }
}

/**
 * Parser for inline Markdown spans (bold, italic, inline code pill, links, strikethrough).
 */
object ResponseInlineParser {
    fun parse(text: String, isDark: Boolean): AnnotatedString {
        if (!text.contains('*') && !text.contains('`') && !text.contains('~') && !text.contains('_') && !text.contains('[')) {
            return AnnotatedString(text)
        }

        val codePillBg = if (isDark) Color(0xFF282442) else Color(0xFFEDE9FE)
        val codePillText = if (isDark) Color(0xFFA78BFA) else Color(0xFF6D28D9)
        val linkColor = if (isDark) Color(0xFF93C5FD) else Color(0xFF2563EB)

        return buildAnnotatedString {
            var i = 0
            val len = text.length

            while (i < len) {
                val ch = text[i]

                // Inline Code: `code`
                if (ch == '`') {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codePillBg,
                                color = codePillText,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        append(" ${text.substring(i + 1, end)} ")
                        pop()
                        i = end + 1
                        continue
                    }
                }

                // Bold-Italic: ***text*** or ___text___
                if ((ch == '*' || ch == '_') && i + 2 < len && text[i + 1] == ch && text[i + 2] == ch) {
                    val token = "$ch$ch$ch"
                    val end = text.indexOf(token, i + 3)
                    if (end > i + 2) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                        append(text.substring(i + 3, end))
                        pop()
                        i = end + 3
                        continue
                    }
                }

                // Bold: **text** or __text__
                if ((ch == '*' || ch == '_') && i + 1 < len && text[i + 1] == ch) {
                    val token = "$ch$ch"
                    val end = text.indexOf(token, i + 2)
                    if (end > i + 1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(text.substring(i + 2, end))
                        pop()
                        i = end + 2
                        continue
                    }
                }

                // Strikethrough: ~~text~~
                if (ch == '~' && i + 1 < len && text[i + 1] == '~') {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 1) {
                        pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        append(text.substring(i + 2, end))
                        pop()
                        i = end + 2
                        continue
                    }
                }

                // Italic: *text* or _text_
                if (ch == '*' || ch == '_') {
                    val end = text.indexOf(ch, i + 1)
                    if (end > i + 1 && (end + 1 >= len || text[end + 1] != ch)) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                        continue
                    }
                }

                // Hyperlink: [label](url)
                if (ch == '[') {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket > i && closeBracket + 1 < len && text[closeBracket + 1] == '(') {
                        val urlEnd = text.indexOf(')', closeBracket + 2)
                        if (urlEnd > closeBracket + 1) {
                            val label = text.substring(i + 1, closeBracket)
                            val url = text.substring(closeBracket + 2, urlEnd)
                            pushStringAnnotation(tag = "URL", annotation = url)
                            pushStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                            append(label)
                            pop()
                            pop()
                            i = urlEnd + 1
                            continue
                        }
                    }
                }

                append(ch)
                i++
            }
        }
    }
}

// ============================================================================
// LIGHTWEIGHT ZERO-CRASH SYNTAX HIGHLIGHTER
// ============================================================================

object SyntaxHighlighter {
    private val KEYWORDS = setOf(
        "fun", "val", "var", "class", "interface", "object", "sealed", "data", "enum",
        "override", "private", "public", "protected", "internal", "import", "package",
        "return", "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
        "throw", "suspend", "inline", "crossinline", "noinline", "reified", "companion",
        "def", "async", "await", "from", "as", "with", "lambda", "yield", "pass", "elif",
        "function", "const", "let", "export", "default", "extends", "implements",
        "select", "where", "insert", "into", "values", "update", "delete", "create", "table"
    )

    private val LITERALS = setOf(
        "true", "false", "null", "nil", "undefined", "None", "True", "False"
    )

    private val TYPES = setOf(
        "String", "Int", "Long", "Float", "Double", "Boolean", "List", "Map", "Set",
        "Unit", "Any", "Nothing", "Array", "Byte", "Short", "Char"
    )

    fun highlight(code: String, lang: String): AnnotatedString {
        return buildAnnotatedString {
            val lines = code.split("\n")
            lines.forEachIndexed { lineIdx, line ->
                if (lineIdx > 0) append("\n")
                var i = 0
                val len = line.length

                while (i < len) {
                    val ch = line[i]

                    // Single-line Comments (// or #)
                    if ((ch == '/' && i + 1 < len && line[i + 1] == '/') || (ch == '#' && lang.lowercase() in listOf("py", "python", "sh", "bash", "yml", "yaml"))) {
                        pushStyle(SpanStyle(color = Color(0xFF7E849E), fontStyle = FontStyle.Italic))
                        append(line.substring(i))
                        pop()
                        break
                    }

                    // Double/Single quoted strings
                    if (ch == '"' || ch == '\'' || ch == '`') {
                        val quoteChar = ch
                        var end = i + 1
                        while (end < len && line[end] != quoteChar) {
                            if (line[end] == '\\' && end + 1 < len) end++
                            end++
                        }
                        val strLen = if (end < len) end + 1 else len
                        pushStyle(SpanStyle(color = Color(0xFF86EFAC))) // Mint green
                        append(line.substring(i, strLen))
                        pop()
                        i = strLen
                        continue
                    }

                    // Numbers
                    if (ch.isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit() && line[i - 1] != '_')) {
                        var end = i + 1
                        while (end < len && (line[end].isLetterOrDigit() || line[end] == '.')) {
                            end++
                        }
                        pushStyle(SpanStyle(color = Color(0xFFFDBA74))) // Amber orange
                        append(line.substring(i, end))
                        pop()
                        i = end
                        continue
                    }

                    // Identifiers / Keywords / Types
                    if (ch.isLetter() || ch == '_') {
                        var end = i + 1
                        while (end < len && (line[end].isLetterOrDigit() || line[end] == '_')) {
                            end++
                        }
                        val word = line.substring(i, end)
                        when {
                            KEYWORDS.contains(word) -> {
                                pushStyle(SpanStyle(color = Color(0xFFC792EA), fontWeight = FontWeight.SemiBold)) // Purple
                                append(word)
                                pop()
                            }
                            LITERALS.contains(word) -> {
                                pushStyle(SpanStyle(color = Color(0xFF67E8F9), fontWeight = FontWeight.Medium)) // Cyan
                                append(word)
                                pop()
                            }
                            TYPES.contains(word) -> {
                                pushStyle(SpanStyle(color = Color(0xFFFDE047), fontWeight = FontWeight.Medium)) // Yellow
                                append(word)
                                pop()
                            }
                            else -> {
                                pushStyle(SpanStyle(color = Color(0xFFF3F4F6))) // Clean white
                                append(word)
                                pop()
                            }
                        }
                        i = end
                        continue
                    }

                    // Punctuation & Operators
                    pushStyle(SpanStyle(color = Color(0xFFCBD5E1)))
                    append(ch)
                    pop()
                    i++
                }
            }
        }
    }
}

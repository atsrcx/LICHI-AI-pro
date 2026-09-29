package com.lichiai.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lichiai.browser.storage.BrowserBookmark
import com.lichiai.browser.storage.BrowserHistoryItem
import com.lichiai.browser.tabs.BrowserTab

enum class MenuSubView {
    MAIN,
    BOOKMARKS,
    HISTORY,
    DOWNLOADS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserMenuSheet(
    activeTab: BrowserTab?,
    bookmarks: List<BrowserBookmark>,
    history: List<BrowserHistoryItem>,
    onToggleDesktop: () -> Unit,
    onAddBookmark: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onClearHistory: () -> Unit,
    onClearCache: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenInspection: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var currentSubView by remember { mutableStateOf(MenuSubView.MAIN) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when (currentSubView) {
                MenuSubView.MAIN -> {
                    Text(
                        text = "Browser Options",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Desktop site toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleDesktop() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Desktop Site", modifier = Modifier.weight(1f))
                        Switch(
                            checked = activeTab?.isDesktopMode == true,
                            onCheckedChange = { onToggleDesktop() }
                        )
                    }

                    // Bookmark current page
                    BrowserMenuItem(
                        icon = Icons.Default.BookmarkBorder,
                        title = "Bookmark this page",
                        onClick = {
                            onAddBookmark()
                            onDismiss()
                        }
                    )

                    // View Bookmarks
                    BrowserMenuItem(
                        icon = Icons.Default.Bookmark,
                        title = "Bookmarks (${bookmarks.size})",
                        onClick = { currentSubView = MenuSubView.BOOKMARKS }
                    )

                    // View History
                    BrowserMenuItem(
                        icon = Icons.Default.History,
                        title = "History (${history.size})",
                        onClick = { currentSubView = MenuSubView.HISTORY }
                    )

                    // Clear Cache
                    BrowserMenuItem(
                        icon = Icons.Default.Delete,
                        title = "Clear Cache & Cookies",
                        onClick = {
                            onClearCache()
                            onDismiss()
                        }
                    )

                    // Inspect & DevTools Intelligence
                    BrowserMenuItem(
                        icon = Icons.Default.BugReport,
                        title = "Inspect & DevTools Intelligence",
                        onClick = {
                            onOpenInspection()
                            onDismiss()
                        }
                    )

                    Divider(modifier = Modifier.padding(vertical = 8.dp))

                    // Browser LLM & Agent Settings
                    BrowserMenuItem(
                        icon = Icons.Default.Settings,
                        title = "Browser & LLM Settings",
                        onClick = {
                            onOpenSettings()
                            onDismiss()
                        }
                    )
                }

                MenuSubView.BOOKMARKS -> {
                    Text(
                        text = "Saved Bookmarks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (bookmarks.isEmpty()) {
                        Text("No bookmarks saved yet.", modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(bookmarks, key = { it.id }) { bm ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onOpenUrl(bm.url)
                                            onDismiss()
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(bm.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                        Text(bm.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                MenuSubView.HISTORY -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Browsing History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (history.isNotEmpty()) {
                            Text(
                                text = "Clear All",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable { onClearHistory() }
                            )
                        }
                    }
                    if (history.isEmpty()) {
                        Text("History is empty.", modifier = Modifier.padding(vertical = 16.dp))
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(history, key = { it.id }) { h ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onOpenUrl(h.url)
                                            onDismiss()
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                        Text(h.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {}
            }
        }
    }
}

@Composable
fun BrowserMenuItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

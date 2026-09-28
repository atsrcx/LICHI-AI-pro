package com.lichiai.browser.downloads

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class DownloadStatus {
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class BrowserDownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val systemDownloadId: Long? = null,
    val fileName: String,
    val url: String,
    val mimeType: String,
    val contentLength: Long,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Isolated download manager for Lichi Browser.
 */
class BrowserDownloadManager(private val context: Context) {

    private val _downloads = MutableStateFlow<List<BrowserDownloadItem>>(emptyList())
    val downloads: StateFlow<List<BrowserDownloadItem>> = _downloads.asStateFlow()

    private val systemDownloadManager by lazy {
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    }

    fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ): BrowserDownloadItem? {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri).apply {
                setTitle(fileName)
                setDescription("Downloading via Lichi Browser")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                userAgent?.let { addRequestHeader("User-Agent", it) }
            }

            val downloadId = systemDownloadManager?.enqueue(request)
            val item = BrowserDownloadItem(
                systemDownloadId = downloadId,
                fileName = fileName,
                url = url,
                mimeType = mimeType ?: "application/octet-stream",
                contentLength = contentLength,
                status = DownloadStatus.DOWNLOADING
            )

            _downloads.update { listOf(item) + it }
            return item
        } catch (_: Exception) {
            val failedItem = BrowserDownloadItem(
                fileName = URLUtil.guessFileName(url, contentDisposition, mimeType),
                url = url,
                mimeType = mimeType ?: "",
                contentLength = contentLength,
                status = DownloadStatus.FAILED
            )
            _downloads.update { listOf(failedItem) + it }
            return failedItem
        }
    }

    fun clearDownloads() {
        _downloads.value = emptyList()
    }
}

package com.lichiai.browser.inspection.download

import android.webkit.URLUtil
import com.lichiai.browser.engine.ChromiumWebViewEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class DownloadCandidate(
    val url: String,
    val filename: String,
    val mimeType: String = "",
    val contentLength: Long = 0L,
    val isDirectLink: Boolean = true,
    val extension: String = "",
    val detectedVia: String = "DownloadListener",
    val sourceContext: String = ""
)

class DownloadObserver {

    private val _downloads = MutableStateFlow<List<DownloadCandidate>>(emptyList())
    val downloads: StateFlow<List<DownloadCandidate>> = _downloads.asStateFlow()

    private val activeCandidates = ConcurrentHashMap<String, DownloadCandidate>()

    companion object {
        val EXTRACT_DOWNLOADS_JS = """
            (function() {
                try {
                    var anchors = document.querySelectorAll('a[href]');
                    var list = [];
                    var exts = ['.apk', '.pdf', '.zip', '.rar', '.7z', '.tar', '.gz', '.dmg', '.exe', 
                                '.iso', '.msi', '.deb', '.rpm', '.csv', '.xlsx', '.docx', '.mp3', '.mp4', '.mkv'];

                    for (var i = 0; i < anchors.length; i++) {
                        var a = anchors[i];
                        var href = a.href || '';
                        var hasDownloadAttr = a.hasAttribute('download');
                        var text = (a.innerText || a.textContent || '').trim();
                        var lower = href.toLowerCase();

                        var matchedExt = '';
                        for (var e = 0; e < exts.length; e++) {
                            if (lower.indexOf(exts[e]) !== -1) {
                                matchedExt = exts[e];
                                break;
                            }
                        }

                        if (hasDownloadAttr || matchedExt || lower.includes('/download') || lower.includes('download=true')) {
                            var filename = a.getAttribute('download') || '';
                            if (!filename && matchedExt) {
                                try {
                                    var path = new URL(href).pathname;
                                    filename = path.substring(path.lastIndexOf('/') + 1);
                                } catch(err) {}
                            }
                            if (!filename) filename = text || 'download';

                            list.push({
                                url: href,
                                filename: filename,
                                text: text,
                                hasDownloadAttr: hasDownloadAttr,
                                matchedExt: matchedExt
                            });
                        }
                    }
                    return JSON.stringify(list);
                } catch(e) {
                    return "[]";
                }
            })();
        """.trimIndent()
    }

    fun recordDownloadFromListener(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val ext = filename.substringAfterLast('.', "")

        val candidate = DownloadCandidate(
            url = url,
            filename = filename,
            mimeType = mimeType ?: "",
            contentLength = contentLength,
            isDirectLink = true,
            extension = ext,
            detectedVia = "DownloadListener",
            sourceContext = "System Download Listener"
        )
        activeCandidates[url] = candidate
        updateState()
    }

    suspend fun discoverDomDownloads(engine: ChromiumWebViewEngine?) = withContext(Dispatchers.Default) {
        if (engine == null) return@withContext
        val rawJson = engine.evaluateJavascriptAsync(EXTRACT_DOWNLOADS_JS)
        try {
            val clean = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                rawJson.substring(1, rawJson.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else rawJson
            val array = JSONArray(clean)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val url = obj.optString("url", "")
                if (url.isNotBlank() && !activeCandidates.containsKey(url)) {
                    val fn = obj.optString("filename", "download")
                    val ext = obj.optString("matchedExt", fn.substringAfterLast('.', ""))
                    val text = obj.optString("text", "")
                    activeCandidates[url] = DownloadCandidate(
                        url = url,
                        filename = fn,
                        isDirectLink = true,
                        extension = ext.removePrefix("."),
                        detectedVia = "DOMAnchor",
                        sourceContext = text
                    )
                }
            }
            updateState()
        } catch (_: Exception) {}
    }

    fun clear() {
        activeCandidates.clear()
        _downloads.value = emptyList()
    }

    private fun updateState() {
        _downloads.value = activeCandidates.values.toList()
    }
}

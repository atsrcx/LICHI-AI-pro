package com.lichiai.browser.inspection

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.lichiai.browser.engine.ChromiumWebViewEngine
import com.lichiai.browser.inspection.download.DownloadObserver
import com.lichiai.browser.inspection.network.NetworkObserver
import com.lichiai.browser.inspection.runtime.BrowserInspectionJsBridge
import com.lichiai.browser.inspection.runtime.ConsoleInspector
import com.lichiai.browser.inspection.serviceworker.ServiceWorkerNetworkObserver

/**
 * Connects the Android WebView runtime to all inspection observers.
 * Production-safe hybrid instrumentation without undocumented APIs or simulated data.
 */
class BrowserInstrumentationHub(
    val networkObserver: NetworkObserver = NetworkObserver(),
    val consoleInspector: ConsoleInspector = ConsoleInspector(),
    val downloadObserver: DownloadObserver = DownloadObserver()
) {
    val serviceWorkerObserver = ServiceWorkerNetworkObserver(networkObserver)
    private val jsBridge = BrowserInspectionJsBridge(networkObserver, consoleInspector)

    init {
        serviceWorkerObserver.initialize()
    }

    @SuppressLint("JavascriptInterface")
    fun attachToEngine(engine: ChromiumWebViewEngine) {
        val wv = engine.webView

        // 1. Add Javascript Interface for real-time telemetry bridge
        try {
            wv.addJavascriptInterface(jsBridge, BrowserInspectionJsBridge.INTERFACE_NAME)
        } catch (_: Exception) {}

        // 2. AndroidX Document Start Script injection if supported
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    wv,
                    BrowserInspectionJsBridge.INSTRUMENTATION_SCRIPT,
                    setOf("*")
                )
            }
        } catch (_: Exception) {}
    }

    fun onPageStarted(url: String) {
        networkObserver.recordRequest(
            url = url,
            method = "GET",
            headers = emptyMap(),
            isForMainFrame = true,
            hasUserGesture = false,
            pageUrl = url
        )
    }

    fun onPageFinished(engine: ChromiumWebViewEngine, url: String) {
        // Fallback injection if document-start script is not active or for SPA navigation
        engine.evaluateJavascript(BrowserInspectionJsBridge.INSTRUMENTATION_SCRIPT, null)
    }

    fun onInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (request == null) return null
        try {
            val url = request.url.toString()
            val method = request.method
            val headers = request.requestHeaders ?: emptyMap()
            val pageUrl = view?.url

            val reqId = networkObserver.recordRequest(
                url = url,
                method = method,
                headers = headers,
                isForMainFrame = request.isForMainFrame,
                hasUserGesture = request.hasGesture(),
                pageUrl = pageUrl
            )
        } catch (_: Exception) {}
        return null // Return null so WebView continues standard HTTP fetching
    }

    fun onConsoleMessage(consoleMessage: ConsoleMessage) {
        consoleInspector.recordConsoleMessage(consoleMessage)
    }

    fun onDownloadTriggered(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        downloadObserver.recordDownloadFromListener(
            url = url,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            contentLength = contentLength
        )
    }

    fun clearAll() {
        networkObserver.clear()
        consoleInspector.clear()
        downloadObserver.clear()
    }
}

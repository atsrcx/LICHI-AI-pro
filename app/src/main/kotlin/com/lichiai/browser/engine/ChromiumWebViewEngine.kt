package com.lichiai.browser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lichiai.browser.api.ScrollDirection
import com.lichiai.browser.context.BrowserPageCandidate
import com.lichiai.browser.events.BrowserEvent
import com.lichiai.browser.events.BrowserEventBus
import com.lichiai.browser.inspection.BrowserInstrumentationHub
import com.lichiai.browser.permissions.BrowserPermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * Robust, production-grade Android WebView implementation of BrowserEngine.
 * Handles DOM storage, cookies, downloads, permissions, and JavaScript evaluation.
 */
@SuppressLint("SetJavaScriptEnabled")
class ChromiumWebViewEngine(
    val context: Context,
    private val eventBus: BrowserEventBus,
    private val permissionManager: BrowserPermissionManager,
    val isIncognito: Boolean = false,
    val instrumentationHub: BrowserInstrumentationHub? = null
) : BrowserEngine {

    val webView: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = if (isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT

        val cookieManager = CookieManager.getInstance()
        if (isIncognito) {
            cookieManager.setAcceptCookie(false)
        } else {
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                eventBus.emit(BrowserEvent.PageLoadProgress(newProgress))
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                val currentUrl = view?.url ?: ""
                eventBus.emit(BrowserEvent.NavigationCompleted(currentUrl, title ?: ""))
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let { permissionManager.handlePermissionRequest(it) }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin != null && callback != null) {
                    permissionManager.handleGeolocationPrompt(origin, callback)
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    instrumentationHub?.onConsoleMessage(consoleMessage)
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                instrumentationHub?.onInterceptRequest(view, request)
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:blank")) {
                    return false
                }
                return try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                val targetUrl = url ?: view?.url ?: ""
                if (targetUrl.isNotBlank()) {
                    instrumentationHub?.onPageStarted(targetUrl)
                    eventBus.emit(BrowserEvent.NavigationStarted(targetUrl))
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val u = url ?: view?.url ?: ""
                val t = view?.title ?: ""
                instrumentationHub?.onPageFinished(this@ChromiumWebViewEngine, u)
                eventBus.emit(BrowserEvent.PageLoaded(u, t))
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    eventBus.emit(BrowserEvent.BrowserError("Failed to load: ${error?.description}"))
                }
            }
        }
    }

    init {
        instrumentationHub?.attachToEngine(this)
    }

    override fun loadUrl(url: String) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            webView.loadUrl(url)
        } else {
            webView.post { webView.loadUrl(url) }
        }
    }

    override fun goBack(): Boolean {
        return if (webView.canGoBack()) {
            webView.post { webView.goBack() }
            true
        } else false
    }

    override fun goForward(): Boolean {
        return if (webView.canGoForward()) {
            webView.post { webView.goForward() }
            true
        } else false
    }

    override fun reload() {
        webView.post { webView.reload() }
    }

    override fun stopLoading() {
        webView.post { webView.stopLoading() }
    }

    override fun canGoBack(): Boolean = webView.canGoBack()

    override fun canGoForward(): Boolean = webView.canGoForward()

    override fun getUrl(): String {
        return if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try { webView.url ?: "about:blank" } catch (_: Exception) { "about:blank" }
        } else {
            try {
                var res = "about:blank"
                val latch = java.util.concurrent.CountDownLatch(1)
                webView.post {
                    try { res = webView.url ?: "about:blank" } catch (_: Exception) {}
                    latch.countDown()
                }
                latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                res
            } catch (_: Exception) {
                "about:blank"
            }
        }
    }

    override fun getTitle(): String {
        return if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try { webView.title ?: "" } catch (_: Exception) { "" }
        } else {
            try {
                var res = ""
                val latch = java.util.concurrent.CountDownLatch(1)
                webView.post {
                    try { res = webView.title ?: "" } catch (_: Exception) {}
                    latch.countDown()
                }
                latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                res
            } catch (_: Exception) {
                ""
            }
        }
    }

    override fun evaluateJavascript(script: String, callback: ((String) -> Unit)?) {
        webView.post {
            webView.evaluateJavascript(script, callback?.let { ValueCallback(it) })
        }
    }

    override suspend fun evaluateJavascriptAsync(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    continuation.resume(result ?: "")
                }
            }
        }
    }

    override suspend fun extractCandidateLinks(): List<BrowserPageCandidate> = withContext(Dispatchers.Default) {
        val rawJson = evaluateJavascriptAsync(BrowserJsBridge.EXTRACT_LINKS_JS)
        parseCandidateLinks(rawJson)
    }

    override suspend fun extractInteractiveElements(): List<com.lichiai.browser.context.BrowserInteractiveElement> = withContext(Dispatchers.Default) {
        val rawJson = evaluateJavascriptAsync(BrowserJsBridge.EXTRACT_INTERACTIVE_ELEMENTS_JS)
        parseInteractiveElements(rawJson)
    }

    override suspend fun extractTextSnippet(maxLength: Int): String = withContext(Dispatchers.Default) {
        val raw = evaluateJavascriptAsync(BrowserJsBridge.EXTRACT_TEXT_SNIPPET_JS)
        // Cleanup JSON string escaping if returned as quote-enclosed
        val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
            raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\n", "\n")
        } else raw
        unquoted.take(maxLength)
    }

    override suspend fun extractPrices(): List<String> = withContext(Dispatchers.Default) {
        val raw = evaluateJavascriptAsync(BrowserJsBridge.EXTRACT_PRICES_JS)
        try {
            val clean = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                raw.substring(1, raw.length - 1).replace("\\\"", "\"")
            } else raw
            val array = JSONArray(clean)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun extractTables(): List<com.lichiai.browser.context.BrowserTableData> = withContext(Dispatchers.Default) {
        val raw = evaluateJavascriptAsync(BrowserJsBridge.EXTRACT_TABLES_JS)
        try {
            val clean = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else raw
            val array = JSONArray(clean)
            val list = mutableListOf<com.lichiai.browser.context.BrowserTableData>()
            for (i in 0 until array.length()) {
                val tableObj = array.getJSONObject(i)
                val title = tableObj.optString("title", "Table ${i + 1}")
                val headersArr = tableObj.optJSONArray("headers") ?: JSONArray()
                val headers = mutableListOf<String>()
                for (h in 0 until headersArr.length()) {
                    headers.add(headersArr.getString(h))
                }
                val rowsArr = tableObj.optJSONArray("rows") ?: JSONArray()
                val rows = mutableListOf<List<String>>()
                for (r in 0 until rowsArr.length()) {
                    val rowArr = rowsArr.getJSONArray(r)
                    val rowData = mutableListOf<String>()
                    for (c in 0 until rowArr.length()) {
                        rowData.add(rowArr.getString(c))
                    }
                    rows.add(rowData)
                }
                list.add(com.lichiai.browser.context.BrowserTableData(title, headers, rows))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun highlightElement(index: Int): Boolean = withContext(Dispatchers.Main) {
        val script = BrowserJsBridge.buildHighlightElementJs(index)
        val res = evaluateJavascriptAsync(script)
        res.contains("HIGHLIGHTED")
    }

    override suspend fun getPageState(): com.lichiai.browser.context.BrowserPageState = withContext(Dispatchers.Default) {
        val raw = evaluateJavascriptAsync(BrowserJsBridge.GET_PAGE_STATE_JS)
        try {
            val clean = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else raw
            val json = org.json.JSONObject(clean)
            com.lichiai.browser.context.BrowserPageState(
                readyState = json.optString("readyState", "complete"),
                scrollY = json.optInt("scrollY", 0),
                maxScrollY = json.optInt("maxScrollY", 0),
                textLength = json.optInt("textLength", 0),
                interactiveCount = json.optInt("interactiveCount", 0),
                isLoaded = json.optBoolean("isLoaded", true)
            )
        } catch (_: Exception) {
            com.lichiai.browser.context.BrowserPageState()
        }
    }

    override suspend fun clickCandidateByIndex(index: Int, targetUrl: String?): Boolean = withContext(Dispatchers.Main) {
        val script = BrowserJsBridge.buildClickCandidateJs(index, targetUrl)
        val res = evaluateJavascriptAsync(script)
        res.contains("CLICKED")
    }

    override suspend fun clickElementByIndex(index: Int): Boolean = withContext(Dispatchers.Main) {
        val script = BrowserJsBridge.buildClickElementJs(index)
        val res = evaluateJavascriptAsync(script)
        res.contains("CLICKED")
    }

    override suspend fun clickCandidateByText(text: String): Boolean = withContext(Dispatchers.Main) {
        val script = BrowserJsBridge.buildClickTextJs(text)
        val res = evaluateJavascriptAsync(script)
        res.contains("CLICKED")
    }

    override suspend fun typeText(index: Int?, selector: String?, text: String, submit: Boolean): Boolean = withContext(Dispatchers.Main) {
        val script = BrowserJsBridge.buildTypeTextJs(index, selector, text, submit)
        val res = evaluateJavascriptAsync(script)
        res.contains("TYPED")
    }

    override suspend fun scroll(direction: ScrollDirection, amount: Int): Boolean = withContext(Dispatchers.Main) {
        val script = BrowserJsBridge.buildScrollJs(direction.name, amount)
        evaluateJavascriptAsync(script)
        true
    }

    override suspend fun captureScreenshot(): Bitmap? = withContext(Dispatchers.Main) {
        try {
            val width = webView.width
            val height = webView.height
            if (width <= 0 || height <= 0) return@withContext null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            webView.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    override fun setDesktopMode(enabled: Boolean) {
        webView.post {
            val desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            val defaultUa = WebSettings.getDefaultUserAgent(context)
            webView.settings.userAgentString = if (enabled) desktopUa else defaultUa
            webView.settings.useWideViewPort = enabled
            webView.reload()
        }
    }

    override fun clearCacheAndCookies() {
        webView.post {
            webView.clearCache(true)
            webView.clearHistory()
            if (isIncognito) {
                CookieManager.getInstance().removeAllCookies(null)
            }
        }
    }

    override fun destroy() {
        webView.post {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private fun parseCandidateLinks(rawJson: String): List<BrowserPageCandidate> {
        return try {
            val clean = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                rawJson.substring(1, rawJson.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else rawJson
            val array = JSONArray(clean)
            val results = mutableListOf<BrowserPageCandidate>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                results.add(
                    BrowserPageCandidate(
                        index = obj.optInt("index", i + 1),
                        title = obj.optString("title", "Link ${i + 1}"),
                        url = obj.optString("url", ""),
                        snippet = obj.optString("snippet", ""),
                        isOfficial = obj.optBoolean("isOfficial", false),
                        isDownload = obj.optBoolean("isDownload", false),
                        isPrice = obj.optBoolean("isPrice", false)
                    )
                )
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseInteractiveElements(rawJson: String): List<com.lichiai.browser.context.BrowserInteractiveElement> {
        return try {
            val clean = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                rawJson.substring(1, rawJson.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else rawJson
            val array = JSONArray(clean)
            val results = mutableListOf<com.lichiai.browser.context.BrowserInteractiveElement>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                results.add(
                    com.lichiai.browser.context.BrowserInteractiveElement(
                        index = obj.optInt("index", i + 1),
                        tag = obj.optString("tag", ""),
                        type = obj.optString("type", ""),
                        text = obj.optString("text", ""),
                        placeholder = obj.optString("placeholder", ""),
                        href = obj.optString("href", ""),
                        name = obj.optString("name", ""),
                        id = obj.optString("id", ""),
                        ariaLabel = obj.optString("ariaLabel", ""),
                        isVisible = obj.optBoolean("isVisible", true),
                        isClickable = obj.optBoolean("isClickable", true),
                        isInput = obj.optBoolean("isInput", false),
                        value = obj.optString("value", ""),
                        bounds = obj.optString("bounds", "")
                    )
                )
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }
}


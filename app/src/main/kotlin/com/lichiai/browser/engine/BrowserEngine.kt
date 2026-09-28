package com.lichiai.browser.engine

import android.graphics.Bitmap
import com.lichiai.browser.api.ScrollDirection
import com.lichiai.browser.context.BrowserInteractiveElement
import com.lichiai.browser.context.BrowserPageCandidate
import com.lichiai.browser.context.BrowserPageState

/**
 * High-level Browser Engine abstraction.
 * Allows switching or enhancing the underlying rendering engine (e.g. GeckoView, Chromium, WebView)
 * without rewriting the Browser Agent or UI layer.
 */
interface BrowserEngine {
    fun loadUrl(url: String)
    fun goBack(): Boolean
    fun goForward(): Boolean
    fun reload()
    fun stopLoading()
    fun canGoBack(): Boolean
    fun canGoForward(): Boolean
    fun getUrl(): String
    fun getTitle(): String
    fun evaluateJavascript(script: String, callback: ((String) -> Unit)? = null)
    suspend fun evaluateJavascriptAsync(script: String): String
    suspend fun extractCandidateLinks(): List<BrowserPageCandidate>
    suspend fun extractInteractiveElements(): List<BrowserInteractiveElement>
    suspend fun extractTextSnippet(maxLength: Int = 1200): String
    suspend fun extractPrices(): List<String>
    suspend fun extractTables(): List<com.lichiai.browser.context.BrowserTableData>
    suspend fun highlightElement(index: Int): Boolean
    suspend fun getPageState(): BrowserPageState
    suspend fun clickCandidateByIndex(index: Int, targetUrl: String? = null): Boolean
    suspend fun clickElementByIndex(index: Int): Boolean
    suspend fun clickCandidateByText(text: String): Boolean
    suspend fun typeText(index: Int?, selector: String?, text: String, submit: Boolean = false): Boolean
    suspend fun scroll(direction: ScrollDirection, amount: Int = 1): Boolean
    suspend fun captureScreenshot(): Bitmap?
    fun setDesktopMode(enabled: Boolean)
    fun clearCacheAndCookies()
    fun destroy()
}


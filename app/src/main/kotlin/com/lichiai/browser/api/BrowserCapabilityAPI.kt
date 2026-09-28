package com.lichiai.browser.api

import android.graphics.Bitmap
import com.lichiai.browser.context.BrowserInteractiveElement
import com.lichiai.browser.context.BrowserTaskContext

/**
 * Strict boundary API through which the Browser Agent interacts with the browser.
 * Absolutely no device shell, arbitrary system intents, or external app access.
 */
interface BrowserCapabilityAPI {
    suspend fun openBrowser(): Boolean
    suspend fun navigate(url: String): Boolean
    suspend fun search(query: String, engine: String? = null): Boolean
    suspend fun clickCandidate(index: Int, targetUrl: String? = null): Boolean
    suspend fun clickElement(index: Int): Boolean
    suspend fun clickSelector(cssSelector: String): Boolean
    suspend fun typeText(selector: String, text: String): Boolean
    suspend fun typeText(index: Int?, selector: String?, text: String, submit: Boolean = false): Boolean
    suspend fun scroll(direction: ScrollDirection, amount: Int = 1): Boolean
    suspend fun goBack(): Boolean
    suspend fun goForward(): Boolean
    suspend fun reload(): Boolean
    suspend fun openTab(url: String? = null, isIncognito: Boolean = false): String
    suspend fun closeTab(tabId: String): Boolean
    suspend fun switchTab(tabId: String): Boolean
    suspend fun getPageContext(): BrowserTaskContext
    suspend fun getInteractiveElements(): List<BrowserInteractiveElement>
    suspend fun extractPageSummary(): String
    suspend fun extractPrices(): List<String>
    suspend fun extractTables(): List<com.lichiai.browser.context.BrowserTableData>
    suspend fun highlightElement(index: Int): Boolean
    suspend fun captureScreenshot(): Bitmap?
    suspend fun stopTask(): Boolean
}


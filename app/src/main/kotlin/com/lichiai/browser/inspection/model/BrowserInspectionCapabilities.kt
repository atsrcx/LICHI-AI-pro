package com.lichiai.browser.inspection.model

/**
 * Capability Matrix documenting what Android WebView & AndroidX WebKit
 * genuinely support in-process vs where fallback/hybrid instrumentation is used.
 */
data class BrowserInspectionCapabilityItem(
    val capability: String,
    val isSupported: Boolean,
    val mechanism: String,
    val notes: String
)

object BrowserInspectionCapabilities {
    val CAPABILITY_MATRIX = listOf(
        BrowserInspectionCapabilityItem(
            capability = "DOM Tree & Element Inspection",
            isSupported = true,
            mechanism = "WebView evaluateJavascript + DOM walker",
            notes = "Full access to elements, attributes, forms, links, scripts, shadow DOM, iframes"
        ),
        BrowserInspectionCapabilityItem(
            capability = "Network Request Interception & Metadata",
            isSupported = true,
            mechanism = "WebViewClient.shouldInterceptRequest + JS Fetch/XHR Proxy",
            notes = "Captures URL, HTTP method, headers, initiator, timestamp, status code, latency"
        ),
        BrowserInspectionCapabilityItem(
            capability = "XHR/Fetch Body & Response Access",
            isSupported = true,
            mechanism = "Client-side JS instrumentation via document-start proxy",
            notes = "Inspects request payloads, response bodies, and JSON API schemas"
        ),
        BrowserInspectionCapabilityItem(
            capability = "WebSocket Observation",
            isSupported = true,
            mechanism = "JS WebSocket Prototype Wrapper",
            notes = "Logs WS endpoint connections, protocols, and message frame events"
        ),
        BrowserInspectionCapabilityItem(
            capability = "Service Worker Requests",
            isSupported = true,
            mechanism = "ServiceWorkerControllerCompat",
            notes = "Observes worker network fetches when supported by WebKit implementation"
        ),
        BrowserInspectionCapabilityItem(
            capability = "Download Link & File Detection",
            isSupported = true,
            mechanism = "DownloadListener + DOM Anchor Analyzer",
            notes = "Detects direct file downloads, headers, content disposition, and file sizes"
        ),
        BrowserInspectionCapabilityItem(
            capability = "Console & Runtime Diagnostics",
            isSupported = true,
            mechanism = "WebChromeClient.onConsoleMessage + window.onerror hooks",
            notes = "Captures logs, warnings, errors, line numbers, and script source files"
        ),
        BrowserInspectionCapabilityItem(
            capability = "W3C Navigation & Resource Performance",
            isSupported = true,
            mechanism = "window.performance API query",
            notes = "Extracts DNS, TCP, TTFB, DOMContentLoaded, Paint timings, and transfer sizes"
        ),
        BrowserInspectionCapabilityItem(
            capability = "Security & Mixed Content Signals",
            isSupported = true,
            mechanism = "SSL Certificate check + Mixed content auditor + Cookie manager",
            notes = "Audits TLS encryption, cookie flags, third-party trackers, and sensitive keys"
        ),
        BrowserInspectionCapabilityItem(
            capability = "In-Process Chrome DevTools Protocol (CDP) WebSocket",
            isSupported = false,
            mechanism = "Reported as UNAVAILABLE (Production Sandboxed Android WebView)",
            notes = "Android does not provide a public in-app CDP WebSocket server; Hybrid Engine handles 100% of capabilities safely"
        )
    )
}

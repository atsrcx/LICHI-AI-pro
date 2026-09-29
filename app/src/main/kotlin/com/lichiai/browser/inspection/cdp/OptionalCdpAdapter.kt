package com.lichiai.browser.inspection.cdp

import android.webkit.WebView
import com.lichiai.browser.inspection.model.BrowserInspectionCapabilities

data class CdpTransportStatus(
    val isAvailable: Boolean,
    val transportType: String,
    val endpointUrl: String?,
    val diagnosticReason: String,
    val hybridFallbackActive: Boolean = true
)

object OptionalCdpAdapter {

    fun probeCdpTransport(): CdpTransportStatus {
        // Safe check without undocumented reflection or fake sockets
        return CdpTransportStatus(
            isAvailable = false,
            transportType = "HYBRID_WEBVIEW_INSTRUMENTATION",
            endpointUrl = null,
            diagnosticReason = "Production Android WebView security model isolates Chrome DevTools Protocol to adb debugging tunnels. Hybrid AndroidX WebKit + JS Instrumentation layer provides full production DevTools intelligence without external dependencies.",
            hybridFallbackActive = true
        )
    }

    fun enableWebViewDebuggingIfPossible() {
        try {
            WebView.setWebContentsDebuggingEnabled(true)
        } catch (_: Exception) {}
    }

    fun getCapabilityExplanation(): String {
        val sb = StringBuilder("Hybrid DevTools Intelligence Matrix:\n")
        BrowserInspectionCapabilities.CAPABILITY_MATRIX.forEach { item ->
            sb.append("• ${item.capability}: [${if (item.isSupported) "SUPPORTED" else "UNAVAILABLE"}] via ${item.mechanism}\n")
        }
        return sb.toString()
    }
}

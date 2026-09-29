package com.lichiai.browser.inspection.performance

import com.lichiai.browser.engine.ChromiumWebViewEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONObject

@Serializable
data class PerformanceAuditReport(
    val dnsLookupTimeMs: Long = 0L,
    val tcpConnectTimeMs: Long = 0L,
    val ttfbMs: Long = 0L,
    val domContentLoadedMs: Long = 0L,
    val fullPageLoadMs: Long = 0L,
    val firstContentfulPaintMs: Long = 0L,
    val totalTransferSizeEstimatedKb: Long = 0L,
    val performanceRating: String = "GOOD" // "EXCELLENT", "GOOD", "NEEDS_IMPROVEMENT", "POOR"
)

class BrowserPerformanceInspector {

    companion object {
        val PERFORMANCE_JS = """
            (function() {
                try {
                    var p = window.performance;
                    if (!p) return JSON.stringify({ error: 'No performance API' });

                    var nav = (p.getEntriesByType && p.getEntriesByType('navigation')[0]) || p.timing;
                    var paintEntries = (p.getEntriesByType && p.getEntriesByType('paint')) || [];
                    var fcp = 0;
                    for (var i = 0; i < paintEntries.length; i++) {
                        if (paintEntries[i].name === 'first-contentful-paint') {
                            fcp = Math.round(paintEntries[i].startTime);
                            break;
                        }
                    }

                    var dns = 0, tcp = 0, ttfb = 0, dcl = 0, load = 0;
                    if (nav) {
                        if (nav.domainLookupEnd && nav.domainLookupStart) {
                            dns = Math.max(0, Math.round(nav.domainLookupEnd - nav.domainLookupStart));
                        }
                        if (nav.connectEnd && nav.connectStart) {
                            tcp = Math.max(0, Math.round(nav.connectEnd - nav.connectStart));
                        }
                        if (nav.responseStart && nav.requestStart) {
                            ttfb = Math.max(0, Math.round(nav.responseStart - nav.requestStart));
                        }
                        if (nav.domContentLoadedEventEnd && (nav.startTime !== undefined ? nav.startTime : nav.navigationStart)) {
                            var base = nav.startTime !== undefined ? nav.startTime : nav.navigationStart;
                            dcl = Math.max(0, Math.round(nav.domContentLoadedEventEnd - base));
                        }
                        if (nav.loadEventEnd && (nav.startTime !== undefined ? nav.startTime : nav.navigationStart)) {
                            var base = nav.startTime !== undefined ? nav.startTime : nav.navigationStart;
                            load = Math.max(0, Math.round(nav.loadEventEnd - base));
                        }
                    }

                    return JSON.stringify({
                        dns: dns,
                        tcp: tcp,
                        ttfb: ttfb,
                        dcl: dcl,
                        load: load,
                        fcp: fcp
                    });
                } catch(e) {
                    return JSON.stringify({ error: e.toString() });
                }
            })();
        """.trimIndent()
    }

    suspend fun auditPerformance(engine: ChromiumWebViewEngine?): PerformanceAuditReport = withContext(Dispatchers.Default) {
        if (engine == null) return@withContext PerformanceAuditReport()
        val rawJson = engine.evaluateJavascriptAsync(PERFORMANCE_JS)
        try {
            val clean = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                rawJson.substring(1, rawJson.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else rawJson

            val json = JSONObject(clean)
            if (json.has("error")) return@withContext PerformanceAuditReport()

            val dns = json.optLong("dns", 0L)
            val tcp = json.optLong("tcp", 0L)
            val ttfb = json.optLong("ttfb", 0L)
            val dcl = json.optLong("dcl", 0L)
            val load = json.optLong("load", 0L)
            val fcp = json.optLong("fcp", 0L)

            val rating = when {
                load in 1..1500 -> "EXCELLENT"
                load in 1501..3500 -> "GOOD"
                load in 3501..6000 -> "NEEDS_IMPROVEMENT"
                load > 6000 -> "POOR"
                else -> "GOOD"
            }

            PerformanceAuditReport(
                dnsLookupTimeMs = dns,
                tcpConnectTimeMs = tcp,
                ttfbMs = ttfb,
                domContentLoadedMs = dcl,
                fullPageLoadMs = load,
                firstContentfulPaintMs = fcp,
                performanceRating = rating
            )
        } catch (_: Exception) {
            PerformanceAuditReport()
        }
    }
}

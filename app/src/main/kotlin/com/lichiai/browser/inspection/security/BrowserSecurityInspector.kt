package com.lichiai.browser.inspection.security

import android.webkit.CookieManager
import com.lichiai.browser.engine.ChromiumWebViewEngine
import com.lichiai.browser.inspection.network.NetworkObserver

class BrowserSecurityInspector(private val networkObserver: NetworkObserver) {

    fun auditSecurity(engine: ChromiumWebViewEngine?, pageUrl: String): SecurityAuditReport {
        val isHttps = pageUrl.startsWith("https://", ignoreCase = true)
        val requests = networkObserver.requests.value

        // Mixed Content Check: If main page is HTTPS, any HTTP request is mixed content
        val mixedContent = if (isHttps) {
            requests.filter { it.url.startsWith("http://", ignoreCase = true) }.map { it.url }
        } else emptyList()

        // Trackers
        val trackerDomains = listOf("google-analytics", "doubleclick", "facebook.net", "hotjar", "criteo", "segment.io", "mixpanel", "sentry.io")
        val detectedTrackers = requests.map { it.domain }
            .filter { d -> trackerDomains.any { t -> d.contains(t) } }
            .distinct()

        // Cookies
        var totalCookies = 0
        var secureCookies = 0
        try {
            val cookieStr = CookieManager.getInstance().getCookie(pageUrl)
            if (!cookieStr.isNullOrBlank()) {
                val items = cookieStr.split(';')
                totalCookies = items.size
                secureCookies = if (isHttps) totalCookies else 0
            }
        } catch (_: Exception) {}

        // Response headers inspection from main frame request
        val mainReq = requests.firstOrNull { it.isForMainFrame } ?: requests.firstOrNull { it.url == pageUrl }
        val respHeaders = mainReq?.responseHeaders ?: emptyMap()

        val securityHeadersPresent = mutableListOf<String>()
        val securityHeadersMissing = mutableListOf<String>()

        val expectedHeaders = listOf(
            "Strict-Transport-Security" to "HSTS",
            "Content-Security-Policy" to "CSP",
            "X-Content-Type-Options" to "No-Sniff",
            "X-Frame-Options" to "Anti-Clickjacking",
            "Referrer-Policy" to "Referrer Policy"
        )

        var cspDirectives = ""
        expectedHeaders.forEach { (hName, label) ->
            val foundKey = respHeaders.keys.firstOrNull { it.equals(hName, ignoreCase = true) }
            if (foundKey != null) {
                securityHeadersPresent.add(label)
                if (label == "CSP") {
                    cspDirectives = respHeaders[foundKey] ?: ""
                }
            } else {
                securityHeadersMissing.add(label)
            }
        }

        // Secret leakage scanner on all requests and response bodies
        val leakedSecrets = mutableListOf<String>()
        requests.take(30).forEach { r ->
            leakedSecrets.addAll(SecretRedactor.findSuspectedSecrets(r.url))
            leakedSecrets.addAll(SecretRedactor.findSuspectedSecrets(r.requestBodyPreview))
        }

        val riskScore = when {
            !isHttps || leakedSecrets.isNotEmpty() -> "HIGH"
            mixedContent.isNotEmpty() || securityHeadersMissing.size >= 4 -> "MEDIUM"
            else -> "LOW"
        }

        return SecurityAuditReport(
            isHttps = isHttps,
            protocol = if (isHttps) "HTTPS (TLS)" else "HTTP (Insecure)",
            certificateStatus = if (isHttps) "SECURE" else "UNENCRYPTED",
            hasMixedContent = mixedContent.isNotEmpty(),
            mixedContentUrls = mixedContent.take(5),
            totalCookies = totalCookies,
            secureCookiesCount = secureCookies,
            cspDetected = cspDirectives.isNotBlank(),
            cspDirectives = cspDirectives.take(200),
            suspectedLeakedSecrets = leakedSecrets.distinct().take(5),
            thirdPartyTrackers = detectedTrackers,
            securityHeadersPresent = securityHeadersPresent,
            securityHeadersMissing = securityHeadersMissing,
            riskScore = riskScore
        )
    }
}

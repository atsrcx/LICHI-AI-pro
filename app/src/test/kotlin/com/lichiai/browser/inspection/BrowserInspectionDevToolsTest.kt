package com.lichiai.browser.inspection

import com.lichiai.browser.inspection.analysis.FindingEngine
import com.lichiai.browser.inspection.cdp.OptionalCdpAdapter
import com.lichiai.browser.inspection.dom.DomSnapshot
import com.lichiai.browser.inspection.endpoint.EndpointClassifier
import com.lichiai.browser.inspection.endpoint.GraphQlDetector
import com.lichiai.browser.inspection.model.BrowserInspectionCapabilities
import com.lichiai.browser.inspection.network.NetworkObserver
import com.lichiai.browser.inspection.security.SecretRedactor
import com.lichiai.browser.inspection.security.SecurityAuditReport
import com.lichiai.intent.model.ResolvedIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserInspectionDevToolsTest {

    @Test
    fun testCapabilityMatrixIntegrity() {
        val matrix = BrowserInspectionCapabilities.CAPABILITY_MATRIX
        assertTrue("Matrix must contain capabilities", matrix.isNotEmpty())

        val domCap = matrix.first { it.capability.contains("DOM") }
        assertTrue("DOM inspection must be supported", domCap.isSupported)

        val netCap = matrix.first { it.capability.contains("Network Request") }
        assertTrue("Network request observation must be supported", netCap.isSupported)

        val cdpCap = matrix.first { it.capability.contains("Chrome DevTools Protocol") }
        assertFalse("In-process CDP must be marked as false/unavailable per platform constraints", cdpCap.isSupported)

        val cdpStatus = OptionalCdpAdapter.probeCdpTransport()
        assertFalse("CDP adapter probe must report isAvailable=false for sandboxed WebView", cdpStatus.isAvailable)
        assertTrue("Hybrid fallback must be active", cdpStatus.hybridFallbackActive)
    }

    @Test
    fun testEndpointClassification() {
        assertEquals("REST", EndpointClassifier.classify("https://api.example.com/v1/users", "GET", false))
        assertEquals("REST", EndpointClassifier.classify("https://example.com/items", "POST", false))
        assertEquals("GRAPHQL", EndpointClassifier.classify("https://api.example.com/graphql", "POST", true))
        assertEquals("AUTH", EndpointClassifier.classify("https://example.com/api/auth/login", "POST", false))
        assertEquals("SEARCH", EndpointClassifier.classify("https://example.com/api/search?q=phone", "GET", false))
        assertEquals("ANALYTICS", EndpointClassifier.classify("https://telemetry.example.com/events/collect", "POST", false))
        assertEquals("CONFIG", EndpointClassifier.classify("https://example.com/api/settings/config", "GET", false))
    }

    @Test
    fun testGraphQlDetection() {
        val res1 = GraphQlDetector.detect("https://example.com/graphql", null)
        assertTrue(res1.isGraphQl)
        assertEquals("query", res1.operationType)

        val mutationPayload = """{"query": "mutation UpdateUser { updateUser(id: 1) { id } }", "operationName": "UpdateUser"}"""
        val res2 = GraphQlDetector.detect("https://example.com/api", mutationPayload)
        assertTrue(res2.isGraphQl)
        assertEquals("mutation", res2.operationType)
        assertEquals("UpdateUser", res2.operationName)

        val plainJson = """{"username": "lichi", "action": "ping"}"""
        val res3 = GraphQlDetector.detect("https://example.com/api/ping", plainJson)
        assertFalse(res3.isGraphQl)
    }

    @Test
    fun testSecretRedaction() {
        val input = "https://api.example.com/data?apiKey=AIzaSyD1234567890abcdefghijklmnopqr"
        val redacted = SecretRedactor.redact(input)
        assertFalse("Real API key must not remain unmasked", redacted.contains("AIzaSyD1234567890abcdefghijklmnopqr"))
        assertTrue("Redaction tag must be present", redacted.contains("[REDACTED]"))

        val found = SecretRedactor.findSuspectedSecrets(input)
        assertTrue("Must detect suspected secret", found.isNotEmpty())
    }

    @Test
    fun testNetworkObserverRecordingAndReport() {
        val observer = NetworkObserver()

        val req1 = observer.recordRequest(
            url = "https://example.com/api/v1/users",
            method = "GET",
            headers = mapOf("Accept" to "application/json"),
            isForMainFrame = false,
            hasUserGesture = false,
            pageUrl = "https://example.com"
        )
        observer.completeRequest(req1, 200, "application/json")

        observer.recordJsFetchOrXhr(
            url = "https://example.com/api/v1/posts",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            requestBody = """{"title": "Test"}""",
            statusCode = 201,
            statusText = "Created",
            durationMs = 120,
            responseHeaders = mapOf("content-type" to "application/json"),
            responseBody = """{"id": 42}""",
            pageUrl = "https://example.com"
        )

        val report = observer.generateAnalysisReport()
        assertEquals(2, report.totalRequests)
        assertEquals(2, report.observedEndpointsCount)
        assertEquals(0, report.timeline.totalFailed)
    }

    @Test
    fun testFindingEngineSynthesis() {
        val dom = DomSnapshot(pageUrl = "https://example.com", title = "Example")
        val sec = SecurityAuditReport(
            isHttps = true,
            hasMixedContent = false,
            suspectedLeakedSecrets = emptyList()
        )
        val findings = FindingEngine.generateFindings(
            dom = dom,
            endpoints = emptyList(),
            requests = emptyList(),
            resources = emptyList(),
            downloads = emptyList(),
            security = sec,
            performance = null,
            consoleLogs = emptyList()
        )
        assertNotNull(findings)
    }

    @Test
    fun testComprehensiveReportGenerationFallbackWithNullEngine() {
        val session = com.lichiai.browser.inspection.model.BrowserInspectionSession(
            inspectionSessionId = "test_session",
            browserTabId = "tab_1",
            pageId = "page_1",
            targetUrl = "about:blank",
            pageTitle = "Blank",
            mode = "FULL_INSPECTION"
        )
        val report = com.lichiai.browser.inspection.analysis.WebsiteAnalysisEngine.generateComprehensiveReport(
            session = session,
            dom = DomSnapshot(pageUrl = "about:blank", title = ""),
            endpoints = emptyList(),
            requests = emptyList(),
            resources = emptyList(),
            downloads = emptyList(),
            security = SecurityAuditReport(isHttps = false, protocol = "HTTP", certificateStatus = "UNENCRYPTED"),
            performance = com.lichiai.browser.inspection.performance.PerformanceAuditReport(),
            consoleLogs = emptyList()
        )
        assertNotNull(report)
        assertEquals("test_session", report.session.inspectionSessionId)
        assertNotNull(report.summary)
        assertNotNull(report.technicalStructureExplanation)
    }
}

package com.lichiai.browser.tools

import com.lichiai.browser.api.BrowserCapabilityAPI
import com.lichiai.browser.api.ScrollDirection

data class BrowserToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class BrowserToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<BrowserToolParam>
)

/**
 * Isolated Tool Registry containing solely browser automation tools.
 * Completely separate from Lichi device-control tools or outside accessibility tools.
 */
class BrowserToolRegistry(private val capabilityApi: BrowserCapabilityAPI) {

    val tools: List<BrowserToolDefinition> = listOf(
        BrowserToolDefinition(
            name = "navigate",
            description = "Navigate the active browser tab to a specified HTTPS URL.",
            parameters = listOf(
                BrowserToolParam("url", "string", "Destination URL to load")
            )
        ),
        BrowserToolDefinition(
            name = "search",
            description = "Search the web using configured search engine.",
            parameters = listOf(
                BrowserToolParam("query", "string", "Search query terms"),
                BrowserToolParam("engine", "string", "Optional search engine (google, duckduckgo, bing, youtube)", required = false)
            )
        ),
        BrowserToolDefinition(
            name = "clickCandidate",
            description = "Click a numbered search result link or candidate on the active webpage.",
            parameters = listOf(
                BrowserToolParam("index", "integer", "1-based ordinal index of candidate link")
            )
        ),
        BrowserToolDefinition(
            name = "clickElement",
            description = "Click an indexed interactive DOM element (button, link, input, tab, etc.) on the active webpage.",
            parameters = listOf(
                BrowserToolParam("index", "integer", "1-based ordinal index of interactive element")
            )
        ),
        BrowserToolDefinition(
            name = "clickSelector",
            description = "Click an element matching target text or selector.",
            parameters = listOf(
                BrowserToolParam("text", "string", "Visible text or selector of the element to click")
            )
        ),
        BrowserToolDefinition(
            name = "typeText",
            description = "Type text into an input field or textarea.",
            parameters = listOf(
                BrowserToolParam("text", "string", "Text to type into input"),
                BrowserToolParam("index", "integer", "Optional element index", required = false),
                BrowserToolParam("selector", "string", "Optional CSS selector", required = false),
                BrowserToolParam("submit", "boolean", "Whether to submit form/press enter after typing", required = false)
            )
        ),
        BrowserToolDefinition(
            name = "scroll",
            description = "Scroll the active webpage down, up, top, or bottom.",
            parameters = listOf(
                BrowserToolParam("direction", "string", "DOWN, UP, TOP, or BOTTOM"),
                BrowserToolParam("amount", "integer", "Scroll multiplier", required = false)
            )
        ),
        BrowserToolDefinition(
            name = "goBack",
            description = "Navigate back in browser history.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "goForward",
            description = "Navigate forward in browser history.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "reload",
            description = "Reload current webpage.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "openTab",
            description = "Open a new browser tab.",
            parameters = listOf(
                BrowserToolParam("url", "string", "Optional URL to load", required = false),
                BrowserToolParam("isIncognito", "boolean", "Open private tab", required = false)
            )
        ),
        BrowserToolDefinition(
            name = "closeTab",
            description = "Close the current or specified tab.",
            parameters = listOf(
                BrowserToolParam("tabId", "string", "Tab ID to close", required = false)
            )
        ),
        BrowserToolDefinition(
            name = "inspectElements",
            description = "Inspect detected interactive DOM elements on current page.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "extractPageSummary",
            description = "Extract main readable text snippet from active page.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "extractPrices",
            description = "Extract detected price figures from active page.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "extractTables",
            description = "Extract structured table data from active page.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "highlightElement",
            description = "Highlight a specific DOM element for the user.",
            parameters = listOf(
                BrowserToolParam("index", "integer", "1-based index of element to highlight")
            )
        ),
        BrowserToolDefinition(
            name = "switchTab",
            description = "Switch active tab to the specified tabId.",
            parameters = listOf(
                BrowserToolParam("tabId", "string", "Destination tab ID")
            )
        ),
        BrowserToolDefinition(
            name = "captureScreenshot",
            description = "Capture visual snapshot bitmap of current webpage viewport.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "stopTask",
            description = "Stop active agent execution and return control to user.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "inspectPage",
            description = "Deeply inspect active webpage (DOM, endpoints, network, resources, security, performance, errors).",
            parameters = listOf(
                BrowserToolParam("mode", "string", "Optional inspection mode: FULL_INSPECTION, ENDPOINT_INSPECTION, NETWORK_INSPECTION, SECURITY_INSPECTION", required = false)
            )
        ),
        BrowserToolDefinition(
            name = "discoverEndpoints",
            description = "Identify all observed API endpoints on the active website with methods, paths, and parameters.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "discoverDownloads",
            description = "Extract direct download links (.apk, .pdf, .zip, etc.) on the current page.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "discoverResources",
            description = "List all loaded resources, scripts, stylesheets, and external connected domains.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "discoverLinks",
            description = "Extract and categorize all links on the current page (internal, external, downloads, APIs).",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "analyzeNetwork",
            description = "Analyze all network requests, latency timeline, status codes, and third-party servers.",
            parameters = emptyList()
        ),
        BrowserToolDefinition(
            name = "explainTechnicalStructure",
            description = "Provide an in-depth technical and architectural explanation of the website.",
            parameters = emptyList()
        )
    )

    suspend fun executeTool(name: String, args: Map<String, String>): String {
        return try {
            when (name) {
                "navigate" -> {
                    val url = args["url"] ?: return "ERROR: 'url' required"
                    val ok = capabilityApi.navigate(url)
                    if (ok) "SUCCESS: Navigating to $url" else "FAILED: Navigation rejected or failed"
                }
                "search" -> {
                    val q = args["query"] ?: return "ERROR: 'query' required"
                    val engine = args["engine"]
                    val ok = capabilityApi.search(q, engine)
                    if (ok) "SUCCESS: Searching for \"$q\"" else "FAILED: Search execution failed"
                }
                "clickCandidate" -> {
                    val idx = args["index"]?.toIntOrNull() ?: 1
                    val targetUrl = args["url"]
                    val ok = capabilityApi.clickCandidate(idx, targetUrl)
                    if (ok) "SUCCESS: Clicked candidate link #$idx" else "FAILED: Candidate #$idx not found or failed to click"
                }
                "clickElement" -> {
                    val idx = args["index"]?.toIntOrNull() ?: 1
                    val ok = capabilityApi.clickElement(idx)
                    if (ok) "SUCCESS: Clicked interactive element #$idx" else "FAILED: Element #$idx not found"
                }
                "clickSelector" -> {
                    val text = args["text"] ?: return "ERROR: 'text' required"
                    val ok = capabilityApi.clickSelector(text)
                    if (ok) "SUCCESS: Clicked element matching \"$text\"" else "FAILED: Element \"$text\" not found"
                }
                "typeText" -> {
                    val text = args["text"] ?: return "ERROR: 'text' required"
                    val idx = args["index"]?.toIntOrNull()
                    val selector = args["selector"]
                    val submit = args["submit"]?.toBoolean() ?: false
                    val ok = capabilityApi.typeText(index = idx, selector = selector, text = text, submit = submit)
                    if (ok) "SUCCESS: Typed \"$text\"" else "FAILED: Input target not found"
                }
                "scroll" -> {
                    val dir = when (args["direction"]?.uppercase()) {
                        "UP" -> ScrollDirection.UP
                        "TOP" -> ScrollDirection.TOP
                        "BOTTOM" -> ScrollDirection.BOTTOM
                        else -> ScrollDirection.DOWN
                    }
                    val amount = args["amount"]?.toIntOrNull() ?: 1
                    capabilityApi.scroll(dir, amount)
                    "SUCCESS: Scrolled ${dir.name}"
                }
                "goBack" -> {
                    val ok = capabilityApi.goBack()
                    if (ok) "SUCCESS: Navigated back" else "NOTICE: Cannot go back (at start of history)"
                }
                "goForward" -> {
                    val ok = capabilityApi.goForward()
                    if (ok) "SUCCESS: Navigated forward" else "NOTICE: Cannot go forward"
                }
                "reload" -> {
                    capabilityApi.reload()
                    "SUCCESS: Reloading page"
                }
                "openTab" -> {
                    val url = args["url"]
                    val incognito = args["isIncognito"]?.toBoolean() ?: false
                    val tabId = capabilityApi.openTab(url, incognito)
                    "SUCCESS: Opened tab $tabId"
                }
                "closeTab" -> {
                    val tabId = args["tabId"] ?: return "ERROR: 'tabId' required"
                    val ok = capabilityApi.closeTab(tabId)
                    if (ok) "SUCCESS: Closed tab $tabId" else "FAILED: Failed to close tab"
                }
                "inspectElements" -> {
                    val els = capabilityApi.getInteractiveElements()
                    if (els.isEmpty()) "No interactive elements detected on page."
                    else "Detected ${els.size} interactive elements:\n" + els.take(15).joinToString("\n") {
                        "#${it.index} [${it.tag.uppercase()}${if (it.type.isNotBlank()) ":${it.type}" else ""}] \"${it.text.ifBlank { it.placeholder.ifBlank { it.ariaLabel } }}\""
                    }
                }
                "extractPageSummary" -> {
                    val summary = capabilityApi.extractPageSummary()
                    "Extracted Summary:\n$summary"
                }
                "extractPrices" -> {
                    val prices = capabilityApi.extractPrices()
                    if (prices.isEmpty()) "No explicit price tags detected on page."
                    else "Detected Prices: ${prices.joinToString(", ")}"
                }
                "extractTables" -> {
                    val tables = capabilityApi.extractTables()
                    if (tables.isEmpty()) "No structured tables detected on page."
                    else {
                        val sb = StringBuilder("Extracted ${tables.size} tables:\n")
                        tables.forEachIndexed { i, t ->
                            sb.append("--- ${t.title.ifBlank { "Table ${i + 1}" }} ---\n")
                            if (t.headers.isNotEmpty()) {
                                sb.append(t.headers.joinToString(" | ") + "\n")
                            }
                            t.rows.take(5).forEach { r ->
                                sb.append(r.joinToString(" | ") + "\n")
                            }
                        }
                        sb.toString()
                    }
                }
                "highlightElement" -> {
                    val idx = args["index"]?.toIntOrNull() ?: 1
                    val ok = capabilityApi.highlightElement(idx)
                    if (ok) "SUCCESS: Highlighted element #$idx" else "FAILED: Element #$idx not found"
                }
                "switchTab" -> {
                    val tabId = args["tabId"] ?: return "ERROR: 'tabId' required"
                    val ok = capabilityApi.switchTab(tabId)
                    if (ok) "SUCCESS: Switched to tab $tabId" else "FAILED: Tab $tabId not found"
                }
                "captureScreenshot" -> {
                    val bmp = capabilityApi.captureScreenshot()
                    if (bmp != null) "SUCCESS: Captured screenshot (${bmp.width}x${bmp.height})"
                    else "FAILED: Screenshot capture unavailable"
                }
                "stopTask" -> {
                    capabilityApi.stopTask()
                    "SUCCESS: Task stopped"
                }
                "inspectPage" -> {
                    val controller = capabilityApi as? com.lichiai.browser.BrowserController
                        ?: return "ERROR: BrowserController not available for inspection"
                    val modeStr = args["mode"]
                    val mode = com.lichiai.browser.inspection.model.InspectionMode.fromString(modeStr)
                    val report = controller.inspectCurrentPage(mode = mode)
                    buildString {
                        append("=== PAGE INSPECTION REPORT ===\n")
                        append("URL: ${report.session.targetUrl}\n")
                        append("Title: ${report.session.pageTitle}\n")
                        append("Summary: ${report.summary.totalRequests} requests, ${report.summary.totalEndpointsDiscovered} APIs, ${report.summary.totalDownloadsDetected} downloads, ${report.summary.totalFormsDetected} forms, ${report.summary.totalErrorsCount} errors\n")
                        append("HTTPS: ${report.summary.isHttps} | Mixed Content: ${report.summary.hasMixedContent}\n")
                        append("Load Time: ${report.summary.fullLoadTimeMs}ms\n\n")
                        if (report.findings.isNotEmpty()) {
                            append("Key Findings:\n")
                            report.findings.forEach { f ->
                                append("• [${f.category}] ${f.title}: ${f.description}\n")
                            }
                        }
                    }
                }
                "discoverEndpoints" -> {
                    val controller = capabilityApi as? com.lichiai.browser.BrowserController
                        ?: return "ERROR: BrowserController not available"
                    val eps = controller.discoverEndpoints()
                    if (eps.isEmpty()) "No asynchronous API endpoints observed yet."
                    else {
                        buildString {
                            append("Discovered ${eps.size} API endpoints:\n")
                            eps.forEachIndexed { i, ep ->
                                append("${i + 1}. [${ep.method}] ${ep.path} (Domain: ${ep.domain}, Category: ${ep.category}, Status: ${ep.responseStatusCode})\n")
                                if (ep.parameters.isNotEmpty()) {
                                    append("   Parameters: " + ep.parameters.joinToString(", ") { "${it.name}=${it.sampleValue}" } + "\n")
                                }
                            }
                        }
                    }
                }
                "discoverDownloads" -> {
                    val controller = capabilityApi as? com.lichiai.browser.BrowserController
                        ?: return "ERROR: BrowserController not available"
                    val dls = controller.discoverDownloads()
                    if (dls.isEmpty()) "No direct download links identified on page."
                    else {
                        buildString {
                            append("Found ${dls.size} downloadable files:\n")
                            dls.forEachIndexed { i, dl ->
                                append("${i + 1}. ${dl.filename} (.${dl.extension})\n   URL: ${dl.url}\n   Source: ${dl.detectedVia}\n")
                            }
                        }
                    }
                }
                "discoverResources" -> {
                    val controller = capabilityApi as? com.lichiai.browser.BrowserController
                        ?: return "ERROR: BrowserController not available"
                    val res = controller.discoverResources()
                    buildString {
                        append("Discovered ${res.size} resources:\n")
                        val byType = res.groupBy { it.type }
                        byType.forEach { (type, list) ->
                            append("\n[$type] (${list.size} items):\n")
                            list.take(6).forEach { r ->
                                append("• ${r.domain} - ${r.url.take(80)}\n")
                            }
                        }
                    }
                }
                "discoverLinks" -> {
                    val controller = capabilityApi as? com.lichiai.browser.BrowserController
                        ?: return "ERROR: BrowserController not available"
                    val links = controller.discoverLinks()
                    if (links.isEmpty()) "No links found."
                    else {
                        buildString {
                            append("Discovered ${links.size} links on page:\n")
                            val byCat = links.groupBy { it.category }
                            byCat.forEach { (cat, list) ->
                                append("\n=== Category: $cat (${list.size}) ===\n")
                                list.take(10).forEach { l ->
                                    append("• \"${l.text}\" -> ${l.url}\n")
                                }
                            }
                        }
                    }
                }
                "analyzeNetwork" -> {
                    val controller = capabilityApi as? com.lichiai.browser.BrowserController
                        ?: return "ERROR: BrowserController not available"
                    val analysis = controller.analyzeNetwork()
                    buildString {
                        append("=== NETWORK ANALYSIS ===\n")
                        append("Total Requests: ${analysis.totalRequests}\n")
                        append("Failed (4xx/5xx/Error): ${analysis.timeline.totalFailed}\n")
                        append("Slow (>1s): ${analysis.timeline.totalSlowRequests}\n")
                        append("Average Latency: ${analysis.timeline.averageLatencyMs}ms\n\n")
                        append("Top Connected Domains:\n")
                        analysis.topDomains.forEach { (domain, count) ->
                            append("• $domain: $count requests\n")
                        }
                    }
                }
                "explainTechnicalStructure" -> {
                    val controller = capabilityApi as? com.lichiai.browser.BrowserController
                        ?: return "ERROR: BrowserController not available"
                    val report = controller.inspectCurrentPage(com.lichiai.browser.inspection.model.InspectionMode.FULL_INSPECTION)
                    report.technicalStructureExplanation
                }
                else -> "ERROR: Unknown browser tool '$name'"
            }
        } catch (e: Exception) {
            "ERROR: Failed executing $name: ${e.message}"
        }
    }
}

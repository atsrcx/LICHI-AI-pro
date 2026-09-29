package com.lichiai.browser.inspection.resources

import android.net.Uri
import com.lichiai.browser.engine.ChromiumWebViewEngine
import com.lichiai.browser.inspection.network.NetworkObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

object UrlNormalizer {

    fun getDomain(urlStr: String): String {
        return try {
            val uri = Uri.parse(urlStr)
            uri.host ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun isSameOrigin(url1: String, url2: String): Boolean {
        val d1 = getDomain(url1)
        val d2 = getDomain(url2)
        if (d1.isBlank() || d2.isBlank()) return false
        return d1.equals(d2, ignoreCase = true)
    }

    fun isCdnDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        return lower.contains("cdn") || lower.contains("cloudflare") || lower.contains("cloudfront") ||
            lower.contains("akamai") || lower.contains("fastly") || lower.contains("jsdelivr") ||
            lower.contains("unpkg") || lower.contains("googleapis") || lower.contains("gstatic") ||
            lower.contains("githubusercontent") || lower.contains("azureedge")
    }
}

class ResourceDiscoveryEngine(private val networkObserver: NetworkObserver) {

    companion object {
        val EXTRACT_ALL_LINKS_JS = """
            (function() {
                try {
                    var anchors = document.querySelectorAll('a[href]');
                    var links = [];
                    var origin = window.location.origin;

                    for (var i = 0; i < anchors.length; i++) {
                        var a = anchors[i];
                        var href = a.href || '';
                        var text = (a.innerText || a.textContent || '').trim().replace(/\s+/g, ' ');

                        if (!href || href.startsWith('javascript:')) continue;

                        var isSame = false;
                        try {
                            var u = new URL(href, window.location.href);
                            isSame = (u.origin === origin);
                        } catch(e) {}

                        links.push({
                            url: href,
                            text: text.substring(0, 100),
                            isSameOrigin: isSame
                        });
                    }
                    return JSON.stringify(links);
                } catch(e) {
                    return "[]";
                }
            })();
        """.trimIndent()

        val EXTRACT_DOM_RESOURCES_JS = """
            (function() {
                try {
                    var resources = [];

                    // Scripts
                    var scripts = document.querySelectorAll('script');
                    for (var s = 0; s < scripts.length; s++) {
                        var src = scripts[s].src || '';
                        resources.push({
                            url: src || ('inline-script-' + (s + 1)),
                            type: 'Script',
                            detectedVia: 'DOM'
                        });
                    }

                    // Stylesheets
                    var links = document.querySelectorAll('link[rel="stylesheet"]');
                    for (var l = 0; l < links.length; l++) {
                        resources.push({
                            url: links[l].href || '',
                            type: 'Stylesheet',
                            detectedVia: 'DOM'
                        });
                    }

                    // Images
                    var imgs = document.querySelectorAll('img[src]');
                    for (var m = 0; m < imgs.length && m < 30; m++) {
                        resources.push({
                            url: imgs[m].src || '',
                            type: 'Image',
                            detectedVia: 'DOM'
                        });
                    }

                    return JSON.stringify(resources);
                } catch(e) {
                    return "[]";
                }
            })();
        """.trimIndent()
    }

    suspend fun discoverAllUrls(engine: ChromiumWebViewEngine?, pageUrl: String): List<DiscoveredUrlRecord> = withContext(Dispatchers.Default) {
        if (engine == null) return@withContext emptyList()
        val rawJson = engine.evaluateJavascriptAsync(EXTRACT_ALL_LINKS_JS)
        parseDiscoveredUrls(rawJson, pageUrl)
    }

    suspend fun discoverAllResources(engine: ChromiumWebViewEngine?, pageUrl: String): List<ResourceRecord> = withContext(Dispatchers.Default) {
        val resourceMap = mutableMapOf<String, ResourceRecord>()

        // 1. Ingest observed network requests
        networkObserver.requests.value.forEach { req ->
            if (req.url.isNotBlank() && req.url.startsWith("http")) {
                val domain = UrlNormalizer.getDomain(req.url)
                val isCdn = UrlNormalizer.isCdnDomain(domain)
                resourceMap[req.url] = ResourceRecord(
                    url = req.url,
                    domain = domain,
                    type = req.resourceType,
                    isThirdParty = req.isThirdParty,
                    isExternalCdn = isCdn,
                    initiator = req.initiator,
                    statusCode = req.statusCode,
                    mimeType = req.mimeType,
                    detectedVia = "Network"
                )
            }
        }

        // 2. Supplement with DOM static references
        if (engine != null) {
            val rawDomRes = engine.evaluateJavascriptAsync(EXTRACT_DOM_RESOURCES_JS)
            try {
                val clean = if (rawDomRes.startsWith("\"") && rawDomRes.endsWith("\"")) {
                    rawDomRes.substring(1, rawDomRes.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                } else rawDomRes
                val array = JSONArray(clean)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val url = obj.optString("url", "")
                    val type = obj.optString("type", "Other")
                    if (url.isNotBlank() && !resourceMap.containsKey(url)) {
                        val domain = UrlNormalizer.getDomain(url)
                        val isThird = pageUrl.isNotBlank() && !UrlNormalizer.isSameOrigin(pageUrl, url)
                        val isCdn = UrlNormalizer.isCdnDomain(domain)
                        resourceMap[url] = ResourceRecord(
                            url = url,
                            domain = domain,
                            type = type,
                            isThirdParty = isThird,
                            isExternalCdn = isCdn,
                            initiator = "DOM",
                            statusCode = 200,
                            mimeType = "",
                            detectedVia = "DOM"
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        resourceMap.values.toList()
    }

    private fun parseDiscoveredUrls(rawJson: String, pageUrl: String): List<DiscoveredUrlRecord> {
        val list = mutableListOf<DiscoveredUrlRecord>()
        try {
            val clean = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                rawJson.substring(1, rawJson.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            } else rawJson
            val array = JSONArray(clean)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val url = obj.optString("url", "")
                val text = obj.optString("text", "")
                val isSame = obj.optBoolean("isSameOrigin", false)

                val cat = categorizeUrl(url)
                list.add(
                    DiscoveredUrlRecord(
                        url = url,
                        text = text,
                        category = cat,
                        isSameOrigin = isSame
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun categorizeUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".apk") || lower.contains(".pdf") || lower.contains(".zip") ||
                lower.contains(".exe") || lower.contains("/download") -> "DOWNLOAD"
            lower.contains("/api/") || lower.contains("/v1/") || lower.contains("/graphql") -> "API_LINK"
            lower.contains(".png") || lower.contains(".jpg") || lower.contains(".mp4") || lower.contains(".mp3") -> "MEDIA"
            lower.startsWith("#") || url.contains("#") -> "ANCHOR"
            lower.startsWith("mailto:") || lower.startsWith("tel:") -> "CONTACT"
            else -> "PAGE"
        }
    }
}

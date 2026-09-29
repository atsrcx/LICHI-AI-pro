package com.lichiai.browser.inspection.resources

import kotlinx.serialization.Serializable

@Serializable
data class ResourceRecord(
    val url: String,
    val domain: String,
    val type: String, // Script, Stylesheet, Image, Font, Media, Document, Manifest, Other
    val isThirdParty: Boolean = false,
    val isExternalCdn: Boolean = false,
    val initiator: String = "DOM",
    val statusCode: Int = 200,
    val mimeType: String = "",
    val detectedVia: String = "Network"
)

@Serializable
data class DiscoveredUrlRecord(
    val url: String,
    val text: String,
    val category: String, // "INTERNAL_PAGE", "EXTERNAL_DOMAIN", "API_LINK", "MEDIA", "DOWNLOAD", "ANCHOR"
    val isSameOrigin: Boolean
)

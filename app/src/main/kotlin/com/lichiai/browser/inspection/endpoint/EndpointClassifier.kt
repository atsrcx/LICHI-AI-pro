package com.lichiai.browser.inspection.endpoint

object EndpointClassifier {

    fun classify(url: String, method: String, isGraphQl: Boolean): String {
        if (isGraphQl) return "GRAPHQL"

        val lower = url.lowercase()
        return when {
            lower.contains("/auth") || lower.contains("/login") || lower.contains("/oauth") ||
                lower.contains("/token") || lower.contains("/session") || lower.contains("/signin") -> "AUTH"

            lower.contains("/search") || lower.contains("/suggest") || lower.contains("/autocomplete") ||
                lower.contains("/query") || lower.contains("/find") -> "SEARCH"

            lower.contains("/analytics") || lower.contains("/collect") || lower.contains("/telemetry") ||
                lower.contains("/track") || lower.contains("/pixel") || lower.contains("/beacon") ||
                lower.contains("/events") || lower.contains("sentry.io") || lower.contains("datadoghq") ||
                lower.contains("google-analytics") -> "ANALYTICS"

            lower.contains("/config") || lower.contains("/settings") || lower.contains("/features") ||
                lower.contains("/env") || lower.contains("/manifest") -> "CONFIG"

            lower.contains("/rpc") || lower.contains("/jsonrpc") || lower.contains("/grpc") -> "RPC"

            lower.contains("/media") || lower.contains("/upload") || lower.contains("/storage") ||
                lower.contains("/files") -> "MEDIA"

            lower.contains("/api/") || lower.contains("/v1/") || lower.contains("/v2/") ||
                lower.contains("/v3/") || lower.contains("/rest/") ||
                method.uppercase() in listOf("POST", "PUT", "DELETE", "PATCH") -> "REST"

            else -> "DATA"
        }
    }
}

package com.lichiai.browser.inspection.endpoint

import org.json.JSONObject

object GraphQlDetector {

    data class GraphQlDetails(
        val isGraphQl: Boolean,
        val operationName: String? = null,
        val operationType: String? = null,
        val querySnippet: String? = null
    )

    fun detect(url: String, payload: String?): GraphQlDetails {
        val lowerUrl = url.lowercase()
        val isUrlGraphQl = lowerUrl.contains("/graphql") || lowerUrl.contains("/gql")

        if (!payload.isNullOrBlank()) {
            try {
                val json = JSONObject(payload)
                if (json.has("query") || json.has("mutation")) {
                    val query = json.optString("query", "")
                    val opName = if (json.has("operationName")) json.optString("operationName").takeIf { it.isNotBlank() } else null
                    val opType = when {
                        query.trimStart().startsWith("mutation") -> "mutation"
                        query.trimStart().startsWith("subscription") -> "subscription"
                        else -> "query"
                    }
                    return GraphQlDetails(
                        isGraphQl = true,
                        operationName = opName,
                        operationType = opType,
                        querySnippet = query.take(300)
                    )
                }
            } catch (_: Exception) {}
        }

        if (isUrlGraphQl) {
            return GraphQlDetails(
                isGraphQl = true,
                operationName = null,
                operationType = "query",
                querySnippet = null
            )
        }

        return GraphQlDetails(isGraphQl = false)
    }
}

package com.lichiai.spy.input

import com.lichiai.spy.model.SpyOperation
import com.lichiai.spy.model.SpyTask
import org.json.JSONArray
import org.json.JSONObject

class ActorInputBuilder {
    fun build(actorId: String, task: SpyTask): String {
        val root = JSONObject()
        val lowerActor = actorId.lowercase()

        when {
            task.operation == SpyOperation.PUBLIC_PHONE_LOOKUP -> {
                // Phone & Contact scrapers expect queries or search terms
                root.put("queries", JSONArray().put(task.target))
                root.put("query", task.target)
                root.put("searchTerm", task.target)
                root.put("maxResults", 5)
            }
            lowerActor.contains("instagram") -> {
                root.put("usernames", JSONArray().put(task.target))
                root.put("resultsLimit", 1)
            }
            lowerActor.contains("twitter") || lowerActor.contains("x-scraper") -> {
                root.put("handles", JSONArray().put(task.target))
                root.put("maxItems", 1)
            }
            lowerActor.contains("google-search") -> {
                root.put("queries", task.target)
                root.put("maxPagesPerQuery", 1)
            }
            else -> {
                root.put("username", task.target)
                root.put("query", task.target)
                root.put("search", task.target)
            }
        }
        return root.toString()
    }
}

package com.lichiai.spy.apify

import android.util.Log
import com.lichiai.spy.core.SpyError
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * Isolated REST Client for official Apify API v2.
 * Documentation: https://docs.apify.com/api/v2
 *
 * All endpoints use Bearer Authorization in HTTP Headers.
 * Never logs or exposes token in logs, exceptions, or error messages.
 */
class ApifyClient(
    private val apiTokenProvider: () -> String?
) {
    companion object {
        private const val TAG = "ApifyClient"
        private const val BASE_URL = "https://api.apify.com/v2"
        private const val DEFAULT_TIMEOUT_MS = 60_000L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_TIMEOUT_MS
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = DEFAULT_TIMEOUT_MS
        }
    }

    /**
     * Parses official Apify error payload from response if present.
     * Sanitizes response and never includes token.
     */
    private fun parseApifyError(response: HttpResponse, bodyText: String, endpointName: String): String {
        return try {
            val errorObj = json.decodeFromString<ApifyErrorResponse>(bodyText)
            val type = errorObj.error?.type ?: "APIFY_ERROR"
            val message = errorObj.error?.message ?: bodyText.take(200)
            "[Endpoint: $endpointName] HTTP ${response.status.value} (Type: $type): $message"
        } catch (_: Exception) {
            "[Endpoint: $endpointName] HTTP ${response.status.value}: ${bodyText.take(200).ifBlank { response.status.description }}"
        }
    }

    /**
     * Verifies API token by querying GET /v2/users/me.
     */
    suspend fun verifyToken(): Result<ApifyUserData> = withContext(Dispatchers.IO) {
        val token = apiTokenProvider()?.trim()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(SpyError.ConfigurationMissing())
        }

        try {
            val response: HttpResponse = httpClient.get("$BASE_URL/users/me") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $token")
                }
            }

            val text = response.bodyAsText()

            if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                val errDetails = parseApifyError(response, text, "GET /v2/users/me")
                return@withContext Result.failure(SpyError.InvalidApiToken("Authentication failed. $errDetails"))
            }

            if (!response.status.isSuccess()) {
                val errDetails = parseApifyError(response, text, "GET /v2/users/me")
                return@withContext Result.failure(SpyError.NetworkError(errDetails))
            }

            val userResp = json.decodeFromString<ApifyUserResponse>(text)
            val data = userResp.data ?: return@withContext Result.failure(SpyError.InvalidApiToken("No user data in response"))
            Result.success(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify Apify token: ${e.message}")
            Result.failure(SpyError.NetworkError(e.message ?: "Unknown network error", e))
        }
    }

    /**
     * Searches public Store for candidate Actors via GET /v2/store.
     */
    suspend fun searchStore(
        query: String,
        limit: Int = 10,
        offset: Int = 0,
        sortBy: String = "popularity"
    ): Result<List<ApifyStoreItem>> = withContext(Dispatchers.IO) {
        val token = apiTokenProvider()?.trim()
        try {
            val response: HttpResponse = httpClient.get("$BASE_URL/store") {
                headers {
                    if (!token.isNullOrBlank()) {
                        append(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
                parameter("search", query)
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("sortBy", sortBy)
            }

            val text = response.bodyAsText()

            if (response.status == HttpStatusCode.TooManyRequests) {
                return@withContext Result.failure(SpyError.RateLimited())
            }

            if (!response.status.isSuccess()) {
                val err = parseApifyError(response, text, "GET /v2/store")
                return@withContext Result.failure(SpyError.NetworkError("Store query failed: $err"))
            }

            val storeResp = json.decodeFromString<ApifyStoreResponse>(text)
            val items = storeResp.data?.items ?: emptyList()
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "Failed searching Apify store: ${e.message}")
            Result.failure(SpyError.NetworkError(e.message ?: "Network error", e))
        }
    }

    /**
     * Validates and retrieves detailed Actor metadata via GET /v2/acts/:actorId.
     * Uses ActorIdentifierResolver to ensure canonical username~actor-name or actorId formatting.
     */
    suspend fun getActorDetail(actorId: String): Result<ApifyActorDetail> = withContext(Dispatchers.IO) {
        val canonicalId = ActorIdentifierResolver.toCanonicalApiId(actorId)
        if (canonicalId.isBlank()) {
            return@withContext Result.failure(SpyError.ActorNotFound("Blank Actor identifier"))
        }

        val token = apiTokenProvider()?.trim()
        try {
            val response: HttpResponse = httpClient.get("$BASE_URL/acts/$canonicalId") {
                headers {
                    if (!token.isNullOrBlank()) {
                        append(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
            }

            val text = response.bodyAsText()

            if (response.status == HttpStatusCode.NotFound) {
                val err = parseApifyError(response, text, "GET /v2/acts/$canonicalId")
                return@withContext Result.failure(SpyError.ActorNotFound("$canonicalId ($err)"))
            }

            if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                val err = parseApifyError(response, text, "GET /v2/acts/$canonicalId")
                return@withContext Result.failure(SpyError.Forbidden("Access to Actor '$canonicalId' forbidden: $err"))
            }

            if (!response.status.isSuccess()) {
                val err = parseApifyError(response, text, "GET /v2/acts/$canonicalId")
                return@withContext Result.failure(SpyError.NetworkError("Failed to fetch Actor details: $err"))
            }

            val detailResp = json.decodeFromString<ApifyActorDetailResponse>(text)
            val data = detailResp.data ?: return@withContext Result.failure(SpyError.ActorUnavailable(canonicalId))
            Result.success(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed fetching actor detail for $canonicalId: ${e.message}")
            Result.failure(SpyError.NetworkError(e.message ?: "Network error", e))
        }
    }

    /**
     * Helper alias to explicitly validate an Actor exists and is reachable.
     */
    suspend fun validateActor(actorId: String): Result<ApifyActorDetail> {
        return getActorDetail(actorId)
    }

    /**
     * Starts an asynchronous Actor run via official POST /v2/acts/:actorId/runs.
     * Uses ActorIdentifierResolver to guarantee valid URL path construction (username~actor-name or actorId).
     */
    suspend fun startActorRun(
        actorId: String,
        inputJson: JsonObject,
        timeoutSecs: Long? = null,
        memoryMbytes: Long? = null
    ): Result<ApifyRunData> = withContext(Dispatchers.IO) {
        val canonicalId = ActorIdentifierResolver.toCanonicalApiId(actorId)
        if (canonicalId.isBlank()) {
            return@withContext Result.failure(SpyError.ActorStartFailed(actorId, "Invalid Actor identifier"))
        }

        val token = apiTokenProvider()?.trim()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(SpyError.ConfigurationMissing())
        }

        try {
            val response: HttpResponse = httpClient.post("$BASE_URL/acts/$canonicalId/runs") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $token")
                }
                contentType(ContentType.Application.Json)
                if (timeoutSecs != null) parameter("timeout", timeoutSecs)
                if (memoryMbytes != null) parameter("memory", memoryMbytes)
                setBody(inputJson.toString())
            }

            val text = response.bodyAsText()

            if (response.status == HttpStatusCode.NotFound) {
                val err = parseApifyError(response, text, "POST /v2/acts/$canonicalId/runs")
                return@withContext Result.failure(SpyError.ActorNotFound("$canonicalId ($err)"))
            }
            if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
                val err = parseApifyError(response, text, "POST /v2/acts/$canonicalId/runs")
                return@withContext Result.failure(SpyError.Unauthorized("Apify authorization failed for Actor '$canonicalId': $err"))
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                return@withContext Result.failure(SpyError.RateLimited())
            }
            if (!response.status.isSuccess() && response.status != HttpStatusCode.Created) {
                val err = parseApifyError(response, text, "POST /v2/acts/$canonicalId/runs")
                return@withContext Result.failure(SpyError.ActorStartFailed(canonicalId, err))
            }

            val runResp = json.decodeFromString<ApifyRunResponse>(text)
            val data = runResp.data ?: return@withContext Result.failure(SpyError.ActorStartFailed(canonicalId, "No run data returned in response"))
            Result.success(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Actor run for $canonicalId: ${e.message}")
            Result.failure(SpyError.NetworkError(e.message ?: "Network error", e))
        }
    }

    /**
     * Retrieves run status via GET /v2/actor-runs/:runId.
     */
    suspend fun getRunStatus(runId: String): Result<ApifyRunData> = withContext(Dispatchers.IO) {
        val cleanRunId = runId.trim()
        val token = apiTokenProvider()?.trim()
        if (token.isNullOrBlank()) {
            return@withContext Result.failure(SpyError.ConfigurationMissing())
        }

        try {
            val response: HttpResponse = httpClient.get("$BASE_URL/actor-runs/$cleanRunId") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $token")
                }
            }

            val text = response.bodyAsText()

            if (!response.status.isSuccess()) {
                val err = parseApifyError(response, text, "GET /v2/actor-runs/$cleanRunId")
                return@withContext Result.failure(SpyError.NetworkError("Failed to fetch run status: $err"))
            }

            val runResp = json.decodeFromString<ApifyRunResponse>(text)
            val data = runResp.data ?: return@withContext Result.failure(SpyError.NetworkError("No run data returned"))
            Result.success(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch run status for $cleanRunId: ${e.message}")
            Result.failure(SpyError.NetworkError(e.message ?: "Network error", e))
        }
    }

    /**
     * Aborts an ongoing run via POST /v2/actor-runs/:runId/abort.
     */
    suspend fun abortRun(runId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val cleanRunId = runId.trim()
        val token = apiTokenProvider()?.trim() ?: return@withContext Result.success(false)
        try {
            val response: HttpResponse = httpClient.post("$BASE_URL/actor-runs/$cleanRunId/abort") {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $token")
                }
            }
            Result.success(response.status.isSuccess())
        } catch (e: Exception) {
            Log.w(TAG, "Failed aborting run $cleanRunId: ${e.message}")
            Result.success(false)
        }
    }

    /**
     * Retrieves Dataset items via GET /v2/datasets/:datasetId/items.
     */
    suspend fun getDatasetItems(
        datasetId: String,
        limit: Int = 20,
        offset: Int = 0,
        clean: Boolean = true
    ): Result<JsonArray> = withContext(Dispatchers.IO) {
        val cleanDatasetId = datasetId.trim()
        val token = apiTokenProvider()?.trim()
        try {
            val response: HttpResponse = httpClient.get("$BASE_URL/datasets/$cleanDatasetId/items") {
                headers {
                    if (!token.isNullOrBlank()) {
                        append(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
                parameter("format", "json")
                parameter("limit", limit)
                parameter("offset", offset)
                parameter("clean", if (clean) "true" else "false")
            }

            val text = response.bodyAsText()

            if (!response.status.isSuccess()) {
                val err = parseApifyError(response, text, "GET /v2/datasets/$cleanDatasetId/items")
                return@withContext Result.failure(SpyError.DatasetReadFailed(cleanDatasetId, err))
            }

            val parsed = json.parseToJsonElement(text)
            if (parsed is JsonArray) {
                Result.success(parsed)
            } else if (parsed is JsonObject && parsed.containsKey("items")) {
                Result.success(parsed["items"]?.jsonArray ?: JsonArray(emptyList()))
            } else {
                Result.success(JsonArray(listOf(parsed)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading dataset $cleanDatasetId: ${e.message}")
            Result.failure(SpyError.DatasetReadFailed(cleanDatasetId, e.message ?: "JSON parse error"))
        }
    }

    /**
     * Retrieves a Key-Value Store record via GET /v2/key-value-stores/:storeId/records/:recordKey.
     */
    suspend fun getKeyValueRecord(
        storeId: String,
        recordKey: String = "OUTPUT"
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanStoreId = storeId.trim()
        val cleanKey = recordKey.trim()
        val token = apiTokenProvider()?.trim()
        try {
            val response: HttpResponse = httpClient.get("$BASE_URL/key-value-stores/$cleanStoreId/records/$cleanKey") {
                headers {
                    if (!token.isNullOrBlank()) {
                        append(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
            }

            val text = response.bodyAsText()

            if (response.status == HttpStatusCode.NotFound) {
                return@withContext Result.failure(SpyError.KeyValueReadFailed(cleanStoreId, cleanKey, "Record '$cleanKey' not found in store '$cleanStoreId'"))
            }

            if (!response.status.isSuccess()) {
                val err = parseApifyError(response, text, "GET /v2/key-value-stores/$cleanStoreId/records/$cleanKey")
                return@withContext Result.failure(SpyError.KeyValueReadFailed(cleanStoreId, cleanKey, err))
            }

            Result.success(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading KV record $cleanKey from store $cleanStoreId: ${e.message}")
            Result.failure(SpyError.KeyValueReadFailed(cleanStoreId, cleanKey, e.message ?: "Network error"))
        }
    }
}

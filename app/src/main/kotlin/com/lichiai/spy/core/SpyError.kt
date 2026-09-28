package com.lichiai.spy.core

sealed class SpyError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ConfigurationMissing(msg: String = "Platform Intelligence (#Spy) is not enabled or Apify API Token is missing. Please configure it in Settings.") : SpyError(msg)
    class InvalidApiToken(msg: String = "Apify API Token is invalid or unauthorized.") : SpyError(msg)
    class Unauthorized(msg: String = "Unauthorized request to Apify API.") : SpyError(msg)
    class Forbidden(msg: String = "Access forbidden. Check your Apify subscription or permissions.") : SpyError(msg)
    class ActorNotFound(val actorName: String) : SpyError("No suitable Apify Actor found for target '$actorName'.")
    class NoCompatibleActor(val platform: String, val operation: String) : SpyError("No compatible Actor found in Store for $platform ($operation).")
    class ActorUnavailable(val actorId: String) : SpyError("Actor '$actorId' is currently unavailable.")
    class ActorSchemaUnavailable(val actorId: String) : SpyError("Actor '$actorId' schema could not be retrieved.")
    class ActorInputInvalid(val reason: String) : SpyError("Generated input for Actor failed validation: $reason")
    class ActorStartFailed(val actorId: String, val details: String) : SpyError("Failed to start Actor '$actorId': $details")
    class ActorRunFailed(val runId: String, val status: String, val exitCode: Int?) : SpyError("Actor run '$runId' failed with status '$status' (exitCode: $exitCode).")
    class ActorTimedOut(val runId: String, val timeoutSeconds: Long) : SpyError("Actor run '$runId' timed out after $timeoutSeconds seconds.")
    class DatasetReadFailed(val datasetId: String, val details: String) : SpyError("Failed to retrieve dataset items from dataset '$datasetId': $details")
    class KeyValueReadFailed(val storeId: String, val recordKey: String, val details: String) : SpyError("Failed to retrieve record '$recordKey' from KV store '$storeId': $details")
    class RateLimited(val retryAfterSeconds: Int = 5) : SpyError("Apify API rate limit exceeded. Retry after $retryAfterSeconds seconds.")
    class NetworkError(msg: String, cause: Throwable? = null) : SpyError("Network error contacting Apify API: $msg", cause)
    class PaidActorRequiresConfirmation(val actorId: String, val pricingModel: String) : SpyError("Actor '$actorId' requires a paid subscription ($pricingModel). Free-First policy requires user confirmation.")
    class UnsupportedOperation(val operation: String) : SpyError("Operation '$operation' is currently unsupported.")
    class ResultUnavailable(val reason: String) : SpyError("Requested public data was not found in the platform results: $reason")
    class VerificationFailed(val reason: String) : SpyError("Result verification failed: $reason")
}

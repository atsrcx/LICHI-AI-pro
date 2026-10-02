package com.lichiai.spy.orchestrator

import android.content.Context
import android.util.Log
import com.lichiai.api.LlmClient
import com.lichiai.calling.contacts.PhoneNumberNormalizer
import com.lichiai.data.ProviderConfig
import com.lichiai.data.SettingsRepository
import com.lichiai.spy.apify.ActorIdentifierResolver
import com.lichiai.spy.apify.ApifyClient
import com.lichiai.spy.contact.PublicContactLookupService
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyCapability
import com.lichiai.spy.core.SpyError
import com.lichiai.spy.core.SpyGate
import com.lichiai.spy.core.SpyGateResult
import com.lichiai.spy.core.SpyLookupMode
import com.lichiai.spy.core.SpyOperation
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.core.TargetType
import com.lichiai.spy.discovery.ActorMetadata
import com.lichiai.spy.discovery.PlatformCapabilityInferencer
import com.lichiai.spy.interpreter.ActorInputBuilder
import com.lichiai.spy.interpreter.SpyIntentParser
import com.lichiai.spy.model.PlatformProfile
import com.lichiai.spy.normalizer.NormalizedEntity
import com.lichiai.spy.normalizer.SpyResultNormalizer
import com.lichiai.spy.normalizer.SpyResultVerifier
import com.lichiai.spy.normalizer.VerificationResult
import com.lichiai.spy.registry.SpyProviderEntity
import com.lichiai.spy.registry.SpyProviderRepository
import com.lichiai.ui.spy.SpyProfileSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import java.util.Locale

enum class SpyTaskStatus {
    DISCOVERING_ACTOR,
    ACTOR_SELECTED,
    STARTING,
    RUNNING,
    READING_RESULTS,
    VERIFYING_RESULT,
    COMPLETED,
    FAILED,
    NO_RESULT
}

data class SpyExecutionResult(
    val speech: String,
    val isSuccess: Boolean,
    val status: SpyTaskStatus = SpyTaskStatus.COMPLETED,
    val task: SpyTask? = null,
    val actor: ActorMetadata? = null,
    val primaryProfile: PlatformProfile? = null,
    val profiles: List<PlatformProfile> = emptyList(),
    val normalizedData: List<NormalizedEntity> = emptyList(),
    val rawJsonSnippet: String = "",
    val errorMessage: String? = null,
    val reportId: String? = null,
    val executedProviders: List<String> = emptyList(),
    val partialFailure: Boolean = false,
    val mode: SpyLookupMode = SpyLookupMode.SINGLE
)

/**
 * Production-grade runtime orchestrator for Lichi #Spy Platform Intelligence.
 *
 * Architecture:
 * - Local Provider Registry is the execution authority (Hot path NEVER performs remote discovery, schema fetches, or verifyToken calls).
 * - SINGLE Mode: Deterministically selects and executes exactly ONE enabled compatible provider.
 * - FULL Mode: Concurrently executes ALL enabled compatible providers, isolates failures, aggregates observations,
 *   preserves provenance and conflicts, and generates an HTML intelligence report.
 */
class SpyRuntimeOrchestrator(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val llmClient: LlmClient
) {
    companion object {
        private const val TAG = "SpyOrchestrator"
        private const val MAX_RUN_TIME_MS = 120_000L
    }

    private val apifyClient = ApifyClient {
        kotlinx.coroutines.runBlocking {
            settingsRepository.settings.first().apifyApiToken
        }
    }

    private val providerRepository = SpyProviderRepository.getInstance(context, apifyClient)

    suspend fun execute(
        rawInput: String,
        provider: ProviderConfig? = null,
        modelId: String? = null,
        requestId: String = "",
        messageId: String = "",
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)? = null
    ): SpyExecutionResult = withContext(Dispatchers.IO) {
        val triggerResult = SpyGate.checkTrigger(rawInput)
        if (triggerResult !is SpyGateResult.Triggered) {
            return@withContext SpyExecutionResult(
                speech = "Request is not a #Spy command.",
                isSuccess = false,
                status = SpyTaskStatus.FAILED,
                errorMessage = "SpyGate not triggered"
            )
        }

        val cleanQuery = triggerResult.cleanQuery

        // Step 1: Language-aware entity extraction and structured task creation
        onProgress?.invoke(1, 6, "Parsing request...")
        val task = SpyIntentParser.parse(cleanQuery, requestId = requestId, messageId = messageId)
        val maskedTarget = if (task.targetType == TargetType.PHONE_NUMBER) {
            PhoneNumberNormalizer.maskPhoneNumber(task.target)
        } else task.target

        Log.i(TAG, "Parsed Spy task: platform=${task.platform}, dynamicRef=${task.dynamicPlatformRef?.key}, mode=${task.lookupMode}, op=${task.operation}, target='$maskedTarget'")

        // Step 2: Public Phone Lookup special route (when platform is UNKNOWN)
        if (task.targetType == TargetType.PHONE_NUMBER && (task.platform == PlatformType.UNKNOWN || task.platform == PlatformType.GENERIC_WEB)) {
            onProgress?.invoke(2, 6, "Contact type: Public Phone Number")
            onProgress?.invoke(3, 6, "Target identified: $maskedTarget")
            return@withContext PublicContactLookupService.execute(task, onProgress)
        }

        val appSettings = settingsRepository.settings.first()
        val token = appSettings.apifyApiToken.trim()

        if (token.isBlank()) {
            return@withContext SpyExecutionResult(
                speech = "🔒 **Platform Intelligence Configuration Required**\n\nPlatform Intelligence (#Spy) requires an API execution token.\n\nPlease go to **Settings > Platform Intelligence (#Spy)** and configure your token.",
                isSuccess = false,
                status = SpyTaskStatus.FAILED,
                errorMessage = "Missing API token"
            )
        }

        if (task.target.isBlank()) {
            val platformLabel = task.dynamicPlatformRef?.displayName ?: task.platform.displayName
            return@withContext SpyExecutionResult(
                speech = "⚠️ Could not identify a valid target or username in your request for $platformLabel.\n\nPlease provide a username, handle (e.g. `@username`), or profile link.",
                isSuccess = false,
                status = SpyTaskStatus.FAILED,
                task = task,
                errorMessage = "Blank target extracted"
            )
        }

        val platformLabel = task.dynamicPlatformRef?.displayName ?: task.platform.displayName
        onProgress?.invoke(2, 6, "Platform identified: $platformLabel (Mode: ${task.lookupMode})")
        onProgress?.invoke(3, 6, "Target identified: ${task.target}")

        // Step 3: Load enabled compatible providers from local Room Registry (Hot Path)
        val enabledProviders = providerRepository.getEnabledProviders()
        val platformKey = task.dynamicPlatformRef?.key ?: task.platform.id

        val compatibleProviders = filterCompatibleProviders(enabledProviders, platformKey, task)

        if (compatibleProviders.isEmpty()) {
            Log.w(TAG, "No enabled compatible providers found for platform '$platformKey'")
            return@withContext SpyExecutionResult(
                speech = "⚠️ **No Enabled Provider Configured**\n\nNo enabled compatible provider is configured for **$platformLabel**.\n\nPlease go to **Settings > Platform Intelligence (#Spy)** to search and enable providers.",
                isSuccess = false,
                status = SpyTaskStatus.NO_RESULT,
                task = task,
                errorMessage = "No enabled compatible provider configured"
            )
        }

        // Step 4: Branch execution based on mode (SINGLE vs FULL)
        return@withContext when (task.lookupMode) {
            SpyLookupMode.SINGLE -> executeSingleMode(task, compatibleProviders, appSettings.spyTimeoutSeconds, appSettings.spyMaxDatasetItems, onProgress)
            SpyLookupMode.FULL -> executeFullMode(task, compatibleProviders, appSettings.spyTimeoutSeconds, appSettings.spyMaxDatasetItems, onProgress)
        }
    }

    /**
     * SINGLE MODE: Executes exactly ONE deterministically selected provider. No hidden fallbacks.
     */
    private suspend fun executeSingleMode(
        task: SpyTask,
        candidates: List<SpyProviderEntity>,
        timeoutSecs: Long,
        maxItems: Int,
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)?
    ): SpyExecutionResult {
        val selectedProvider = selectBestSingleProvider(candidates, task)
        val canonicalId = selectedProvider.canonicalActorId
        onProgress?.invoke(4, 6, "Executing provider: ${selectedProvider.title}")

        val executionResult = executeSingleProviderRun(selectedProvider, task, timeoutSecs, maxItems, onProgress)

        // Record health stats
        providerRepository.recordExecution(
            providerId = canonicalId,
            isSuccess = executionResult.isSuccess,
            latencyMs = executionResult.latencyMs
        )

        if (!executionResult.isSuccess) {
            val err = executionResult.error ?: "Provider execution failed"
            return SpyExecutionResult(
                speech = "❌ **Provider Execution Failed**\n\nProvider **${selectedProvider.title}** failed to retrieve verified data.\n\nDetails: $err",
                isSuccess = false,
                status = SpyTaskStatus.FAILED,
                task = task,
                errorMessage = err,
                executedProviders = listOf(canonicalId),
                mode = SpyLookupMode.SINGLE
            )
        }

        val verifiedEntities = executionResult.verifiedEntities
        if (verifiedEntities.isEmpty()) {
            return SpyExecutionResult(
                speech = "ℹ️ **No Public Profile Data Found**\n\nNo verified public profile data was returned for **${task.target}** on ${task.platform.displayName}.\n\nPossible causes:\n• Account is private or restricted\n• Account does not exist\n• Platform rate limited anonymous requests",
                isSuccess = false,
                status = SpyTaskStatus.NO_RESULT,
                task = task,
                executedProviders = listOf(canonicalId),
                mode = SpyLookupMode.SINGLE
            )
        }

        val profiles = verifiedEntities.map { it.toPlatformProfile(task.platform, canonicalId).copy(previewRequested = task.previewRequested) }
        val primaryProfile = profiles.firstOrNull()

        val synthesizedText = formatIntelligenceReport(task, profiles)
        val embeddedSpeech = if (primaryProfile != null) {
            SpyProfileSerializer.embedProfile(primaryProfile, synthesizedText)
        } else synthesizedText

        return SpyExecutionResult(
            speech = embeddedSpeech,
            isSuccess = true,
            status = SpyTaskStatus.COMPLETED,
            task = task,
            primaryProfile = primaryProfile,
            profiles = profiles,
            normalizedData = verifiedEntities,
            rawJsonSnippet = executionResult.rawSnippet,
            executedProviders = listOf(canonicalId),
            mode = SpyLookupMode.SINGLE
        )
    }

    /**
     * FULL MODE: Executes ALL enabled compatible providers concurrently.
     * Isolates provider failures, aggregates observations, detects conflicts, and generates an HTML report.
     */
    private suspend fun executeFullMode(
        task: SpyTask,
        candidates: List<SpyProviderEntity>,
        timeoutSecs: Long,
        maxItems: Int,
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)?
    ): SpyExecutionResult = coroutineScope {
        onProgress?.invoke(4, 6, "Running ${candidates.size} enabled providers concurrently...")

        val deferredResults = candidates.map { provider ->
            async {
                val res = executeSingleProviderRun(provider, task, timeoutSecs, maxItems, null)
                providerRepository.recordExecution(
                    providerId = provider.canonicalActorId,
                    isSuccess = res.isSuccess,
                    latencyMs = res.latencyMs
                )
                provider to res
            }
        }

        val completedRuns = deferredResults.awaitAll()

        val providerStats = mutableListOf<ProviderExecutionStats>()
        val allVerifiedEntities = mutableListOf<NormalizedEntity>()
        var combinedRawSnippets = ""

        for ((provider, runResult) in completedRuns) {
            val stat = ProviderExecutionStats(
                providerId = provider.canonicalActorId,
                providerName = provider.title,
                status = if (runResult.isSuccess) "SUCCESS" else "FAILED",
                latencyMs = runResult.latencyMs,
                recordCount = runResult.verifiedEntities.size,
                error = runResult.error
            )
            providerStats.add(stat)

            if (runResult.isSuccess) {
                allVerifiedEntities.addAll(runResult.verifiedEntities)
                if (runResult.rawSnippet.isNotBlank() && combinedRawSnippets.length < 2000) {
                    combinedRawSnippets += "\n" + runResult.rawSnippet
                }
            }
        }

        val executedProviderIds = candidates.map { it.canonicalActorId }
        val hasAnySuccess = allVerifiedEntities.isNotEmpty()

        if (!hasAnySuccess) {
            val errorSummary = completedRuns.mapNotNull { it.second.error }.take(3).joinToString("; ")
            return@coroutineScope SpyExecutionResult(
                speech = "❌ **All Providers Failed in FULL Mode**\n\nExecuted ${candidates.size} providers, but none returned verified public data.\n\nDetails: ${errorSummary.ifBlank { "No verified data returned" }}",
                isSuccess = false,
                status = SpyTaskStatus.FAILED,
                task = task,
                executedProviders = executedProviderIds,
                mode = SpyLookupMode.FULL
            )
        }

        onProgress?.invoke(6, 6, "Synthesizing multi-source intelligence report...")

        // Aggregate and merge multi-provider observations
        val unifiedProfile = SpyEvidenceMerger.mergeEntities(allVerifiedEntities, task, executedProviderIds)

        // Generate HTML Report
        val htmlReport = SpyHtmlReportRenderer.renderHtml(
            task = task,
            profile = unifiedProfile,
            providerStats = providerStats,
            conflicts = unifiedProfile.conflicts
        )

        val reportId = SpyReportStore.saveReport(context, htmlReport)
        val profileWithReport = unifiedProfile.copy(reportId = reportId, previewRequested = task.previewRequested)

        val synthesizedText = formatFullModeIntelligenceReport(task, profileWithReport, providerStats)
        val embeddedSpeech = SpyProfileSerializer.embedProfile(profileWithReport, synthesizedText)

        SpyExecutionResult(
            speech = embeddedSpeech,
            isSuccess = true,
            status = SpyTaskStatus.COMPLETED,
            task = task,
            primaryProfile = profileWithReport,
            profiles = listOf(profileWithReport),
            normalizedData = allVerifiedEntities,
            rawJsonSnippet = combinedRawSnippets.take(1500),
            reportId = reportId,
            executedProviders = executedProviderIds,
            partialFailure = providerStats.any { it.status == "FAILED" },
            mode = SpyLookupMode.FULL
        )
    }

    private data class SingleRunOutcome(
        val isSuccess: Boolean,
        val verifiedEntities: List<NormalizedEntity> = emptyList(),
        val rawSnippet: String = "",
        val latencyMs: Long = 0,
        val error: String? = null
    )

    private suspend fun executeSingleProviderRun(
        provider: SpyProviderEntity,
        task: SpyTask,
        timeoutSecs: Long,
        maxItems: Int,
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)?
    ): SingleRunOutcome {
        val startTime = System.currentTimeMillis()
        val canonicalId = provider.canonicalActorId

        return try {
            val payload = ActorInputBuilder.buildInput(task, provider)
            Log.i(TAG, "Run payload for $canonicalId: $payload")

            val startResult = apifyClient.startActorRun(
                actorId = canonicalId,
                inputJson = payload,
                timeoutSecs = timeoutSecs
            )

            if (startResult.isFailure) {
                val err = startResult.exceptionOrNull()?.message ?: "Failed to start Actor run"
                return SingleRunOutcome(
                    isSuccess = false,
                    latencyMs = System.currentTimeMillis() - startTime,
                    error = err
                )
            }

            val runData = startResult.getOrThrow()
            val runId = runData.id

            // Adaptive polling
            val (pollSuccess, datasetId, kvId, statusError) = pollRunStatus(runId, startTime, onProgress)

            if (!pollSuccess) {
                return SingleRunOutcome(
                    isSuccess = false,
                    latencyMs = System.currentTimeMillis() - startTime,
                    error = statusError ?: "Actor run did not succeed"
                )
            }

            // Retrieve Dataset items or KV output
            val rawItems = if (!datasetId.isNullOrBlank()) {
                apifyClient.getDatasetItems(datasetId, limit = maxItems).getOrNull()
            } else null

            val kvOutput = if ((rawItems == null || rawItems.isEmpty()) && !kvId.isNullOrBlank()) {
                apifyClient.getKeyValueRecord(kvId, "OUTPUT").getOrNull()
            } else null

            val latencyMs = System.currentTimeMillis() - startTime

            if (rawItems != null && rawItems.isNotEmpty()) {
                val normalizedList = SpyResultNormalizer.normalize(rawItems, task, providerId = canonicalId)
                val verifiedList = normalizedList.filter { entity ->
                    val vResult = SpyResultVerifier.verifyEntity(entity, task)
                    vResult is VerificationResult.Verified
                }

                if (verifiedList.isNotEmpty()) {
                    SingleRunOutcome(
                        isSuccess = true,
                        verifiedEntities = verifiedList,
                        rawSnippet = rawItems.toString().take(1000),
                        latencyMs = latencyMs
                    )
                } else {
                    SingleRunOutcome(
                        isSuccess = false,
                        latencyMs = latencyMs,
                        error = "Returned records failed target verification"
                    )
                }
            } else if (!kvOutput.isNullOrBlank() && !kvOutput.trim().startsWith("[]")) {
                SingleRunOutcome(
                    isSuccess = true,
                    verifiedEntities = listOf(
                        NormalizedEntity(
                            title = task.target,
                            identifier = task.target,
                            bioOrDescription = kvOutput.take(300),
                            rawJsonSnippet = kvOutput.take(500),
                            providerId = canonicalId
                        )
                    ),
                    rawSnippet = kvOutput.take(1000),
                    latencyMs = latencyMs
                )
            } else {
                SingleRunOutcome(
                    isSuccess = false,
                    latencyMs = latencyMs,
                    error = "No dataset items or KV output returned"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing provider $canonicalId: ${e.message}", e)
            SingleRunOutcome(
                isSuccess = false,
                latencyMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Execution error"
            )
        }
    }

    private data class PollResult(
        val isSuccess: Boolean,
        val datasetId: String?,
        val kvStoreId: String?,
        val error: String?
    )

    private suspend fun pollRunStatus(
        runId: String,
        startTime: Long,
        onProgress: ((step: Int, total: Int, statusText: String) -> Unit)?
    ): PollResult {
        var delayMs = 350L
        var finalDatasetId: String? = null
        var finalKvId: String? = null

        while ((System.currentTimeMillis() - startTime) < MAX_RUN_TIME_MS) {
            val elapsedSecs = (System.currentTimeMillis() - startTime) / 1000
            onProgress?.invoke(5, 6, "Retrieving public data (${elapsedSecs}s)...")
            delay(delayMs)
            delayMs = (delayMs + 350L).coerceAtMost(1500L) // Adaptive backoff

            val statusResult = apifyClient.getRunStatus(runId)
            if (statusResult.isSuccess) {
                val runData = statusResult.getOrThrow()
                finalDatasetId = runData.defaultDatasetId ?: finalDatasetId
                finalKvId = runData.defaultKeyValueStoreId ?: finalKvId

                when (runData.status.uppercase(Locale.ROOT)) {
                    "SUCCEEDED" -> {
                        return PollResult(true, finalDatasetId, finalKvId, null)
                    }
                    "FAILED", "ABORTED", "TIMED-OUT" -> {
                        return PollResult(false, finalDatasetId, finalKvId, "Run ended with status: ${runData.status}")
                    }
                }
            }
        }

        return PollResult(false, finalDatasetId, finalKvId, "Execution timed out")
    }

    private fun filterCompatibleProviders(
        providers: List<SpyProviderEntity>,
        platformKey: String,
        task: SpyTask
    ): List<SpyProviderEntity> {
        val cleanKey = platformKey.lowercase(Locale.ROOT)
        return providers.filter { entity ->
            val matchesPlatform = entity.platformKeys.any { it.equals(cleanKey, ignoreCase = true) }
                || entity.actorName.contains(cleanKey, ignoreCase = true)
                || entity.title.contains(cleanKey, ignoreCase = true)
                || entity.platformKeys.contains("web")
            val hasSchema = entity.hasValidSchema()
            val isRunnable = entity.runnableState != "UNRUNNABLE"
            matchesPlatform && hasSchema && isRunnable
        }
    }

    private fun selectBestSingleProvider(
        candidates: List<SpyProviderEntity>,
        task: SpyTask
    ): SpyProviderEntity {
        val platformKey = task.dynamicPlatformRef?.key ?: task.platform.id

        return candidates.maxWithOrNull(
            compareBy<SpyProviderEntity> { entity ->
                // Exact platform key match
                if (entity.platformKeys.contains(platformKey)) 100 else 50
            }.thenBy { entity ->
                // Success count and rate
                val total = entity.successCount + entity.failureCount
                if (total > 0) (entity.successCount.toDouble() / total) * 50.0 else 25.0
            }.thenByDescending { entity ->
                // Latency (lower is better)
                if (entity.averageLatencyMs > 0) -entity.averageLatencyMs else 0
            }.thenBy { entity ->
                entity.selectionPriority
            }.thenBy { entity ->
                entity.canonicalActorId
            }
        ) ?: candidates.first()
    }

    private fun formatIntelligenceReport(
        task: SpyTask,
        profiles: List<PlatformProfile>
    ): String {
        val sb = StringBuilder()
        val platformName = task.dynamicPlatformRef?.displayName ?: task.platform.displayName

        if (task.operation == SpyOperation.PUBLIC_EMAIL_LOOKUP) {
            val email = profiles.firstNotNullOfOrNull { it.publicEmail.takeIf { e -> e.isNotBlank() } }
            sb.append("📧 **Public Email Intelligence: $platformName**\n\n")
            if (!email.isNullOrBlank()) {
                sb.append("• **Email:** `$email`\n")
                sb.append("• **Type:** Public Business Contact\n")
                sb.append("• **Associated Profile:** @${profiles.first().username}\n")
                sb.append("• **Verification:** Confirmed in public profile\n")
            } else {
                sb.append("• No publicly exposed business email was found on @${task.target}'s public profile.\n")
            }
            return sb.toString().trim()
        }

        if (task.operation == SpyOperation.PUBLIC_PHONE_LOOKUP) {
            val phone = profiles.firstNotNullOfOrNull { it.publicPhone.takeIf { p -> p.isNotBlank() } }
            sb.append("📱 **Public Phone Intelligence: $platformName**\n\n")
            if (!phone.isNullOrBlank()) {
                sb.append("• **Phone:** `$phone`\n")
                sb.append("• **Type:** Public Business Contact\n")
                sb.append("• **Associated Profile:** @${profiles.first().username}\n")
                sb.append("• **Verification:** Confirmed in public profile\n")
            } else {
                sb.append("• No publicly exposed business phone number was found on @${task.target}'s public profile.\n")
            }
            return sb.toString().trim()
        }

        for ((index, profile) in profiles.withIndex()) {
            if (profiles.size > 1) {
                sb.append("🔎 **$platformName Profile #${index + 1}**\n\n")
            } else {
                sb.append("🔎 **$platformName Profile Found**\n\n")
            }

            val usernameDisplay = if (profile.username.isNotBlank()) profile.username else task.target
            sb.append("• **Username:** `@$usernameDisplay`\n")

            if (profile.displayName.isNotBlank() && profile.displayName != profile.username) {
                sb.append("• **Full Name:** ${profile.displayName}\n")
            }

            if (profile.isVerified != null) {
                sb.append("• **Verified:** ${if (profile.isVerified) "✅ Yes" else "No"}\n")
            }

            if (profile.isPrivate != null) {
                sb.append("• **Account Type:** ${if (profile.isPrivate) "🔒 Private" else "🌐 Public"}\n")
            }

            if (profile.category.isNotBlank()) {
                sb.append("• **Category:** ${profile.category}\n")
            }

            if (profile.followers.isNotBlank()) {
                sb.append("• **Followers:** ${profile.followers}\n")
            }
            if (profile.following.isNotBlank()) {
                sb.append("• **Following:** ${profile.following}\n")
            }
            if (profile.postCount.isNotBlank()) {
                sb.append("• **Posts / Media:** ${profile.postCount}\n")
            }
            if (profile.subscriberCount.isNotBlank()) {
                sb.append("• **Subscribers:** ${profile.subscriberCount}\n")
            }
            if (profile.views.isNotBlank()) {
                sb.append("• **Views / Score:** ${profile.views}\n")
            }

            if (profile.bio.isNotBlank()) {
                sb.append("• **Bio:** ${profile.bio}\n")
            }

            if (profile.website.isNotBlank()) {
                sb.append("• **Website:** ${profile.website}\n")
            }

            if (profile.publicEmail.isNotBlank()) {
                sb.append("• **Public Business Email:** `${profile.publicEmail}`\n")
            }

            if (profile.publicPhone.isNotBlank()) {
                sb.append("• **Public Business Phone:** `${profile.publicPhone}`\n")
            }

            if (profile.highlights.isNotEmpty()) {
                sb.append("\n**Recent Highlights:**\n")
                profile.highlights.take(3).forEach { h ->
                    sb.append("• \"$h\"\n")
                }
            }

            sb.append("\n")
        }

        return sb.toString().trim()
    }

    private fun formatFullModeIntelligenceReport(
        task: SpyTask,
        profile: PlatformProfile,
        providerStats: List<ProviderExecutionStats>
    ): String {
        val sb = StringBuilder()
        val platformName = task.dynamicPlatformRef?.displayName ?: task.platform.displayName
        val successCount = providerStats.count { it.status == "SUCCESS" }
        val totalCount = providerStats.size

        sb.append("🛡️ **Platform Intelligence Aggregation (FULL Mode)**\n\n")
        sb.append("• **Platform:** $platformName\n")
        sb.append("• **Target:** `@${profile.username.ifBlank { task.target }}`\n")
        sb.append("• **Providers Executed:** $totalCount ($successCount succeeded)\n")
        sb.append("• **Confidence:** ${profile.sourceConfidence}\n")

        if (profile.conflicts.isNotEmpty()) {
            sb.append("• **Conflicts Detected:** ${profile.conflicts.size} differing observation(s)\n")
        }

        sb.append("\n")
        sb.append(formatIntelligenceReport(task, listOf(profile)))

        return sb.toString().trim()
    }
}

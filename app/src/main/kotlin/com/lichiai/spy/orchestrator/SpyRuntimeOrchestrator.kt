package com.lichiai.spy.orchestrator

import android.content.Context
import android.util.Log
import com.lichiai.api.LlmClient
import com.lichiai.data.AppSettings
import com.lichiai.data.ProviderConfig
import com.lichiai.data.SettingsRepository
import com.lichiai.spy.apify.ActorIdentifierResolver
import com.lichiai.spy.apify.ApifyClient
import com.lichiai.spy.model.PlatformCatalog
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyError
import com.lichiai.spy.core.SpyGate
import com.lichiai.spy.core.SpyGateResult
import com.lichiai.spy.core.SpyOperation
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.discovery.ActorDiscoveryService
import com.lichiai.spy.discovery.ActorMetadata
import com.lichiai.spy.interpreter.ActorInputBuilder
import com.lichiai.spy.interpreter.SpyIntentParser
import com.lichiai.spy.interpreter.TargetExtractor
import com.lichiai.spy.model.PlatformProfile
import com.lichiai.spy.model.PlatformSupportStatus
import com.lichiai.spy.normalizer.NormalizedEntity
import com.lichiai.spy.normalizer.SpyResultNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
    val errorMessage: String? = null
)

/**
 * Production-grade runtime orchestrator for Lichi #Spy Platform Intelligence V2.
 * Coordinates token verification, language-aware entity extraction, actor discovery & validation,
 * async run lifecycle, dataset/KV retrieval, 4-stage fact verification, and structured intelligence synthesis.
 * Strict zero-Apify branding in user output.
 */
class SpyRuntimeOrchestrator(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val llmClient: LlmClient
) {
    companion object {
        private const val TAG = "SpyOrchestrator"
        private const val POLL_INTERVAL_MS = 2500L
        private const val MAX_RUN_TIME_MS = 120_000L
    }

    private val apifyClient = ApifyClient {
        kotlinx.coroutines.runBlocking {
            settingsRepository.settings.first().apifyApiToken
        }
    }

    private val discoveryService = ActorDiscoveryService(apifyClient)

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

        // Step 1: Validate Token Upfront
        onProgress?.invoke(1, 6, "Understanding request...")
        val userCheck = apifyClient.verifyToken()
        if (userCheck.isFailure) {
            val err = userCheck.exceptionOrNull()?.message ?: "Invalid token"
            return@withContext SpyExecutionResult(
                speech = "🔒 **Authentication Failed**\n\nCould not authenticate with intelligence backend. Please verify your token in Settings.\n\nDetails: $err",
                isSuccess = false,
                status = SpyTaskStatus.FAILED,
                errorMessage = err
            )
        }

        val cleanQuery = triggerResult.cleanQuery

        // Step 2: Language-aware entity extraction and task creation
        val task = SpyIntentParser.parse(cleanQuery, requestId = requestId, messageId = messageId)
        Log.i(TAG, "Parsed Spy task: platform=${task.platform}, op=${task.operation}, target='${task.target}', fields=${task.requestedFields}")

        onProgress?.invoke(2, 6, "Platform identified: ${task.platform.displayName}")

        // 2.1 Check Platform Catalog Support Status
        val platformDef = PlatformCatalog.getDefinition(task.platform)
        if (platformDef.supportStatus == PlatformSupportStatus.UNSUPPORTED) {
            return@withContext SpyExecutionResult(
                speech = "⚠️ **Platform Unsupported**\n\nLICHI cannot retrieve ${task.platform.displayName}'s public profile data with the currently configured sources.",
                isSuccess = false,
                status = SpyTaskStatus.NO_RESULT,
                task = task,
                errorMessage = "Platform unsupported"
            )
        }

        // 2.2 Deterministic Target Validation
        if (task.target.isBlank()) {
            return@withContext SpyExecutionResult(
                speech = "⚠️ Could not identify a valid target or username in your request for ${task.platform.displayName}.\n\nPlease provide a username, handle (e.g. `@username`), or profile link.",
                isSuccess = false,
                status = SpyTaskStatus.FAILED,
                task = task,
                errorMessage = "Blank target extracted"
            )
        }

        onProgress?.invoke(3, 6, "Target identified: ${task.target}")

        // Step 3: Discover Actor / Source
        onProgress?.invoke(4, 6, "Finding and validating data source...")
        val actorResult = discoveryService.discoverBestActor(
            platform = task.platform,
            operation = task.operation,
            targetQuery = task.target,
            freeFirstOnly = appSettings.spyFreeFirstOnly
        )

        if (actorResult.isFailure) {
            val err = actorResult.exceptionOrNull()?.message ?: "Unknown error"
            return@withContext SpyExecutionResult(
                speech = "⚠️ No compatible data source found for ${task.platform.displayName}.\n\nError: $err",
                isSuccess = false,
                status = SpyTaskStatus.NO_RESULT,
                task = task,
                errorMessage = err
            )
        }

        var actor = actorResult.getOrThrow()
        val canonicalActorId = ActorIdentifierResolver.toCanonicalApiId(actor.actorId)
        Log.i(TAG, "Selected Actor: $canonicalActorId (${actor.title})")

        // Step 4: Validate Selected Actor
        val validationResult = apifyClient.validateActor(canonicalActorId)
        if (validationResult.isSuccess) {
            val detail = validationResult.getOrThrow()
            actor = actor.copy(
                actorId = canonicalActorId,
                title = detail.title.ifBlank { actor.title },
                description = detail.description.ifBlank { actor.description },
                readme = detail.readme ?: actor.readme,
                pricingModel = detail.pricingModel ?: actor.pricingModel,
                exampleInputJson = detail.exampleRunInput?.body ?: actor.exampleInputJson
            )
        } else {
            val validationError = validationResult.exceptionOrNull()?.message ?: "Actor validation failed"
            Log.w(TAG, "Actor validation warning for $canonicalActorId: $validationError")
            if (validationResult.exceptionOrNull() is SpyError.ActorNotFound) {
                return@withContext SpyExecutionResult(
                    speech = "⚠️ The requested source was not available for ${task.platform.displayName}.",
                    isSuccess = false,
                    status = SpyTaskStatus.FAILED,
                    task = task,
                    actor = actor,
                    errorMessage = validationError
                )
            }
        }

        // Step 5: Prepare Input & Start Run
        onProgress?.invoke(5, 6, "Retrieving public profile...")
        val actorInput = ActorInputBuilder.buildInput(task, actor)
        Log.i(TAG, "Run Payload for $canonicalActorId: $actorInput")

        val runStartResult = apifyClient.startActorRun(
            actorId = canonicalActorId,
            inputJson = actorInput,
            timeoutSecs = appSettings.spyTimeoutSeconds
        )

        if (runStartResult.isFailure) {
            val err = runStartResult.exceptionOrNull()?.message ?: "Failed to retrieve public data"
            return@withContext SpyExecutionResult(
                speech = "❌ Failed to retrieve public profile data for ${task.platform.displayName}.\n\nDetails: $err",
                isSuccess = false,
                status = SpyTaskStatus.FAILED,
                task = task,
                actor = actor,
                errorMessage = err
            )
        }

        val runData = runStartResult.getOrThrow()
        val runId = runData.id
        Log.i(TAG, "Run started: id=$runId, defaultDatasetId=${runData.defaultDatasetId}")

        // Step 6: Poll for run completion
        val startTime = System.currentTimeMillis()
        var finalDatasetId = runData.defaultDatasetId
        var finalKvStoreId = runData.defaultKeyValueStoreId
        var isFinished = false

        while (!isFinished && (System.currentTimeMillis() - startTime) < MAX_RUN_TIME_MS) {
            val elapsedSecs = (System.currentTimeMillis() - startTime) / 1000
            onProgress?.invoke(5, 6, "Processing profile data (${elapsedSecs}s)...")
            delay(POLL_INTERVAL_MS)

            val statusResult = apifyClient.getRunStatus(runId)
            if (statusResult.isSuccess) {
                val currentStatus = statusResult.getOrThrow()
                finalDatasetId = currentStatus.defaultDatasetId ?: finalDatasetId
                finalKvStoreId = currentStatus.defaultKeyValueStoreId ?: finalKvStoreId

                when (currentStatus.status.uppercase(Locale.ROOT)) {
                    "SUCCEEDED" -> {
                        isFinished = true
                    }
                    "FAILED", "ABORTED", "TIMED-OUT" -> {
                        return@withContext SpyExecutionResult(
                            speech = "❌ Profile lookup ended with status: ${currentStatus.status}.",
                            isSuccess = false,
                            status = SpyTaskStatus.FAILED,
                            task = task,
                            actor = actor,
                            errorMessage = "Run status: ${currentStatus.status}"
                        )
                    }
                }
            }
        }

        onProgress?.invoke(6, 6, "Building intelligence report...")

        // Step 7: Fetch dataset items or KV store records
        val rawItems = if (!finalDatasetId.isNullOrBlank()) {
            apifyClient.getDatasetItems(finalDatasetId, limit = appSettings.spyMaxDatasetItems).getOrNull()
        } else null

        val kvOutput = if ((rawItems == null || rawItems.isEmpty()) && !finalKvStoreId.isNullOrBlank()) {
            apifyClient.getKeyValueRecord(finalKvStoreId, "OUTPUT").getOrNull()
        } else null

        // Step 8: Fact Extraction & 4-Stage Verification
        if (rawItems == null || rawItems.isEmpty()) {
            if (!kvOutput.isNullOrBlank() && !kvOutput.trim().startsWith("[]")) {
                return@withContext SpyExecutionResult(
                    speech = "🕵️ **Lichi Platform Intelligence**\n\n```json\n${kvOutput.take(1500)}\n```",
                    isSuccess = true,
                    status = SpyTaskStatus.COMPLETED,
                    task = task,
                    actor = actor,
                    rawJsonSnippet = kvOutput.take(1500)
                )
            }

            return@withContext SpyExecutionResult(
                speech = "ℹ️ **No Public Profile Data Found**\n\nNo public data records were returned for **${task.target}** on ${task.platform.displayName}.\n\nPossible causes:\n• Account is private or restricted\n• Account does not exist\n• Platform rate limited anonymous requests",
                isSuccess = false,
                status = SpyTaskStatus.NO_RESULT,
                task = task,
                actor = actor
            )
        }

        val normalizedList = SpyResultNormalizer.normalize(rawItems, task)
        val verifiedEntities = normalizedList.filter { it.hasGenuineData() }
        val profiles = verifiedEntities.map { it.toPlatformProfile(task.platform).copy(previewRequested = task.previewRequested) }

        // If no entity contains genuine scraped facts
        if (verifiedEntities.isEmpty()) {
            val scraperError = normalizedList.firstOrNull { !it.scraperError.isNullOrBlank() }?.scraperError
            val errorDetails = if (!scraperError.isNullOrBlank()) "\n\nSource note: $scraperError" else ""
            return@withContext SpyExecutionResult(
                speech = "⚠️ **Verification Incomplete**\n\nA record for **${task.target}** was retrieved, but no public profile metrics (followers, bio, or name) could be verified.$errorDetails",
                isSuccess = false,
                status = SpyTaskStatus.NO_RESULT,
                task = task,
                actor = actor,
                normalizedData = normalizedList,
                rawJsonSnippet = rawItems.toString().take(1000)
            )
        }

        // Step 9: Format Structured Intelligence Report
        val primaryProfile = profiles.firstOrNull()
        val synthesizedText = formatIntelligenceReport(
            task = task,
            profiles = profiles
        )

        val embeddedSpeech = if (primaryProfile != null) {
            com.lichiai.ui.spy.SpyProfileSerializer.embedProfile(primaryProfile, synthesizedText)
        } else {
            synthesizedText
        }

        return@withContext SpyExecutionResult(
            speech = embeddedSpeech,
            isSuccess = true,
            status = SpyTaskStatus.COMPLETED,
            task = task,
            actor = actor,
            primaryProfile = primaryProfile,
            profiles = profiles,
            normalizedData = verifiedEntities,
            rawJsonSnippet = rawItems.toString().take(1000)
        )
    }

    private fun formatIntelligenceReport(
        task: SpyTask,
        profiles: List<PlatformProfile>
    ): String {
        val sb = StringBuilder()
        val platformName = task.platform.displayName

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

            val profileLink = if (profile.profileUrl.isNotBlank()) {
                profile.profileUrl
            } else when (task.platform) {
                PlatformType.INSTAGRAM -> "https://www.instagram.com/$usernameDisplay/"
                PlatformType.YOUTUBE -> "https://www.youtube.com/@$usernameDisplay"
                PlatformType.REDDIT -> "https://www.reddit.com/r/$usernameDisplay"
                PlatformType.TIKTOK -> "https://www.tiktok.com/@$usernameDisplay"
                PlatformType.TWITTER_X -> "https://twitter.com/$usernameDisplay"
                PlatformType.GITHUB -> "https://github.com/$usernameDisplay"
                else -> ""
            }

            if (profileLink.isNotBlank()) {
                sb.append("• **Profile Link:** $profileLink\n")
            }

            if (profile.highlights.isNotEmpty()) {
                sb.append("\n**Recent Highlights:**\n")
                profile.highlights.forEach { h ->
                    sb.append("• \"$h\"\n")
                }
            }

            sb.append("\n")
        }

        return sb.toString().trim()
    }
}

package com.lichiai.spy

import com.lichiai.calling.intent.CallAction
import com.lichiai.calling.intent.CallActionIntentResolver
import com.lichiai.spy.apify.ActorIdentifierResolver
import com.lichiai.spy.contact.PublicContactLookupService
import com.lichiai.spy.core.PlatformType
import com.lichiai.spy.core.SpyGate
import com.lichiai.spy.core.SpyGateResult
import com.lichiai.spy.core.SpyOperation
import com.lichiai.spy.core.SpyTask
import com.lichiai.spy.core.TargetType
import com.lichiai.spy.discovery.ActorMetadata
import com.lichiai.spy.interpreter.ActorInputBuilder
import com.lichiai.spy.interpreter.SpyIntentParser
import com.lichiai.spy.interpreter.TargetExtractor
import com.lichiai.spy.model.PlatformCatalog
import com.lichiai.spy.model.PlatformProfile
import com.lichiai.spy.model.PlatformSupportStatus
import com.lichiai.spy.normalizer.SpyResultNormalizer
import com.lichiai.ui.spy.SpyProfileSerializer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpyTests {

    @Test
    fun testActorIdentifierResolver_Formatting() {
        assertEquals(
            "apify~instagram-profile-scraper",
            ActorIdentifierResolver.toCanonicalApiId("apify/instagram-profile-scraper")
        )
        assertEquals(
            "apify~instagram-profile-scraper",
            ActorIdentifierResolver.toCanonicalApiId("apify~instagram-profile-scraper")
        )
        assertEquals(
            "apify~instagram-profile-scraper",
            ActorIdentifierResolver.toCanonicalApiId("https://apify.com/apify/instagram-profile-scraper")
        )
        assertEquals(
            "apify~instagram-profile-scraper",
            ActorIdentifierResolver.toCanonicalApiId("https://apify.com/apify/instagram-profile-scraper/api")
        )
        assertEquals(
            "apify/instagram-profile-scraper",
            ActorIdentifierResolver.toStoreName("apify~instagram-profile-scraper")
        )
    }

    @Test
    fun testExactNattykamalBug_RegressionAssertions() {
        // Screenshot 1 Exact Root-Cause Case: "Instagram ma nattykamal profile ko dikhao"
        // Must extract "nattykamal", NEVER "ma"
        val query1 = "Instagram ma nattykamal profile ko dikhao"
        val task1 = SpyIntentParser.parse(query1)
        assertEquals(PlatformType.INSTAGRAM, task1.platform)
        assertEquals("nattykamal", task1.target)
        assertFalse(task1.target == "ma")

        // Assertion 2: "Instagram par nattykamal ka profile dikhao"
        val query2 = "Instagram par nattykamal ka profile dikhao"
        val task2 = SpyIntentParser.parse(query2)
        assertEquals(PlatformType.INSTAGRAM, task2.platform)
        assertEquals("nattykamal", task2.target)

        // Assertion 3: "Instagram @nattykamal profile dikhao"
        val query3 = "Instagram @nattykamal profile dikhao"
        val task3 = SpyIntentParser.parse(query3)
        assertEquals(PlatformType.INSTAGRAM, task3.platform)
        assertEquals("nattykamal", task3.target)

        // Assertion 4: "Instagram nattykamal ka profile dikhao"
        val query4 = "Instagram nattykamal ka profile dikhao"
        val task4 = SpyIntentParser.parse(query4)
        assertEquals(PlatformType.INSTAGRAM, task4.platform)
        assertEquals("nattykamal", task4.target)

        // Assertion 5: "Instagram mein nattykamal ko search karo"
        val query5 = "Instagram mein nattykamal ko search karo"
        val task5 = SpyIntentParser.parse(query5)
        assertEquals(PlatformType.INSTAGRAM, task5.platform)
        assertEquals("nattykamal", task5.target)

        // Assertion 6: "Instagram profile @nattykamal"
        val query6 = "Instagram profile @nattykamal"
        val task6 = SpyIntentParser.parse(query6)
        assertEquals(PlatformType.INSTAGRAM, task6.platform)
        assertEquals("nattykamal", task6.target)
    }

    @Test
    fun testShortUsernames_PreservedExplicitly() {
        // Explicit @ma must NOT be rejected as noise
        val taskMa = SpyIntentParser.parse("Instagram @ma profile dikhao")
        assertEquals(PlatformType.INSTAGRAM, taskMa.platform)
        assertEquals("ma", taskMa.target)

        val taskA = SpyIntentParser.parse("Instagram @a profile")
        assertEquals(PlatformType.INSTAGRAM, taskA.platform)
        assertEquals("a", taskA.target)

        val taskAb = SpyIntentParser.parse("Instagram @ab profile")
        assertEquals(PlatformType.INSTAGRAM, taskAb.platform)
        assertEquals("ab", taskAb.target)

        val taskX = SpyIntentParser.parse("Twitter @x profile")
        assertEquals(PlatformType.TWITTER_X, taskX.platform)
        assertEquals("x", taskX.target)
    }

    @Test
    fun testExactPhoneLookup_RegressionAssertions() {
        // Screenshot 2 Exact Root-Cause Case: "+918XXXXXXXXX number ke bare mein information nikalo"
        val query = "+918999999999 number ke bare mein information nikalo"
        val task = SpyIntentParser.parse(query)
        assertEquals(TargetType.PHONE_NUMBER, task.targetType)
        assertEquals(SpyOperation.PUBLIC_PHONE_LOOKUP, task.operation)
        assertEquals(PlatformType.UNKNOWN, task.platform)
        assertEquals("+918999999999", task.target)

        // Public contact service executes safely without Apify Actor discovery failure
        val result = runBlocking { PublicContactLookupService.execute(task) }
        assertTrue(result.isSuccess)
        assertFalse(result.speech.contains("No compatible Actor found in Store"))
        assertTrue(result.speech.contains("Public Contact Intelligence"))
    }

    @Test
    fun testActorInputBuilder_NattykamalInstagram() {
        val task = SpyTask(
            platform = PlatformType.INSTAGRAM,
            operation = SpyOperation.PROFILE_LOOKUP,
            target = "nattykamal",
            maxResults = 1
        )
        val actor = ActorMetadata(
            actorId = "apify~instagram-profile-scraper",
            name = "instagram-profile-scraper",
            title = "Instagram Profile Scraper"
        )
        val input = ActorInputBuilder.buildInput(task, actor)
        assertTrue(input.containsKey("usernames"))
        val usernames = input["usernames"]?.jsonArray
        assertNotNull(usernames)
        assertEquals(1, usernames?.size)
        assertEquals("nattykamal", usernames?.first()?.jsonPrimitive?.content)
        assertFalse(usernames?.first()?.jsonPrimitive?.content == "ma")
    }

    @Test
    fun testPreviewRequested_NotForcedUnconditionally() {
        // Generic query without preview keyword
        val normalTask = SpyIntentParser.parse("Instagram nattykamal profile dikhao")
        assertFalse(normalTask.previewRequested)

        // Query with explicit preview keyword
        val previewTask = SpyIntentParser.parse("Instagram nattykamal profile preview dikhao")
        assertTrue(previewTask.previewRequested)

        val overviewTask = SpyIntentParser.parse("Instagram nattykamal ka overview batao")
        assertTrue(overviewTask.previewRequested)
    }

    @Test
    fun testMultiPlatformTargetExtraction() {
        // YouTube
        val ytTask = SpyIntentParser.parse("YouTube @mkbhd channel details")
        assertEquals(PlatformType.YOUTUBE, ytTask.platform)
        assertEquals("mkbhd", ytTask.target)

        // GitHub
        val ghTask = SpyIntentParser.parse("GitHub torvalds ka profile")
        assertEquals(PlatformType.GITHUB, ghTask.platform)
        assertEquals("torvalds", ghTask.target)

        // TikTok
        val ttTask = SpyIntentParser.parse("TikTok @charlidamelio videos")
        assertEquals(PlatformType.TIKTOK, ttTask.platform)
        assertEquals("charlidamelio", ttTask.target)

        // Twitter/X
        val xTask = SpyIntentParser.parse("Twitter @elonmusk profile")
        assertEquals(PlatformType.TWITTER_X, xTask.platform)
        assertEquals("elonmusk", xTask.target)

        // Reddit
        val redditTask = SpyIntentParser.parse("Reddit r/android posts")
        assertEquals(PlatformType.REDDIT, redditTask.platform)
        assertEquals("android", redditTask.target)
    }

    @Test
    fun testSpyForensic_NormalCallsPreserved() {
        val trigger = SpyGate.checkTrigger("Rahul ko call karo")
        assertTrue(trigger is SpyGateResult.NotTriggered)
    }

    @Test
    fun testPlatformCatalog_Integrity() {
        val instaDef = PlatformCatalog.findDefinition(PlatformType.INSTAGRAM)
        assertEquals("Instagram", instaDef.displayName)
        assertEquals(PlatformSupportStatus.SUPPORTED, instaDef.supportStatus)
        assertTrue(instaDef.aliases.contains("instagram"))

        val ytDef = PlatformCatalog.findDefinition(PlatformType.YOUTUBE)
        assertEquals("YouTube", ytDef.displayName)
        assertEquals(PlatformSupportStatus.SUPPORTED, ytDef.supportStatus)

        val ghDef = PlatformCatalog.findDefinition(PlatformType.GITHUB)
        assertEquals("GitHub", ghDef.displayName)
        assertEquals(PlatformSupportStatus.SUPPORTED, ghDef.supportStatus)

        val match = PlatformCatalog.findByAlias("insta")
        assertNotNull(match)
        assertEquals(PlatformType.INSTAGRAM, match?.platformType)

        // Crucial test: "information" or "in" must NOT match LinkedIn
        val infoMatch = PlatformCatalog.findByAlias("information")
        assertNull(infoMatch)
        val linkedinMatch = PlatformCatalog.findByAlias("linkedin")
        assertEquals(PlatformType.LINKEDIN, linkedinMatch?.platformType)
    }

    @Test
    fun testSpyResultNormalizer_VerificationSuccessAndContactExtraction() {
        val rawItem = buildJsonObject {
            put("fullName", "Natty Kamal")
            put("username", "nattykamal")
            put("biography", "Fitness Coach & Athlete | Contact: coach@nattykamal.com")
            put("followersCount", "125000")
            put("followsCount", "450")
            put("postsCount", "620")
            put("verified", true)
            put("url", "https://instagram.com/nattykamal")
            put("profilePicUrlHd", "https://instagram.com/p/pic.jpg")
            put("externalUrl", "https://nattykamal.com")
        }
        val items = JsonArray(listOf(rawItem))
        val task = SpyTask(platform = PlatformType.INSTAGRAM, target = "nattykamal")

        val normalized = SpyResultNormalizer.normalize(items, task)
        assertEquals(1, normalized.size)
        val first = normalized.first()
        assertTrue(first.hasGenuineData())
        assertEquals("Natty Kamal", first.title)
        assertEquals("nattykamal", first.identifier)
        assertEquals("https://instagram.com/p/pic.jpg", first.avatarUrl)
        assertEquals("https://nattykamal.com", first.websiteUrl)
        assertEquals("coach@nattykamal.com", first.publicEmail)
        assertEquals("125.0K", first.statistics["Followers"])

        val profile = first.toPlatformProfile(PlatformType.INSTAGRAM)
        assertEquals("nattykamal", profile.username)
        assertEquals("Natty Kamal", profile.displayName)
        assertEquals("125.0K", profile.followers)
        assertEquals("coach@nattykamal.com", profile.publicEmail)
    }

    @Test
    fun testSpyResultNormalizer_VerificationFailsOnEmptyOrError() {
        val errorItem = buildJsonObject {
            put("username", "nattykamal")
            put("error", "User not found")
        }
        val items1 = JsonArray(listOf(errorItem))
        val task1 = SpyTask(platform = PlatformType.INSTAGRAM, target = "nattykamal")
        val normalized1 = SpyResultNormalizer.normalize(items1, task1)
        assertFalse(normalized1.first().hasGenuineData())

        val emptyItem = buildJsonObject {
            put("username", "nattykamal")
        }
        val items2 = JsonArray(listOf(emptyItem))
        val normalized2 = SpyResultNormalizer.normalize(items2, task1)
        assertFalse(normalized2.first().hasGenuineData())
    }

    @Test
    fun testSpyProfileSerializer_EmbeddingAndExtraction() {
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "nattykamal",
            displayName = "Natty Kamal",
            followers = "125.0K",
            following = "450",
            postCount = "620",
            isVerified = true,
            publicEmail = "coach@nattykamal.com"
        )
        val markdown = "🔎 **Lichi Platform Intelligence**\n• **Username:** `@nattykamal`"
        val embedded = SpyProfileSerializer.embedProfile(profile, markdown)
        assertTrue(embedded.contains("<!--LICHI_SPY_PROFILE:"))

        val extracted = SpyProfileSerializer.extractProfile(embedded)
        assertNotNull(extracted)
        assertEquals("nattykamal", extracted?.username)
        assertEquals("Natty Kamal", extracted?.displayName)
        assertEquals("125.0K", extracted?.followers)
        assertEquals("coach@nattykamal.com", extracted?.publicEmail)

        val clean = SpyProfileSerializer.stripEmbeddedProfile(embedded)
        assertFalse(clean.contains("<!--LICHI_SPY_PROFILE:"))
        assertTrue(clean.startsWith("🔎 **Lichi Platform Intelligence**"))
    }

    @Test
    fun testDynamicPlatformAndFullModeParsing() {
        // Test -full flag
        val taskFull1 = SpyIntentParser.parse("Instagram nattykamal -full")
        assertEquals(com.lichiai.spy.core.SpyLookupMode.FULL, taskFull1.lookupMode)
        assertEquals("nattykamal", taskFull1.target)
        assertEquals(PlatformType.INSTAGRAM, taskFull1.platform)

        // Test -FULL flag and -U flag
        val taskFull2 = SpyIntentParser.parse("Instagram -U nattykamal -FULL")
        assertEquals(com.lichiai.spy.core.SpyLookupMode.FULL, taskFull2.lookupMode)
        assertEquals("nattykamal", taskFull2.target)

        // Test dynamic unknown platform like "Threads" or "Bluesky"
        val taskBluesky = SpyIntentParser.parse("Bluesky -u alice.bsky.social")
        assertEquals("alice.bsky.social", taskBluesky.target)
        assertEquals("bluesky", taskBluesky.dynamicPlatformRef?.key)

        val taskThreads = SpyIntentParser.parse("Threads markzuck profile -full")
        assertEquals("markzuck", taskThreads.target)
        assertEquals(com.lichiai.spy.core.SpyLookupMode.FULL, taskThreads.lookupMode)
    }

    @Test
    fun testPlatformCapabilityInferencer() {
        val platforms = com.lichiai.spy.discovery.PlatformCapabilityInferencer.inferPlatforms(
            name = "threads-profile-scraper",
            title = "Threads Profile & Posts Scraper",
            description = "Extract profile details, bio, follower count, and media from Threads.net",
            readme = null
        )
        assertTrue(platforms.contains("threads"))

        val capabilities = com.lichiai.spy.discovery.PlatformCapabilityInferencer.inferCapabilities(
            name = "threads-profile-scraper",
            title = "Threads Profile & Posts Scraper",
            description = "Extract profile details, bio, follower count, and media from Threads.net",
            readme = null
        )
        assertTrue(capabilities.contains(com.lichiai.spy.core.SpyCapability.PROFILE))
        assertTrue(capabilities.contains(com.lichiai.spy.core.SpyCapability.POSTS))
    }

    @Test
    fun testSpyResultVerifier() {
        val task = SpyTask(
            platform = PlatformType.INSTAGRAM,
            target = "nattykamal",
            operation = SpyOperation.PROFILE_LOOKUP
        )

        // Genuine match
        val genuineEntity = com.lichiai.spy.normalizer.NormalizedEntity(
            identifier = "nattykamal",
            title = "Natty Kamal",
            statistics = mapOf("Followers" to "1000")
        )
        val genuineResult = com.lichiai.spy.normalizer.SpyResultVerifier.verifyEntity(genuineEntity, task)
        assertTrue(genuineResult is com.lichiai.spy.normalizer.VerificationResult.Verified)

        // Mismatched target
        val mismatchedEntity = com.lichiai.spy.normalizer.NormalizedEntity(
            identifier = "other_person",
            title = "Other Person",
            statistics = mapOf("Followers" to "1000")
        )
        val mismatchedResult = com.lichiai.spy.normalizer.SpyResultVerifier.verifyEntity(mismatchedEntity, task)
        assertTrue(mismatchedResult is com.lichiai.spy.normalizer.VerificationResult.Rejected)

        // Error payload
        val errorEntity = com.lichiai.spy.normalizer.NormalizedEntity(
            identifier = "nattykamal",
            scraperError = "Profile not found"
        )
        val errorResult = com.lichiai.spy.normalizer.SpyResultVerifier.verifyEntity(errorEntity, task)
        assertTrue(errorResult is com.lichiai.spy.normalizer.VerificationResult.Rejected)
    }

    @Test
    fun testSpyEvidenceMerger_ConflictDetection() {
        val task = SpyTask(
            platform = PlatformType.INSTAGRAM,
            target = "nattykamal"
        )

        val entity1 = com.lichiai.spy.normalizer.NormalizedEntity(
            identifier = "nattykamal",
            title = "Natty Kamal",
            bioOrDescription = "Coach in Delhi",
            statistics = mapOf("Followers" to "120.0K"),
            providerId = "provider-a"
        )

        val entity2 = com.lichiai.spy.normalizer.NormalizedEntity(
            identifier = "nattykamal",
            title = "Natty Kamal Official",
            bioOrDescription = "Coach in Mumbai",
            statistics = mapOf("Followers" to "125.0K"),
            providerId = "provider-b"
        )

        val mergedProfile = com.lichiai.spy.orchestrator.SpyEvidenceMerger.mergeEntities(
            verifiedEntities = listOf(entity1, entity2),
            task = task,
            executedProviderIds = listOf("provider-a", "provider-b")
        )
        assertEquals("nattykamal", mergedProfile.username)
        assertEquals(2, mergedProfile.sourceProviders.size)
        assertTrue(mergedProfile.sourceProviders.contains("provider-a"))
        assertTrue(mergedProfile.sourceProviders.contains("provider-b"))

        // Conflicts should be detected for title, bio, and followers
        assertTrue(mergedProfile.conflicts.size >= 2)
    }

    @Test
    fun testSpyHtmlReportRenderer() {
        val task = SpyTask(
            platform = PlatformType.INSTAGRAM,
            target = "nattykamal",
            lookupMode = com.lichiai.spy.core.SpyLookupMode.FULL
        )
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "nattykamal",
            displayName = "Natty Kamal",
            followers = "125.0K",
            following = "450",
            sourceProviders = listOf("apify/instagram-scraper", "apify/instagram-profile-scraper")
        )

        val stats = listOf(
            com.lichiai.spy.orchestrator.ProviderExecutionStats(
                providerId = "apify/instagram-scraper",
                providerName = "Instagram Scraper",
                status = "SUCCESS",
                latencyMs = 1200L,
                recordCount = 1
            ),
            com.lichiai.spy.orchestrator.ProviderExecutionStats(
                providerId = "apify/instagram-profile-scraper",
                providerName = "Instagram Profile Scraper",
                status = "SUCCESS",
                latencyMs = 980L,
                recordCount = 1
            )
        )

        val html = com.lichiai.spy.orchestrator.SpyHtmlReportRenderer.renderHtml(
            task = task,
            profile = profile,
            providerStats = stats,
            conflicts = emptyList()
        )

        assertTrue(html.contains("<!DOCTYPE html>"))
        assertTrue(html.contains("nattykamal"))
        assertTrue(html.contains("125.0K"))
        assertTrue(html.contains("Platform Intelligence Report"))
    }
}

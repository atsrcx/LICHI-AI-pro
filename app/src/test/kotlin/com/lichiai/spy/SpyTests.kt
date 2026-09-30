package com.lichiai.spy

import com.lichiai.calling.intent.CallAction
import com.lichiai.calling.intent.CallActionIntentResolver
import com.lichiai.spy.apify.ActorIdentifierResolver
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
    fun testRequiredTargetExtractionCases() {
        // Test 1: #Spy Instagram per axeel_dubin account ka detail nikalo
        val trig1 = SpyGate.checkTrigger("#Spy Instagram per axeel_dubin account ka detail nikalo")
        assertTrue(trig1 is SpyGateResult.Triggered)
        val task1 = SpyIntentParser.parse((trig1 as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.INSTAGRAM, task1.platform)
        assertEquals(SpyOperation.PROFILE_LOOKUP, task1.operation)
        assertEquals("axeel_dubin", task1.target)

        // Test 2: #Spy Instagram @axeel_dubin ka profile batao
        val trig2 = SpyGate.checkTrigger("#Spy Instagram @axeel_dubin ka profile batao")
        assertTrue(trig2 is SpyGateResult.Triggered)
        val task2 = SpyIntentParser.parse((trig2 as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.INSTAGRAM, task2.platform)
        assertEquals("axeel_dubin", task2.target)

        // Test 3: #Spy Instagram axeel_dubin ka profile dikhao
        val trig3 = SpyGate.checkTrigger("#Spy Instagram axeel_dubin ka profile dikhao")
        assertTrue(trig3 is SpyGateResult.Triggered)
        val task3 = SpyIntentParser.parse((trig3 as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.INSTAGRAM, task3.platform)
        assertEquals("axeel_dubin", task3.target)

        // Test 4: #Spy Instagram https://www.instagram.com/axeel_dubin/ ka detail nikalo
        val trig4 = SpyGate.checkTrigger("#Spy Instagram https://www.instagram.com/axeel_dubin/ ka detail nikalo")
        assertTrue(trig4 is SpyGateResult.Triggered)
        val task4 = SpyIntentParser.parse((trig4 as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.INSTAGRAM, task4.platform)
        assertEquals("axeel_dubin", task4.target)

        // Test 5: #Spy Instagram par axeel_dubin ke followers batao
        val trig5 = SpyGate.checkTrigger("#Spy Instagram par axeel_dubin ke followers batao")
        assertTrue(trig5 is SpyGateResult.Triggered)
        val task5 = SpyIntentParser.parse((trig5 as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.INSTAGRAM, task5.platform)
        assertEquals("axeel_dubin", task5.target)
        assertTrue(task5.requestedFields.contains("followers"))

        // Test 6: #Spy Instagram par axeel_dubin ka bio batao
        val trig6 = SpyGate.checkTrigger("#Spy Instagram par axeel_dubin ka bio batao")
        assertTrue(trig6 is SpyGateResult.Triggered)
        val task6 = SpyIntentParser.parse((trig6 as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.INSTAGRAM, task6.platform)
        assertEquals("axeel_dubin", task6.target)
        assertTrue(task6.requestedFields.contains("bio"))

        // Test 7: #Spy YouTube @mkbhd ka channel detail batao
        val trig7 = SpyGate.checkTrigger("#Spy YouTube @mkbhd ka channel detail batao")
        assertTrue(trig7 is SpyGateResult.Triggered)
        val task7 = SpyIntentParser.parse((trig7 as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.YOUTUBE, task7.platform)
        assertEquals("mkbhd", task7.target)

        // Test 8: #Spy GitHub torvalds ka profile batao
        val trig8 = SpyGate.checkTrigger("#Spy GitHub torvalds ka profile batao")
        assertTrue(trig8 is SpyGateResult.Triggered)
        val task8 = SpyIntentParser.parse((trig8 as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.GITHUB, task8.platform)
        assertEquals("torvalds", task8.target)

        // Test 9: #Spy Reddit username ka public profile batao
        val trig9 = SpyGate.checkTrigger("#Spy Reddit username ka public profile batao")
        assertTrue(trig9 is SpyGateResult.Triggered)
        val task9 = SpyIntentParser.parse((trig9 as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.REDDIT, task9.platform)
        assertEquals("username", task9.target)

        // Test 10: #Spy Instagram @username ka public business email batao
        val trig10 = SpyGate.checkTrigger("#Spy Instagram @username ka public business email batao")
        assertTrue(trig10 is SpyGateResult.Triggered)
        val task10 = SpyIntentParser.parse((trig10 as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.INSTAGRAM, task10.platform)
        assertEquals("username", task10.target)
        assertEquals(SpyOperation.PUBLIC_EMAIL_LOOKUP, task10.operation)
        assertTrue(task10.requestedFields.contains("email"))

        // Test 11: #Spy website ka public phone number batao
        val trig11 = SpyGate.checkTrigger("#Spy website ka public phone number batao")
        assertTrue(trig11 is SpyGateResult.Triggered)
        val task11 = SpyIntentParser.parse((trig11 as SpyGateResult.Triggered).cleanQuery)
        assertEquals(SpyOperation.PUBLIC_PHONE_LOOKUP, task11.operation)
        assertTrue(task11.requestedFields.contains("phone"))

        // Test 12: Normal request WITHOUT #Spy
        val trig12 = SpyGate.checkTrigger("Instagram par axeel_dubin ka profile batao")
        assertTrue(trig12 is SpyGateResult.NotTriggered)
        assertFalse(SpyGate.isSpyTriggered("Instagram par axeel_dubin ka profile batao"))
    }

    @Test
    fun testSpyForensic_TestB_PhoneLookupNeverCalls() {
        val input = "#Spy +919927881086 is phone number ka information nikalo"
        val trigger = SpyGate.checkTrigger(input)
        assertTrue(trigger is SpyGateResult.Triggered)

        val task = SpyIntentParser.parse((trigger as SpyGateResult.Triggered).cleanQuery)
        assertEquals(SpyOperation.PUBLIC_PHONE_LOOKUP, task.operation)
        assertEquals(TargetType.PHONE_NUMBER, task.targetType)
        assertEquals("+919927881086", task.target)
        assertEquals(PlatformType.UNKNOWN, task.platform)

        // Verify Call Action Resolver completely ignores #Spy input
        val callActionResolver = CallActionIntentResolver()
        val action = callActionResolver.resolve(input)
        assertNull(action)
    }

    @Test
    fun testSpyForensic_TestC_PhoneLookupDoesNotGuessLinkedIn() {
        val input = "#Spy +919927881086 is ka information nikalo"
        val trigger = SpyGate.checkTrigger(input)
        assertTrue(trigger is SpyGateResult.Triggered)

        val task = SpyIntentParser.parse((trigger as SpyGateResult.Triggered).cleanQuery)
        assertEquals(SpyOperation.PUBLIC_PHONE_LOOKUP, task.operation)
        assertEquals(TargetType.PHONE_NUMBER, task.targetType)
        assertEquals("+919927881086", task.target)
        // Must NOT guess LinkedIn
        assertEquals(PlatformType.UNKNOWN, task.platform)
        assertFalse(task.platform == PlatformType.LINKEDIN)
    }

    @Test
    fun testSpyForensic_TestD_ProfilePreview() {
        val input = "#Spy Instagram @axeel_dubin ka profile preview dikhao"
        val trigger = SpyGate.checkTrigger(input)
        assertTrue(trigger is SpyGateResult.Triggered)

        val task = SpyIntentParser.parse((trigger as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.INSTAGRAM, task.platform)
        assertEquals(SpyOperation.PROFILE_PREVIEW, task.operation)
        assertEquals("axeel_dubin", task.target)
        assertTrue(task.previewRequested)
    }

    @Test
    fun testSpyForensic_TestE_PublicEmailLookup() {
        val input = "#Spy example@email.com ka public information nikalo"
        val trigger = SpyGate.checkTrigger(input)
        assertTrue(trigger is SpyGateResult.Triggered)

        val task = SpyIntentParser.parse((trigger as SpyGateResult.Triggered).cleanQuery)
        assertEquals(SpyOperation.PUBLIC_EMAIL_LOOKUP, task.operation)
        assertEquals(TargetType.EMAIL, task.targetType)
        assertEquals("example@email.com", task.target)
    }

    @Test
    fun testSpyForensic_TestF_PhoneWithExplicitPlatform() {
        val input = "#Spy Instagram par +919927881086 ka public business information batao"
        val trigger = SpyGate.checkTrigger(input)
        assertTrue(trigger is SpyGateResult.Triggered)

        val task = SpyIntentParser.parse((trigger as SpyGateResult.Triggered).cleanQuery)
        assertEquals(PlatformType.INSTAGRAM, task.platform)
        assertEquals(SpyOperation.PUBLIC_PHONE_LOOKUP, task.operation)
        assertEquals(TargetType.PHONE_NUMBER, task.targetType)
        assertEquals("+919927881086", task.target)
    }

    @Test
    fun testSpyForensic_TestG_NormalCallsPreserved() {
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
    fun testActorInputBuilder_Instagram() {
        val task = SpyTask(
            platform = PlatformType.INSTAGRAM,
            operation = SpyOperation.PROFILE_LOOKUP,
            target = "axeel_dubin",
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
        assertEquals("axeel_dubin", usernames?.first()?.jsonPrimitive?.content)
    }

    @Test
    fun testSpyResultNormalizer_VerificationSuccessAndContactExtraction() {
        val rawItem = buildJsonObject {
            put("fullName", "Axeel Dubin")
            put("username", "axeel_dubin")
            put("biography", "Tech Innovator & Architect | Contact: business@axeeldubin.com")
            put("followersCount", "283600")
            put("followsCount", "273")
            put("postsCount", "4300")
            put("verified", true)
            put("url", "https://instagram.com/axeel_dubin")
            put("profilePicUrlHd", "https://instagram.com/p/pic.jpg")
            put("externalUrl", "https://axeeldubin.com")
        }
        val items = JsonArray(listOf(rawItem))
        val task = SpyTask(platform = PlatformType.INSTAGRAM, target = "axeel_dubin")

        val normalized = SpyResultNormalizer.normalize(items, task)
        assertEquals(1, normalized.size)
        val first = normalized.first()
        assertTrue(first.hasGenuineData())
        assertEquals("Axeel Dubin", first.title)
        assertEquals("axeel_dubin", first.identifier)
        assertEquals("https://instagram.com/p/pic.jpg", first.avatarUrl)
        assertEquals("https://axeeldubin.com", first.websiteUrl)
        assertEquals("business@axeeldubin.com", first.publicEmail)
        assertEquals("283.6K", first.statistics["Followers"])
        assertEquals("273", first.statistics["Following"])
        assertEquals("4.3K", first.statistics["Posts / Media"])

        val profile = first.toPlatformProfile(PlatformType.INSTAGRAM)
        assertEquals("axeel_dubin", profile.username)
        assertEquals("Axeel Dubin", profile.displayName)
        assertEquals("283.6K", profile.followers)
        assertEquals("business@axeeldubin.com", profile.publicEmail)
    }

    @Test
    fun testSpyResultNormalizer_VerificationFailsOnEmptyOrError() {
        val errorItem = buildJsonObject {
            put("username", "axeel_dubin")
            put("error", "User not found")
        }
        val items1 = JsonArray(listOf(errorItem))
        val task1 = SpyTask(platform = PlatformType.INSTAGRAM, target = "axeel_dubin")
        val normalized1 = SpyResultNormalizer.normalize(items1, task1)
        assertFalse(normalized1.first().hasGenuineData())

        val emptyItem = buildJsonObject {
            put("username", "axeel_dubin")
        }
        val items2 = JsonArray(listOf(emptyItem))
        val normalized2 = SpyResultNormalizer.normalize(items2, task1)
        assertFalse(normalized2.first().hasGenuineData())
    }

    @Test
    fun testSpyProfileSerializer_EmbeddingAndExtraction() {
        val profile = PlatformProfile(
            platform = PlatformType.INSTAGRAM,
            username = "axeel_dubin",
            displayName = "Axel Ariel Dubin",
            followers = "283.6K",
            following = "273",
            postCount = "4.3K",
            isVerified = true,
            publicEmail = "business@example.com"
        )
        val markdown = "🔎 **Lichi Platform Intelligence**\n• **Username:** `@axeel_dubin`"
        val embedded = SpyProfileSerializer.embedProfile(profile, markdown)
        assertTrue(embedded.contains("<!--LICHI_SPY_PROFILE:"))

        val extracted = SpyProfileSerializer.extractProfile(embedded)
        assertNotNull(extracted)
        assertEquals("axeel_dubin", extracted?.username)
        assertEquals("Axel Ariel Dubin", extracted?.displayName)
        assertEquals("283.6K", extracted?.followers)
        assertEquals("business@example.com", extracted?.publicEmail)

        val clean = SpyProfileSerializer.stripEmbeddedProfile(embedded)
        assertFalse(clean.contains("<!--LICHI_SPY_PROFILE:"))
        assertTrue(clean.startsWith("🔎 **Lichi Platform Intelligence**"))
    }
}

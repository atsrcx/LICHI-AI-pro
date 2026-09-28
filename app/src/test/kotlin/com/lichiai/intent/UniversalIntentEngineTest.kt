package com.lichiai.intent

import com.lichiai.intent.context.ContextBuilder
import com.lichiai.intent.model.BrowserActionType
import com.lichiai.intent.model.IntentContext
import com.lichiai.intent.model.LichiCapability
import com.lichiai.intent.model.ResolvedIntent
import com.lichiai.intent.normalizer.InputNormalizer
import com.lichiai.intent.router.DeterministicRuleRouter
import com.lichiai.intent.router.ReferenceAndCorrectionResolver
import com.lichiai.intent.router.SemanticRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalIntentEngineTest {

    @Test
    fun testInputNormalizer() {
        val raw = "Browser   kholo   aur google per pubg game search karo  "
        val normalized = InputNormalizer.normalize(raw)
        assertEquals("browser kholo aur google par pubg game search karo", normalized)
    }

    @Test
    fun testDeterministicBrowserDirectUrls() {
        val router = DeterministicRuleRouter()

        val resUrl = router.route("https://example.com")
        assertNotNull(resUrl)
        assertTrue(resUrl!!.first is ResolvedIntent.BrowserTask)
        assertEquals("https://example.com", (resUrl.first as ResolvedIntent.BrowserTask).url)

        val resDomain = router.route("wikipedia.org")
        assertNotNull(resDomain)
        assertTrue(resDomain!!.first is ResolvedIntent.BrowserTask)
        assertEquals("https://wikipedia.org", (resDomain.first as ResolvedIntent.BrowserTask).url)
    }

    @Test
    fun testDeterministicBrowserControls() {
        val router = DeterministicRuleRouter()

        val scroll = router.route("scroll down")
        assertNotNull(scroll)
        assertEquals(BrowserActionType.SCROLL_DOWN, (scroll!!.first as ResolvedIntent.BrowserTask).action)

        val back = router.route("go back")
        assertNotNull(back)
        assertEquals(BrowserActionType.BACK, (back!!.first as ResolvedIntent.BrowserTask).action)

        val refresh = router.route("reload page")
        assertNotNull(refresh)
        assertEquals(BrowserActionType.RELOAD, (refresh!!.first as ResolvedIntent.BrowserTask).action)
    }

    @Test
    fun testSemanticBrowserSearchQueries() {
        val router = SemanticRouter()

        // Test B: "Browser kholo aur Google par PUBG game search karo."
        val resPubg = router.route("browser kholo aur google par pubg game search karo")
        assertNotNull(resPubg)
        assertTrue(resPubg!!.first is ResolvedIntent.BrowserTask)
        val pubgTask = resPubg.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.SEARCH, pubgTask.action)
        assertTrue(pubgTask.query?.contains("pubg game") == true)

        // "Google par search karo iPhone 16 price"
        val resIphone = router.route("google par search karo iphone 16 price")
        assertNotNull(resIphone)
        assertTrue(resIphone!!.first is ResolvedIntent.BrowserTask)
        val iphoneTask = resIphone.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.SEARCH, iphoneTask.action)
        assertTrue(iphoneTask.query?.contains("iphone 16 price") == true)
    }

    @Test
    fun testSemanticWebSearchQueries() {
        val router = SemanticRouter()

        // Test C: "Web search karo Aaj news kya bata raha hai."
        val resNews = router.route("web search karo aaj news kya bata raha hai")
        assertNotNull(resNews)
        assertTrue(resNews!!.first is ResolvedIntent.WebSearchTask)
        val newsTask = resNews.first as ResolvedIntent.WebSearchTask
        assertTrue(newsTask.isNewsSearch)
        assertTrue(newsTask.query.contains("news") || newsTask.query.contains("aaj"))
    }

    @Test
    fun testSemanticAndroidAppOpening() {
        val router = SemanticRouter()

        // Test D: "Instagram kholo mere phone mein."
        val resInsta = router.route("instagram kholo mere phone mein")
        assertNotNull(resInsta)
        assertTrue(resInsta!!.first is ResolvedIntent.AndroidAgentTask)
        val instaTask = resInsta.first as ResolvedIntent.AndroidAgentTask
        assertEquals("instagram", instaTask.targetApp)
    }

    @Test
    fun testFollowUpAndCorrectionResolver() {
        val contextWithCandidates = IntentContext(
            lastExecutedCapability = LichiCapability.BROWSER,
            browserCandidates = listOf("PUBG Mobile Official", "Krafton Battlegrounds", "Download APK")
        )

        val secondResult = ReferenceAndCorrectionResolver.resolve("doosra result kholo", contextWithCandidates)
        assertNotNull(secondResult)
        assertTrue(secondResult!!.first is ResolvedIntent.BrowserTask)
        val clickTask = secondResult.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.CLICK_CANDIDATE, clickTask.action)
        assertEquals(1, clickTask.candidateIndex)

        // Find on page
        val findResult = ReferenceAndCorrectionResolver.resolve("ismein download dhundo", contextWithCandidates)
        assertNotNull(findResult)
        assertTrue(findResult!!.first is ResolvedIntent.BrowserTask)
        val findTask = findResult.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.FIND_ON_PAGE, findTask.action)
        assertEquals("download", findTask.findTarget)

        // Correction from web search to browser
        val lastWebContext = IntentContext(
            lastExecutedCapability = LichiCapability.WEB_SEARCH,
            lastSearchQuery = "weather in delhi"
        )
        val correction = ReferenceAndCorrectionResolver.resolve("nahi browser mein search karo", lastWebContext)
        assertNotNull(correction)
        assertTrue(correction!!.first is ResolvedIntent.BrowserTask)
        val corrTask = correction.first as ResolvedIntent.BrowserTask
        assertEquals(BrowserActionType.SEARCH, corrTask.action)
        assertEquals("weather in delhi", corrTask.query)

        // Cancellation tests
        val cancelResult = ReferenceAndCorrectionResolver.resolve("rehne do", contextWithCandidates)
        assertNotNull(cancelResult)
        assertTrue(cancelResult!!.first is ResolvedIntent.Cancellation)

        val cancelResult2 = ReferenceAndCorrectionResolver.resolve("chhodo yaar", contextWithCandidates)
        assertNotNull(cancelResult2)
        assertTrue(cancelResult2!!.first is ResolvedIntent.Cancellation)

        // Contact correction: "Nahi, Rohit ko"
        val callContext = IntentContext(
            lastExecutedCapability = LichiCapability.CALLS,
            recentEntities = mapOf("contact" to "Rahul")
        )
        val contactCorrection = ReferenceAndCorrectionResolver.resolve("nahi rohit ko", callContext)
        assertNotNull(contactCorrection)
        assertTrue(contactCorrection!!.first is ResolvedIntent.CallTask)
        val correctedCall = contactCorrection.first as ResolvedIntent.CallTask
        assertEquals("rohit", correctedCall.callIntent.targetText?.lowercase())

        // Platform correction: "Nahi Instagram pe"
        val platformCorrection = ReferenceAndCorrectionResolver.resolve("nahi instagram pe", callContext)
        assertNotNull(platformCorrection)
        assertTrue(platformCorrection!!.first is ResolvedIntent.AndroidAgentTask)
        val platformTask = platformCorrection.first as ResolvedIntent.AndroidAgentTask
        assertEquals("instagram", platformTask.targetApp)
    }

    @Test
    fun testQuestionsVsActions() {
        val router = SemanticRouter()

        // "Instagram kya hai?" should be NormalChat (conversation), not AndroidAgentTask
        val resQuestion = router.route("Instagram kya hai?")
        assertNotNull(resQuestion)
        assertTrue(resQuestion!!.first is ResolvedIntent.NormalChat)

        // "Rahul ko call kaise karte hain?" should be NormalChat, not CallTask
        val resCallQ = router.route("Rahul ko call kaise karte hain?")
        assertNotNull(resCallQ)
        assertTrue(resCallQ!!.first is ResolvedIntent.NormalChat)
    }

    @Test
    fun testCasualBrowsingAndMessagingPhrasing() {
        val router = SemanticRouter()

        // "Yaar zara browser mein dekh na woh PUBG wali official site"
        val resCasual = router.route("Yaar zara browser mein dekh na woh PUBG wali official site")
        assertNotNull(resCasual)
        assertTrue(resCasual!!.first is ResolvedIntent.BrowserTask)
        val bTask = resCasual.first as ResolvedIntent.BrowserTask
        assertTrue(bTask.query?.contains("pubg", ignoreCase = true) == true)

        // "Aditya ko bol de kal milne aa raha hai kya"
        val resMsg = router.route("Aditya ko bol de kal milne aa raha hai kya")
        assertNotNull(resMsg)
        assertTrue(resMsg!!.first is ResolvedIntent.AndroidAgentTask)
        val msgTask = resMsg.first as ResolvedIntent.AndroidAgentTask
        assertTrue(msgTask.goal.contains("Aditya", ignoreCase = true))

        // "Abhi iPhone 17 Pro ka price check karo"
        val resPrice = router.route("Abhi iPhone 17 Pro ka price check karo")
        assertNotNull(resPrice)
        assertTrue(resPrice!!.first is ResolvedIntent.WebSearchTask)
        val priceTask = resPrice.first as ResolvedIntent.WebSearchTask
        assertTrue(priceTask.query.contains("iphone 17 pro", ignoreCase = true))
    }

    @Test
    fun testIntentUnderstandingGeneration() = runBlocking {
        val capabilityRegistry = com.lichiai.intent.registry.CapabilityRegistry()
        val contextBuilder = ContextBuilder()
        val engine = UniversalIntentEngine(capabilityRegistry, contextBuilder)

        val result = engine.resolve("browser kholo aur google par pubg game search karo")
        assertNotNull(result.understanding)
        assertEquals(com.lichiai.intent.model.IntentType.VISIBLE_BROWSER_TASK, result.understanding?.intentType)
        assertEquals("BROWSER", result.understanding?.domain)
    }

    @Test
    fun testMasterForensicUserScenarios() {
        val router = SemanticRouter()

        // TEST A & P: "Mere phone ki YouTube mein ek Arijit Singh ka gana lagao"
        val resA = router.route("Mere phone ki YouTube mein ek Arijit Singh ka gana lagao")
        assertNotNull(resA)
        assertTrue(resA!!.first is ResolvedIntent.AndroidAgentTask || resA.first is ResolvedIntent.MediaTask)
        if (resA.first is ResolvedIntent.MediaTask) {
            val media = resA.first as ResolvedIntent.MediaTask
            assertEquals("youtube", media.targetApp)
            assertTrue(media.query.contains("Arijit Singh", ignoreCase = true))
            assertFalse(media.query.contains("ek arijit singh ka gana lagao", ignoreCase = true))
        }

        // TEST B: "Mere phone mein YouTube kholo"
        val resB = router.route("Mere phone mein YouTube kholo")
        assertNotNull(resB)
        assertTrue(resB!!.first is ResolvedIntent.AndroidAgentTask)
        assertEquals("youtube", (resB.first as ResolvedIntent.AndroidAgentTask).targetApp)

        // TEST C: "YouTube par Arijit Singh ka latest gana search karo"
        val resC = router.route("YouTube par Arijit Singh ka latest gana search karo")
        assertNotNull(resC)
        assertTrue(resC!!.first is ResolvedIntent.BrowserTask || resC.first is ResolvedIntent.MediaTask)
        if (resC.first is ResolvedIntent.BrowserTask) {
            val b = resC.first as ResolvedIntent.BrowserTask
            assertEquals("youtube", b.searchEngine)
            assertTrue(b.query?.contains("Arijit Singh", ignoreCase = true) == true)
            assertTrue(b.query?.contains("latest", ignoreCase = true) == true)
            assertFalse(b.query?.contains("search karo", ignoreCase = true) == true)
        }

        // TEST D: "Google par Arijit Singh ka gana search karo"
        val resD = router.route("Google par Arijit Singh ka gana search karo")
        assertNotNull(resD)
        assertTrue(resD!!.first is ResolvedIntent.BrowserTask)
        val bD = resD.first as ResolvedIntent.BrowserTask
        assertEquals("google", bD.searchEngine)
        assertTrue(bD.query?.contains("Arijit Singh", ignoreCase = true) == true)
        assertFalse(bD.query?.contains("Google par", ignoreCase = true) == true)

        // TEST E: "Browser kholo aur Google par PUBG search karo"
        val resE = router.route("Browser kholo aur Google par PUBG search karo")
        assertNotNull(resE)
        assertTrue(resE!!.first is ResolvedIntent.BrowserTask)
        assertEquals("pubg", (resE.first as ResolvedIntent.BrowserTask).query?.lowercase())

        // TEST N: "Google par PUBG game search kar do"
        val resN = router.route("Google par PUBG game search kar do")
        assertNotNull(resN)
        assertTrue(resN!!.first is ResolvedIntent.BrowserTask)
        assertEquals("pubg game", (resN.first as ResolvedIntent.BrowserTask).query?.lowercase())

        // TEST O: "Arijit Singh ka latest romantic song search karo"
        val resO = router.route("Arijit Singh ka latest romantic song search karo")
        assertNotNull(resO)
        assertTrue(resO!!.first is ResolvedIntent.BrowserTask)
        val bO = resO.first as ResolvedIntent.BrowserTask
        assertTrue(bO.query?.contains("Arijit Singh", ignoreCase = true) == true)
        assertTrue(bO.query?.contains("latest", ignoreCase = true) == true)
        assertTrue(bO.query?.contains("romantic", ignoreCase = true) == true)
        assertTrue(bO.query?.contains("song", ignoreCase = true) == true)

        // TEST Q: Roman Hindi: "youtube par arijit singh ka gana laga do"
        val resQ = router.route("youtube par arijit singh ka gana laga do")
        assertNotNull(resQ)
        assertTrue(resQ!!.first is ResolvedIntent.MediaTask || resQ.first is ResolvedIntent.AndroidAgentTask)

        // TEST R: Hindi: "YouTube पर अरिजीत सिंह का गाना चला दो"
        val resR = router.route("YouTube पर अरिजीत सिंह का गाना चला दो")
        assertNotNull(resR)
    }
}

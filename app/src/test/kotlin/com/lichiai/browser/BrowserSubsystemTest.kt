package com.lichiai.browser

import com.lichiai.browser.api.BrowserCommandParser
import com.lichiai.browser.api.BrowserUserIntent
import com.lichiai.browser.api.ScrollDirection
import com.lichiai.browser.context.BrowserTaskContext
import com.lichiai.browser.planner.BrowserPlanner
import com.lichiai.browser.security.BrowserSecurityManager
import com.lichiai.browser.tabs.BrowserTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSubsystemTest {

    @Test
    fun testCommandParser_DirectUrl() {
        val intent1 = BrowserCommandParser.parse("https://github.com")
        assertTrue("Expected NavigateUrl intent", intent1 is BrowserUserIntent.NavigateUrl)
        assertEquals("https://github.com", (intent1 as BrowserUserIntent.NavigateUrl).url)

        val intent2 = BrowserCommandParser.parse("open wikipedia.org")
        assertTrue("Expected NavigateUrl intent", intent2 is BrowserUserIntent.NavigateUrl)
        assertEquals("https://wikipedia.org", (intent2 as BrowserUserIntent.NavigateUrl).url)
    }

    @Test
    fun testCommandParser_SearchQueries() {
        // Fast hash syntax e.g. #google PUBG
        val intent1 = BrowserCommandParser.parse("#google PUBG")
        assertTrue("Expected Search intent", intent1 is BrowserUserIntent.Search)
        assertEquals("PUBG", (intent1 as BrowserUserIntent.Search).query)

        // Natural language Hindi/Hinglish search
        val intent2 = BrowserCommandParser.parse("Google par PUBG game dhundho")
        assertTrue("Expected Search intent", intent2 is BrowserUserIntent.Search)
        assertTrue((intent2 as BrowserUserIntent.Search).query.contains("PUBG"))

        val intent3 = BrowserCommandParser.parse("search latest AI news")
        assertTrue("Expected Search intent", intent3 is BrowserUserIntent.Search)
        assertTrue((intent3 as BrowserUserIntent.Search).query.contains("AI news"))
    }

    @Test
    fun testCommandParser_ClickCandidate() {
        val intent = BrowserCommandParser.parse("doosra result kholo")
        assertTrue("Expected ClickCandidate intent", intent is BrowserUserIntent.ClickCandidate)
        assertEquals(2, (intent as BrowserUserIntent.ClickCandidate).index)

        val intent2 = BrowserCommandParser.parse("third result")
        assertTrue("Expected ClickCandidate intent", intent2 is BrowserUserIntent.ClickCandidate)
        assertEquals(3, (intent2 as BrowserUserIntent.ClickCandidate).index)
    }

    @Test
    fun testCommandParser_NavigationControls() {
        assertTrue(BrowserCommandParser.parse("back jao") is BrowserUserIntent.GoBack)
        assertTrue(BrowserCommandParser.parse("forward jao") is BrowserUserIntent.GoForward)
        assertTrue(BrowserCommandParser.parse("reload") is BrowserUserIntent.Reload)
        val scroll = BrowserCommandParser.parse("scroll down")
        assertTrue(scroll is BrowserUserIntent.Scroll)
        assertEquals(ScrollDirection.DOWN, (scroll as BrowserUserIntent.Scroll).direction)
        assertTrue(BrowserCommandParser.parse("stop") is BrowserUserIntent.StopTask)
    }

    @Test
    fun testSecurityManager_AllowedUrls() {
        val resHttps = BrowserSecurityManager.validateNavigationUrl("https://example.com/test")
        assertTrue("HTTPS should be allowed", resHttps.allowed)

        val resHttp = BrowserSecurityManager.validateNavigationUrl("http://example.com")
        assertTrue("HTTP should be allowed", resHttp.allowed)
    }

    @Test
    fun testSecurityManager_BlockedDangerousUrls() {
        val resFile = BrowserSecurityManager.validateNavigationUrl("file:///android_asset/secret.txt")
        assertFalse("file:// scheme must be blocked", resFile.allowed)

        val resContent = BrowserSecurityManager.validateNavigationUrl("content://contacts/people")
        assertFalse("content:// scheme must be blocked", resContent.allowed)

        val resJs = BrowserSecurityManager.validateNavigationUrl("javascript:alert(1)")
        assertFalse("javascript: scheme must be blocked", resJs.allowed)

        val resIntent = BrowserSecurityManager.validateNavigationUrl("intent://#Intent;action=android.intent.action.VIEW;end")
        assertFalse("intent:// scheme must be blocked", resIntent.allowed)
    }

    @Test
    fun testBrowserPlanner_ZeroLlmFastPath() {
        val searchIntent = BrowserUserIntent.Search("weather in Mumbai")
        val ctx = BrowserTaskContext(taskId = "test_task", userGoal = "search weather")
        val plan = BrowserPlanner.planFromIntent(searchIntent, ctx, "https://www.google.com/search?q=")

        assertNotNull("Search plan should not be null", plan)
        assertTrue("Search should be zero-LLM executed", plan.zeroLlmExecuted)
        assertEquals(1, plan.steps.size)
        assertEquals("search", plan.steps[0].toolName)
        assertEquals("weather in Mumbai", plan.steps[0].arguments["query"])
    }

    @Test
    fun testBrowserPlanner_CandidateSelectionPlan() {
        val clickIntent = BrowserUserIntent.ClickCandidate(index = 2)
        val ctx = BrowserTaskContext(taskId = "test_task", userGoal = "doosra result")
        val plan = BrowserPlanner.planFromIntent(clickIntent, ctx, "https://www.google.com/search?q=")

        assertNotNull(plan)
        assertTrue(plan.zeroLlmExecuted)
        assertEquals("clickCandidate", plan.steps[0].toolName)
        assertEquals("2", plan.steps[0].arguments["index"])
    }

    @Test
    fun testBrowserTab_StateManagement() {
        val tab = BrowserTab(
            id = "tab_1",
            title = "Google",
            url = "https://www.google.com",
            isLoading = false,
            progress = 100,
            canGoBack = true,
            canGoForward = false
        )

        assertEquals("tab_1", tab.id)
        assertEquals("Google", tab.title)
        assertTrue(tab.canGoBack)
        assertFalse(tab.canGoForward)
        assertFalse(tab.isIncognito)
    }

    @Test
    fun testBrowserIntentBoundary_DetectionAndResolution() {
        val pubgCmd = "Browser kholo aur Google par PUBG game search karo."
        assertTrue("Should detect browser intent for PUBG query", com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent(pubgCmd))

        val (intent, ack) = com.lichiai.browser.api.BrowserIntentBoundary.resolveIntent(pubgCmd)
        assertTrue("Intent must be Search", intent is BrowserUserIntent.Search)
        val searchIntent = intent as BrowserUserIntent.Search
        assertTrue(searchIntent.query.contains("PUBG"))
        assertEquals("google", searchIntent.searchEngine)
        assertTrue(ack.contains("Google"))

        // Pure site navigation
        assertTrue(com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent("Browser kholo."))
        assertTrue(com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent("Google kholo."))
        assertTrue(com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent("YouTube kholo."))
        assertTrue(com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent("Neeche scroll karo."))
        assertTrue(com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent("Search results mein doosra result kholo."))

        // Non-browser intents MUST be rejected so they flow to normal chat or call handlers
        assertFalse(com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent("Rahul ko phone lagao"))
        assertFalse(com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent("Call Mummy"))
        assertFalse(com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent("What is the capital of France?"))
        assertFalse(com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent("Turn on flashlight"))
        assertFalse(com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent("Instagram kholo mere phone mein"))
        assertFalse(com.lichiai.browser.api.BrowserIntentBoundary.isBrowserIntent("Phone ki brightness 50% karo"))
    }

    @Test
    fun testUrlResolver_AddressBarContract() {
        // Direct URLs
        val r1 = com.lichiai.browser.api.BrowserUrlResolver.resolve("https://example.com")
        assertTrue(r1 is com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.DirectUrl)
        assertEquals("https://example.com", (r1 as com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.DirectUrl).url)

        val r2 = com.lichiai.browser.api.BrowserUrlResolver.resolve("example.com")
        assertTrue(r2 is com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.DirectUrl)
        assertEquals("https://example.com", (r2 as com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.DirectUrl).url)

        val r3 = com.lichiai.browser.api.BrowserUrlResolver.resolve("https://google.com")
        assertTrue(r3 is com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.DirectUrl)
        assertEquals("https://google.com", (r3 as com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.DirectUrl).url)

        val r4 = com.lichiai.browser.api.BrowserUrlResolver.resolve("https://youtube.com")
        assertTrue(r4 is com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.DirectUrl)
        assertEquals("https://youtube.com", (r4 as com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.DirectUrl).url)

        // Search queries
        val r5 = com.lichiai.browser.api.BrowserUrlResolver.resolve("PUBG game")
        assertTrue(r5 is com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.SearchQuery)
        assertEquals("PUBG game", (r5 as com.lichiai.browser.api.BrowserUrlResolver.ResolvedTarget.SearchQuery).query)
    }

    @Test
    fun testNaturalQueryExtraction_ExactPromptCases() {
        // Case 1: "Browser kholo aur Google par PUBG game search karo."
        val i1 = BrowserCommandParser.parse("Browser kholo aur Google par PUBG game search karo.")
        assertTrue("Expected Search intent", i1 is BrowserUserIntent.Search)
        val s1 = i1 as BrowserUserIntent.Search
        assertEquals("PUBG game", s1.query)
        assertEquals("google", s1.searchEngine)

        // Case 2: "Web search karo Aaj news kya bata raha hai" -> "Aaj news kya bata raha hai" (NOT "Web Aaj news...")
        val i2 = BrowserCommandParser.parse("Web search karo Aaj news kya bata raha hai")
        assertTrue("Expected Search intent", i2 is BrowserUserIntent.Search)
        val s2 = i2 as BrowserUserIntent.Search
        assertEquals("Aaj news kya bata raha hai", s2.query)
        assertFalse(s2.query.startsWith("Web", ignoreCase = true))

        // Case 3: "YouTube kholo aur Arijit Singh search karo"
        val i3 = BrowserCommandParser.parse("YouTube kholo aur Arijit Singh search karo")
        assertTrue("Expected Search intent", i3 is BrowserUserIntent.Search)
        val s3 = i3 as BrowserUserIntent.Search
        assertEquals("Arijit Singh", s3.query)
        assertEquals("youtube", s3.searchEngine)

        // Case 4: "Google par PUBG search karo"
        val i4 = BrowserCommandParser.parse("Google par PUBG search karo")
        assertTrue("Expected Search intent", i4 is BrowserUserIntent.Search)
        val s4 = i4 as BrowserUserIntent.Search
        assertEquals("PUBG", s4.query)
        assertEquals("google", s4.searchEngine)
    }

    @Test
    fun testBrowserVerifier_AccurateVerification() {
        val blankCtx = BrowserTaskContext(currentUrl = "about:blank")
        val blankResult = com.lichiai.browser.verifier.BrowserVerifier.verifySearchResults("PUBG", blankCtx)
        assertFalse("Blank URL must not pass search results verification", blankResult.passed)

        val loadedCtx = BrowserTaskContext(
            currentUrl = "https://www.google.com/search?q=PUBG+game",
            currentTitle = "PUBG game - Google Search"
        )
        val loadedResult = com.lichiai.browser.verifier.BrowserVerifier.verifySearchResults("PUBG game", loadedCtx)
        assertTrue("Loaded search URL must pass verification", loadedResult.passed)
    }

    @Test
    fun testExactUserScenarios_NoFullSentenceSearchRegression() {
        // Scenario 1: "Browser kholo, Google par PUBG ki official website search karo aur search results mein jo official PUBG website mile usko open karo"
        val cmd1 = "Browser kholo, Google par PUBG ki official website search karo aur search results mein jo official PUBG website mile usko open karo"
        val i1 = BrowserCommandParser.parse(cmd1)
        assertTrue("Scenario 1 must produce SearchAndOpen intent", i1 is BrowserUserIntent.SearchAndOpen)
        val s1 = i1 as BrowserUserIntent.SearchAndOpen
        assertTrue("Search query must contain PUBG", s1.searchQuery.contains("PUBG", ignoreCase = true))
        assertFalse("Search query must NOT be the entire sentence", s1.searchQuery.contains("search results mein"))
        assertTrue("Target criterion must be Official", s1.targetCriterion is com.lichiai.browser.api.TargetCriterion.Official)

        // Scenario 2: "Apna browser kholo, Google par Aditya ke baare mein koi relevant website dhundho aur jo relevant website mile usko kholo."
        val cmd2 = "Apna browser kholo, Google par Aditya ke baare mein koi relevant website dhundho aur jo relevant website mile usko kholo."
        val i2 = BrowserCommandParser.parse(cmd2)
        assertTrue("Scenario 2 must produce SearchAndOpen intent", i2 is BrowserUserIntent.SearchAndOpen)
        val s2 = i2 as BrowserUserIntent.SearchAndOpen
        assertTrue("Search query must contain Aditya", s2.searchQuery.contains("Aditya", ignoreCase = true))
        assertFalse("Search query must NOT be full sentence", s2.searchQuery.contains("jo relevant website mile"))
        assertTrue("Target criterion must be Relevant", s2.targetCriterion is com.lichiai.browser.api.TargetCriterion.Relevant)

        // Scenario 3: Search-Only: "Google par PUBG search karo."
        val cmd3 = "Google par PUBG search karo."
        val i3 = BrowserCommandParser.parse(cmd3)
        assertTrue("Scenario 3 must produce Search-only intent", i3 is BrowserUserIntent.Search)
        val s3 = i3 as BrowserUserIntent.Search
        assertEquals("PUBG", s3.query)
        assertEquals("google", s3.searchEngine)

        // Scenario 4: "Google par PUBG search karo aur official website kholo."
        val cmd4 = "Google par PUBG search karo aur official website kholo."
        val i4 = BrowserCommandParser.parse(cmd4)
        assertTrue("Scenario 4 must produce SearchAndOpen intent", i4 is BrowserUserIntent.SearchAndOpen)
        val s4 = i4 as BrowserUserIntent.SearchAndOpen
        assertTrue(s4.searchQuery.contains("PUBG", ignoreCase = true))
        assertTrue(s4.targetCriterion is com.lichiai.browser.api.TargetCriterion.Official)

        // Scenario 5: "Google par Android ki latest news wali relevant website kholo."
        val cmd5 = "Google par Android ki latest news wali relevant website kholo."
        val i5 = BrowserCommandParser.parse(cmd5)
        assertTrue("Scenario 5 must produce SearchAndOpen intent", i5 is BrowserUserIntent.SearchAndOpen)
        val s5 = i5 as BrowserUserIntent.SearchAndOpen
        assertTrue(s5.searchQuery.contains("Android", ignoreCase = true))
        assertTrue(s5.targetCriterion is com.lichiai.browser.api.TargetCriterion.Relevant)

        // Scenario 6: "Google par iPhone 17 Pro ka price wala result kholo."
        val cmd6 = "Google par iPhone 17 Pro ka price wala result kholo."
        val i6 = BrowserCommandParser.parse(cmd6)
        assertTrue("Scenario 6 must produce SearchAndOpen intent", i6 is BrowserUserIntent.SearchAndOpen)
        val s6 = i6 as BrowserUserIntent.SearchAndOpen
        assertTrue(s6.searchQuery.contains("iPhone 17 Pro", ignoreCase = true))
        assertTrue(s6.targetCriterion is com.lichiai.browser.api.TargetCriterion.Price)

        // Scenario 7: "Doosra result kholo."
        val cmd7 = "Doosra result kholo."
        val i7 = BrowserCommandParser.parse(cmd7)
        assertTrue("Scenario 7 must be ClickCandidate", i7 is BrowserUserIntent.ClickCandidate)
        assertEquals(2, (i7 as BrowserUserIntent.ClickCandidate).index)

        // Scenario 8: "Ab ismein download dhundo."
        val cmd8 = "Ab ismein download dhundo."
        val i8 = BrowserCommandParser.parse(cmd8)
        assertTrue("Scenario 8 must be DownloadTarget or Search", i8 is BrowserUserIntent.DownloadTarget)

        // Scenario 9: "Nahi, pehla wala kholo."
        val cmd9 = "Nahi, pehla wala kholo."
        val i9 = BrowserCommandParser.parse(cmd9)
        assertTrue("Scenario 9 must be ClickCandidate with index 1", i9 is BrowserUserIntent.ClickCandidate)
        assertEquals(1, (i9 as BrowserUserIntent.ClickCandidate).index)
    }

    @Test
    fun testCandidateRanking_GeneralAndZeroHardcoding() {
        val pubgOfficial = com.lichiai.browser.context.BrowserPageCandidate(
            index = 1,
            title = "PUBG: BATTLEGROUNDS - Official Website",
            url = "https://pubg.com",
            snippet = "Official website of PUBG: BATTLEGROUNDS",
            isOfficial = true
        )

        val pubgWiki = com.lichiai.browser.context.BrowserPageCandidate(
            index = 2,
            title = "PUBG - Wikipedia",
            url = "https://en.wikipedia.org/wiki/PUBG",
            snippet = "Wikipedia article about PUBG",
            isOfficial = false
        )

        val officialScore = com.lichiai.browser.verifier.BrowserVerifier.rankCandidate(
            pubgOfficial,
            "PUBG official website",
            com.lichiai.browser.api.TargetCriterion.Official
        )

        val wikiScore = com.lichiai.browser.verifier.BrowserVerifier.rankCandidate(
            pubgWiki,
            "PUBG official website",
            com.lichiai.browser.api.TargetCriterion.Official
        )

        assertTrue("Official website must outrank Wikipedia for official site queries", officialScore > wikiScore)
    }

    @Test
    fun testGoalCompletionVerification() {
        val searchResultsCtx = BrowserTaskContext(
            currentUrl = "https://www.google.com/search?q=PUBG",
            currentTitle = "PUBG - Google Search"
        )
        val searchVerify = com.lichiai.browser.verifier.BrowserVerifier.verifyGoalCompletion(
            goal = "Open official PUBG website",
            targetCriterion = com.lichiai.browser.api.TargetCriterion.Official,
            context = searchResultsCtx,
            pageSnippet = "Google search results for PUBG"
        )
        assertFalse("Search results page must NOT pass destination goal completion", searchVerify.passed)

        val openedSiteCtx = BrowserTaskContext(
            currentUrl = "https://pubg.com",
            currentTitle = "PUBG: BATTLEGROUNDS"
        )
        val openedVerify = com.lichiai.browser.verifier.BrowserVerifier.verifyGoalCompletion(
            goal = "Open official PUBG website",
            targetCriterion = com.lichiai.browser.api.TargetCriterion.Official,
            context = openedSiteCtx,
            pageSnippet = "Welcome to PUBG: BATTLEGROUNDS official website."
        )
        assertTrue("Opened destination page must pass goal completion", openedVerify.passed)

        val errorSiteCtx = BrowserTaskContext(
            currentUrl = "https://broken-example.com",
            currentTitle = "404 Not Found"
        )
        val errorVerify = com.lichiai.browser.verifier.BrowserVerifier.verifyGoalCompletion(
            goal = "Open official website",
            targetCriterion = com.lichiai.browser.api.TargetCriterion.Official,
            context = errorSiteCtx,
            pageSnippet = "The requested page was not found on this server."
        )
        assertFalse("Error 404 page must fail goal completion", errorVerify.passed)
    }

    @Test
    fun testLLMDecisionParsing_AllActions() {
        // Test 1: SEARCH action
        val jsonSearch = """
            {
              "action": "SEARCH",
              "goal": "Find official PUBG website",
              "query": "PUBG official website",
              "engine": "google",
              "summary": "Searching Google for PUBG official website"
            }
        """.trimIndent()
        val d1 = com.lichiai.browser.llm.BrowserLLMClient.parseDecision(jsonSearch)
        assertEquals("SEARCH", d1.action)
        assertEquals("PUBG official website", d1.query)
        assertEquals("google", d1.engine)
        assertEquals("Find official PUBG website", d1.goal)

        // Test 2: CLICK_CANDIDATE action with markdown wrapping
        val jsonClick = """
            ```json
            {
              "action": "CLICK_CANDIDATE",
              "index": 1,
              "summary": "Opening official website result #1"
            }
            ```
        """.trimIndent()
        val d2 = com.lichiai.browser.llm.BrowserLLMClient.parseDecision(jsonClick)
        assertEquals("CLICK_CANDIDATE", d2.action)
        assertEquals(1, d2.index)

        // Test 3: TYPE_TEXT action
        val jsonType = """
            {
              "action": "TYPE_TEXT",
              "text": "Android development",
              "index": 2,
              "submit": true,
              "summary": "Typing query into search input"
            }
        """.trimIndent()
        val d3 = com.lichiai.browser.llm.BrowserLLMClient.parseDecision(jsonType)
        assertEquals("TYPE_TEXT", d3.action)
        assertEquals("Android development", d3.text)
        assertEquals(2, d3.index)
        assertTrue(d3.submit)

        // Test 4: ANSWER action
        val jsonAnswer = """
            {
              "action": "ANSWER",
              "answer": "The official price of iPhone 17 Pro is $1099.",
              "summary": "Found price information"
            }
        """.trimIndent()
        val d4 = com.lichiai.browser.llm.BrowserLLMClient.parseDecision(jsonAnswer)
        assertEquals("ANSWER", d4.action)
        assertEquals("The official price of iPhone 17 Pro is $1099.", d4.answer)

        // Test 5: STOP action
        val jsonStop = """
            {
              "action": "STOP",
              "summary": "PUBG official website open ho gayi hai."
            }
        """.trimIndent()
        val d5 = com.lichiai.browser.llm.BrowserLLMClient.parseDecision(jsonStop)
        assertEquals("STOP", d5.action)
        assertEquals("PUBG official website open ho gayi hai.", d5.summary)
    }

    @Test
    fun testLLMPromptBuilder_StateAndGoalInclusion() {
        val ctx = BrowserTaskContext(
            taskId = "test_prompt_task",
            userGoal = "Browser kholo, Google par PUBG ki official website search karo aur official result kholo",
            currentUrl = "https://www.google.com/search?q=PUBG+official+website",
            currentTitle = "PUBG official website - Google Search",
            lastAction = "search: {query=PUBG official website}",
            lastActionResult = "SUCCESS: Loaded search results",
            extractedCandidates = listOf(
                com.lichiai.browser.context.BrowserPageCandidate(
                    index = 1,
                    title = "PUBG: BATTLEGROUNDS - Official Website",
                    url = "https://pubg.com",
                    isOfficial = true
                )
            ),
            interactiveElements = listOf(
                com.lichiai.browser.context.BrowserInteractiveElement(
                    index = 1,
                    tag = "button",
                    text = "Search"
                )
            )
        )

        val sysPrompt = com.lichiai.browser.llm.BrowserPromptBuilder.buildSystemPrompt()
        assertTrue("System prompt must instruct semantic query separation", sysPrompt.contains("USER GOAL vs SEARCH QUERY"))
        assertTrue("System prompt must require JSON schema", sysPrompt.contains("JSON SCHEMA"))

        val userPrompt = com.lichiai.browser.llm.BrowserPromptBuilder.buildUserPrompt(ctx, "Page snippet about PUBG")
        assertTrue("User prompt must contain original user goal", userPrompt.contains("USER GOAL: \"Browser kholo, Google par PUBG ki official website search karo aur official result kholo\""))
        assertTrue("User prompt must contain candidate link with official tag", userPrompt.contains("#1: \"PUBG: BATTLEGROUNDS - Official Website\" [OFFICIAL]"))
        assertTrue("User prompt must contain interactive element", userPrompt.contains("Element #1 [BUTTON]: \"Search\""))
        assertTrue("User prompt must contain last action", userPrompt.contains("Last Action: search: {query=PUBG official website}"))
    }
}

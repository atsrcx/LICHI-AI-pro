package com.lichiai.web

import com.lichiai.web.model.WebActivityState
import com.lichiai.web.model.WebActivityStatus
import com.lichiai.web.model.WebResult
import com.lichiai.web.model.WebSearchResponse
import com.lichiai.web.planner.FactEvidenceLevel
import com.lichiai.web.planner.QueryPlanner
import com.lichiai.web.planner.WebSearchIntent
import com.lichiai.web.verifier.FactVerificationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebIntelligenceV2UnitTest {

    @Test
    fun testCurrentPriceIntentAndAmbiguity() {
        val prompt = "Abhi internet par dekh kar batao current iPhone rate kya hai?"
        val plan = QueryPlanner.plan(prompt)

        assertEquals(WebSearchIntent.CURRENT_PRICE.name, plan.intent)
        assertEquals("iPhone", plan.entities.product)
        assertTrue("General iPhone query without model/storage should be flagged ambiguous", plan.isAmbiguous)
        assertNotNull(plan.ambiguityReason)
        assertTrue(plan.queries.isNotEmpty())
    }

    @Test
    fun testSpecificProductPriceQueryPlanning() {
        val prompt = "iPhone 17 256GB current price in India"
        val plan = QueryPlanner.plan(prompt)

        assertEquals(WebSearchIntent.CURRENT_PRICE.name, plan.intent)
        assertEquals("iPhone 17", plan.entities.product)
        assertEquals("256GB", plan.entities.storage)
        assertEquals("India", plan.location)
        assertFalse(plan.isAmbiguous)

        val hasOfficialQuery = plan.queries.any { it.siteConstraint == "apple.com/in" }
        assertTrue("Should include official Apple India query", hasOfficialQuery)
    }

    @Test
    fun testFactVerificationMultiSource() {
        val plan = QueryPlanner.plan("iPhone 17 256GB current price in India")
        val results = listOf(
            WebResult(
                title = "Buy iPhone 17 256GB - Apple (India)",
                url = "https://www.apple.com/in/shop/buy-iphone/iphone-17",
                domain = "apple.com/in",
                snippet = "Get iPhone 17 256GB starting from ₹79,900 with trade-in."
            ),
            WebResult(
                title = "Apple iPhone 17 (256 GB) - Flipkart",
                url = "https://www.flipkart.com/apple-iphone-17-256gb",
                domain = "flipkart.com",
                snippet = "Buy Apple iPhone 17 256 GB online at best price of ₹74,999 on Flipkart."
            )
        )

        val evidence = FactVerificationEngine.verify(plan, results)

        assertEquals(FactEvidenceLevel.MULTI_SOURCE_CONFIRMED.name, evidence.evidenceLevel)
        assertEquals("₹79,900", evidence.officialPrice)
        assertEquals("₹74,999", evidence.retailPrice)
        assertNotNull(evidence.disagreementNotice)
        assertTrue(evidence.isCurrent)
        assertFalse(evidence.isHistorical)
    }

    @Test
    fun testFactVerificationHistoricalLaunchPrice() {
        val plan = QueryPlanner.plan("iPhone 16 current price")
        val results = listOf(
            WebResult(
                title = "iPhone 16 Review - Tech Portal",
                url = "https://example.com/review",
                domain = "example.com",
                snippet = "The device was launched at a starting price at launch of ₹79,900 last year."
            )
        )

        val evidence = FactVerificationEngine.verify(plan, results)
        assertTrue("Launch price mentions should be marked as historical", evidence.isHistorical)
    }

    @Test
    fun testNewsAndImageIntentClassification() {
        val newsPrompt = "taaza khabar aaj ki breaking news"
        val newsPlan = QueryPlanner.plan(newsPrompt)
        assertTrue(newsPlan.newsRequired)

        val imagePrompt = "Taj Mahal ki HD photo dikhao"
        val imagePlan = QueryPlanner.plan(imagePrompt)
        assertTrue(imagePlan.imageRequired)
    }

    @Test
    fun testBroadCurrentNewsPlanning() {
        val prompt1 = "Abhi duniya mein kya chal raha hai?"
        val plan1 = QueryPlanner.plan(prompt1)
        assertTrue(plan1.newsRequired)
        assertTrue(plan1.queries.any { it.query.contains("world news", ignoreCase = true) || it.query.contains("breaking news", ignoreCase = true) })

        val prompt2 = "What is happening in the world right now?"
        val plan2 = QueryPlanner.plan(prompt2)
        assertTrue(plan2.newsRequired)
        assertTrue(plan2.queries.isNotEmpty())
    }

    @Test
    fun testWebActivityStateTransitions() {
        val idleState = WebActivityState(status = WebActivityStatus.IDLE)
        assertFalse("IDLE state should not be active", idleState.isActive)

        val searchingState = WebActivityState(
            status = WebActivityStatus.SEARCHING,
            query = "Durga Puja 2026 dates"
        )
        assertTrue("SEARCHING state must be active", searchingState.isActive)

        val completedState = WebActivityState(
            status = WebActivityStatus.COMPLETED,
            completedSources = listOf(
                WebResult(title = "Durga Puja Dates 2026", url = "https://example.com/durgapuja", domain = "example.com", snippet = "October 2026")
            )
        )
        assertFalse("COMPLETED state should not be active (it is finished)", completedState.isActive)
        assertEquals(1, completedState.completedSources.size)
    }
}

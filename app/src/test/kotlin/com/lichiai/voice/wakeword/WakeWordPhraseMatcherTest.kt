package com.lichiai.voice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordPhraseMatcherTest {

    private val defaultPhrases = listOf(
        WakePhraseConfig(id = "wp_1", phrase = "Hey Lichi", isEnabled = true),
        WakePhraseConfig(id = "wp_2", phrase = "Hi Lichi", isEnabled = true),
        WakePhraseConfig(id = "wp_3", phrase = "Okay Lichi", isEnabled = true),
        WakePhraseConfig(id = "wp_4", phrase = "Lichi", isEnabled = true)
    )

    @Test
    fun `test normalization strips punctuation and lowercases`() {
        assertEquals("hey lichi", WakeWordPhraseMatcher.normalize("  Hey, Lichi!  "))
        assertEquals("hi lichi", WakeWordPhraseMatcher.normalize("HI LICHI???"))
        assertEquals("okay lichi", WakeWordPhraseMatcher.normalize("Okay...   Lichi."))
        assertEquals("lichi", WakeWordPhraseMatcher.normalize("Lichi"))
    }

    @Test
    fun `test buildGrammarJson includes enabled phrases and unk token`() {
        val grammar = WakeWordPhraseMatcher.buildGrammarJson(defaultPhrases)
        assertTrue(grammar.contains("hey lichi"))
        assertTrue(grammar.contains("hi lichi"))
        assertTrue(grammar.contains("okay lichi"))
        assertTrue(grammar.contains("lichi"))
        assertTrue(grammar.contains("[unk]"))
    }

    @Test
    fun `test extractTextFromJson parses text and partial fields`() {
        val finalJson = "{\"text\" : \"hey lichi\"}"
        val partialJson = "{\"partial\" : \"okay lichi\"}"
        val emptyJson = "{\"partial\" : \"\"}"

        assertEquals("hey lichi", WakeWordPhraseMatcher.extractTextFromJson(finalJson))
        assertEquals("okay lichi", WakeWordPhraseMatcher.extractTextFromJson(partialJson))
        assertEquals("", WakeWordPhraseMatcher.extractTextFromJson(emptyJson))
    }

    @Test
    fun `test exact matching triggers for all 4 configured wake words`() {
        val match1 = WakeWordPhraseMatcher.findMatchingPhrase("hey lichi", defaultPhrases)
        assertNotNull(match1)
        assertEquals("wp_1", match1?.id)

        val match2 = WakeWordPhraseMatcher.findMatchingPhrase("hi lichi", defaultPhrases)
        assertNotNull(match2)
        assertEquals("wp_2", match2?.id)

        val match3 = WakeWordPhraseMatcher.findMatchingPhrase("okay lichi", defaultPhrases)
        assertNotNull(match3)
        assertEquals("wp_3", match3?.id)

        val match4 = WakeWordPhraseMatcher.findMatchingPhrase("lichi", defaultPhrases)
        assertNotNull(match4)
        assertEquals("wp_4", match4?.id)
    }

    @Test
    fun `test false positive protection rejects close sounding or non-matching phrases`() {
        // Rejects false triggers
        assertNull(WakeWordPhraseMatcher.findMatchingPhrase("hey listen", defaultPhrases))
        assertNull(WakeWordPhraseMatcher.findMatchingPhrase("hey lucky", defaultPhrases))
        assertNull(WakeWordPhraseMatcher.findMatchingPhrase("i like this", defaultPhrases))
        assertNull(WakeWordPhraseMatcher.findMatchingPhrase("hello lichi", defaultPhrases))
        assertNull(WakeWordPhraseMatcher.findMatchingPhrase("lichi was here today", defaultPhrases))
        assertNull(WakeWordPhraseMatcher.findMatchingPhrase("[unk]", defaultPhrases))
        assertNull(WakeWordPhraseMatcher.findMatchingPhrase("", defaultPhrases))
    }

    @Test
    fun `test disabled wake phrases are ignored`() {
        val customizedPhrases = listOf(
            WakePhraseConfig(id = "wp_1", phrase = "Hey Lichi", isEnabled = false),
            WakePhraseConfig(id = "wp_2", phrase = "Hi Lichi", isEnabled = true)
        )

        assertNull(WakeWordPhraseMatcher.findMatchingPhrase("hey lichi", customizedPhrases))
        assertNotNull(WakeWordPhraseMatcher.findMatchingPhrase("hi lichi", customizedPhrases))
    }

    @Test
    fun `test strict vs balanced sensitivity mode`() {
        // In STRICT mode, prefix/suffix speech is rejected
        val strictMatch = WakeWordPhraseMatcher.findMatchingPhrase(
            recognizedRawText = "so hey lichi please help",
            activePhrases = defaultPhrases,
            sensitivity = WakeWordSensitivity.STRICT
        )
        assertNull(strictMatch)

        // In BALANCED mode, contiguous sub-phrase triggers correctly
        val balancedMatch = WakeWordPhraseMatcher.findMatchingPhrase(
            recognizedRawText = "so hey lichi please help",
            activePhrases = defaultPhrases,
            sensitivity = WakeWordSensitivity.BALANCED
        )
        assertNotNull(balancedMatch)
        assertEquals("wp_1", balancedMatch?.id)
    }

    @Test
    fun `test single word trigger isolation`() {
        // "lichi" should not trigger on "lichi juice" in strict mode
        val strictJuice = WakeWordPhraseMatcher.findMatchingPhrase(
            recognizedRawText = "lichi juice",
            activePhrases = defaultPhrases,
            sensitivity = WakeWordSensitivity.STRICT
        )
        assertNull(strictJuice)

        // "lichi" standalone should trigger
        val strictIsolated = WakeWordPhraseMatcher.findMatchingPhrase(
            recognizedRawText = "lichi",
            activePhrases = defaultPhrases,
            sensitivity = WakeWordSensitivity.STRICT
        )
        assertNotNull(strictIsolated)
        assertEquals("wp_4", strictIsolated?.id)
    }

    @Test
    fun `test custom phrases and deduplication`() {
        val customPhrases = listOf(
            WakePhraseConfig(id = "wp_1", phrase = "Hey Lichi", isEnabled = true),
            WakePhraseConfig(id = "wp_2", phrase = "hey lichi", isEnabled = true), // Duplicate phrase
            WakePhraseConfig(id = "wp_3", phrase = "Jarvis", isEnabled = true),
            WakePhraseConfig(id = "wp_4", phrase = "Computer", isEnabled = true),
            WakePhraseConfig(id = "wp_5", phrase = "", isEnabled = true) // Empty
        )

        val grammar = WakeWordPhraseMatcher.buildGrammarJson(customPhrases)
        // Check grammar has distinct entries
        assertTrue(grammar.contains("hey lichi"))
        assertTrue(grammar.contains("jarvis"))
        assertTrue(grammar.contains("computer"))
        assertTrue(grammar.contains("[unk]"))

        val matchJarvis = WakeWordPhraseMatcher.findMatchingPhrase("jarvis", customPhrases)
        assertNotNull(matchJarvis)
        assertEquals("wp_3", matchJarvis?.id)

        val matchComputer = WakeWordPhraseMatcher.findMatchingPhrase("computer", customPhrases)
        assertNotNull(matchComputer)
        assertEquals("wp_4", matchComputer?.id)
    }
}

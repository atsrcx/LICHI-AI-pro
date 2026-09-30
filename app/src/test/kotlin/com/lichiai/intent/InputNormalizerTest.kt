package com.lichiai.intent

import com.lichiai.intent.normalizer.InputNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class InputNormalizerTest {

    @Test
    fun testInputNormalizer() {
        val raw = "Browser   kholo   aur google per pubg game search karo  "
        val normalized = InputNormalizer.normalize(raw)
        assertEquals("browser kholo aur google par pubg game search karo", normalized)
    }

    @Test
    fun testInputNormalizerPunctuationAndSpacing() {
        val raw = "  Call   Rahul   now!!!  "
        val normalized = InputNormalizer.normalize(raw)
        assertEquals("call rahul now!!!", normalized)
    }
}

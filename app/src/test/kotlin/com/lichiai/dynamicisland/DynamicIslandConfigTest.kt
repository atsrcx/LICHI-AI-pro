package com.lichiai.dynamicisland

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicIslandConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun testDefaultConfig_isValid() {
        val config = DynamicIslandConfig()
        assertFalse(config.enabled)
        assertEquals(IslandShape.PILL, config.shape)
        assertEquals(180, config.widthDp)
        assertEquals(42, config.heightDp)
        assertFalse(config.glowEnabled)
        assertTrue(config.snapToEdges)
    }

    @Test
    fun testSanitization_clampsOutOfBoundsValues() {
        val badConfig = DynamicIslandConfig(
            widthDp = 1000,
            heightDp = 5,
            overallAlpha = 2.5f,
            backgroundAlpha = -0.5f,
            borderWidthDp = 50f,
            autoCollapseDelaySec = 100,
            xFraction = 1.5f,
            yFraction = -0.2f
        )
        val sanitized = badConfig.sanitized()

        assertEquals(sanitized.maxWidthDp, sanitized.widthDp)
        assertEquals(sanitized.minHeightDp, sanitized.heightDp)
        assertEquals(1.0f, sanitized.overallAlpha, 0.001f)
        assertEquals(0.1f, sanitized.backgroundAlpha, 0.001f)
        assertEquals(6.0f, sanitized.borderWidthDp, 0.001f)
        assertEquals(15, sanitized.autoCollapseDelaySec)
        assertEquals(1.0f, sanitized.xFraction, 0.001f)
        assertEquals(0.0f, sanitized.yFraction, 0.001f)
    }

    @Test
    fun testPresets_applyCorrectly() {
        val base = DynamicIslandConfig()

        val cyber = DynamicIslandConfig.applyPreset(IslandPreset.CYBERPUNK, base)
        assertEquals("#0D0221", cyber.backgroundColorHex)
        assertEquals("#00F0FF", cyber.primaryColorHex)
        assertEquals(IslandShape.CUT_CORNER, cyber.shape)

        val glass = DynamicIslandConfig.applyPreset(IslandPreset.GLASS, base)
        assertEquals(0.55f, glass.backgroundAlpha, 0.01f)
        assertEquals(IslandShape.PILL, glass.shape)

        val mono = DynamicIslandConfig.applyPreset(IslandPreset.MONOCHROME, base)
        assertFalse(mono.glowEnabled)
        assertEquals("#FFFFFF", mono.primaryColorHex)
    }

    @Test
    fun testJsonSerialization_roundTrip() {
        val original = DynamicIslandConfig(
            preset = IslandPreset.CYBERPUNK,
            widthDp = 220,
            heightDp = 48,
            primaryColorHex = "#FF007F",
            snapToEdges = true
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<DynamicIslandConfig>(serialized)

        assertEquals(original.preset, deserialized.preset)
        assertEquals(original.widthDp, deserialized.widthDp)
        assertEquals(original.heightDp, deserialized.heightDp)
        assertEquals(original.primaryColorHex, deserialized.primaryColorHex)
        assertEquals(original.snapToEdges, deserialized.snapToEdges)
    }
}

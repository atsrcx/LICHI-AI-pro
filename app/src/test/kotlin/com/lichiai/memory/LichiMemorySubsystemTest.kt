package com.lichiai.memory

import com.lichiai.memory.model.BiTemporalWindow
import com.lichiai.memory.model.MemoryCategory
import com.lichiai.memory.model.MemoryItem
import com.lichiai.memory.model.MemoryStatus
import com.lichiai.memory.pipeline.SecretFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LichiMemorySubsystemTest {

    @Test
    fun testSecretFilter_redactsApiKeysAndPrivateTokens() {
        val raw = "Here is my openai key sk-abc12345678901234567890 and google key AIzaSyD98765432101234567890123456789012"
        val scrubbed = SecretFilter.scrub(raw)
        assertFalse(scrubbed.contains("sk-abc12345678901234567890"))
        assertFalse(scrubbed.contains("AIzaSyD98765432101234567890123456789012"))
        assertTrue(scrubbed.contains("[REDACTED_SECRET]"))
    }

    @Test
    fun testSecretFilter_redactsGitHubTokensAndBearerAuth() {
        val raw = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.xyz and ghp_123456789012345678901234567890123456"
        val scrubbed = SecretFilter.scrub(raw)
        assertFalse(scrubbed.contains("ghp_123456789012345678901234567890123456"))
        assertTrue(scrubbed.contains("[REDACTED_SECRET]"))
    }

    @Test
    fun testBiTemporalWindow_validityRanges() {
        val now = 1000000L
        val windowIndefinite = BiTemporalWindow(observedAt = now, validFrom = null, validUntil = null)
        assertTrue(windowIndefinite.isValidAt(now))
        assertTrue(windowIndefinite.isValidAt(now + 50000))

        val windowBounded = BiTemporalWindow(observedAt = now, validFrom = now, validUntil = now + 1000)
        assertTrue(windowBounded.isValidAt(now + 500))
        assertFalse(windowBounded.isValidAt(now + 2000))
        assertFalse(windowBounded.isValidAt(now - 100))
    }

    @Test
    fun testMemoryItem_statuses() {
        val item = MemoryItem(
            id = "test-1",
            key = "user_name",
            value = "Alex",
            category = MemoryCategory.FACT,
            status = MemoryStatus.ACTIVE
        )
        assertEquals(MemoryStatus.ACTIVE, item.status)
        assertEquals("user_name", item.key)
        assertEquals("Alex", item.value)
    }
}

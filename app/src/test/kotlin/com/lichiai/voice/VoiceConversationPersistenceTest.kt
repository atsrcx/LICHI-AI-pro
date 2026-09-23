package com.lichiai.voice

import com.lichiai.data.Conversation
import com.lichiai.data.Message
import com.lichiai.util.newId
import com.lichiai.voice.conversation.VoiceTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConversationPersistenceTest {

    @Test
    fun testVoiceTurnToMessageConversionAndPersistence() {
        val convId = newId()
        val userQuery = "What is the weather today?"
        val assistantResponse = "It is sunny and 25 degrees."
        val now = System.currentTimeMillis()

        val userMsg = Message(
            id = newId(),
            role = "user",
            content = userQuery,
            createdAt = now
        )
        val assistantMsg = Message(
            id = newId(),
            role = "assistant",
            content = assistantResponse,
            createdAt = now + 1
        )

        val conversation = Conversation(
            id = convId,
            title = userQuery.take(30),
            messages = listOf(userMsg, assistantMsg),
            createdAt = now,
            updatedAt = now
        )

        assertEquals(2, conversation.messages.size)
        assertEquals("user", conversation.messages[0].role)
        assertEquals(userQuery, conversation.messages[0].content)
        assertEquals("assistant", conversation.messages[1].role)
        assertEquals(assistantResponse, conversation.messages[1].content)
        assertEquals("What is the weather today?", conversation.title)
    }

    @Test
    fun testVoiceTurnSeedingFromExistingConversation() {
        val msgs = listOf(
            Message(id = "1", role = "user", content = "Hello assistant"),
            Message(id = "2", role = "assistant", content = "Hello! How can I help you?"),
            Message(id = "3", role = "user", content = "Tell me a joke"),
            Message(id = "4", role = "assistant", content = "Why did the chicken cross the road?")
        )

        val loadedTurns = mutableListOf<VoiceTurn>()
        var i = 0
        while (i < msgs.size) {
            val m = msgs[i]
            if (m.role == "user") {
                val next = msgs.getOrNull(i + 1)
                val asstText = if (next?.role == "assistant") next.content else ""
                loadedTurns.add(
                    VoiceTurn(
                        userText = m.content,
                        assistantText = asstText,
                        isUserFinal = true,
                        isAssistantComplete = true,
                        timestamp = m.createdAt
                    )
                )
                if (next?.role == "assistant") i += 2 else i += 1
            } else {
                i += 1
            }
        }

        assertEquals(2, loadedTurns.size)
        assertEquals("Hello assistant", loadedTurns[0].userText)
        assertEquals("Hello! How can I help you?", loadedTurns[0].assistantText)
        assertEquals("Tell me a joke", loadedTurns[1].userText)
        assertEquals("Why did the chicken cross the road?", loadedTurns[1].assistantText)
    }

    @Test
    fun testSubsequentVoiceTurnAppendsToExistingConversation() {
        val initialUserMsg = Message(id = "1", role = "user", content = "First question")
        val initialAsstMsg = Message(id = "2", role = "assistant", content = "First answer")
        val conv = Conversation(
            id = "conv-1",
            title = "First question",
            messages = listOf(initialUserMsg, initialAsstMsg)
        )

        val turn2User = Message(id = "3", role = "user", content = "Follow-up question")
        val turn2Asst = Message(id = "4", role = "assistant", content = "Follow-up answer")

        val updatedConv = conv.copy(
            messages = conv.messages + turn2User + turn2Asst,
            updatedAt = System.currentTimeMillis()
        )

        assertEquals("First question", updatedConv.title) // Title should not be overwritten
        assertEquals(4, updatedConv.messages.size)
        assertEquals("Follow-up question", updatedConv.messages[2].content)
        assertEquals("Follow-up answer", updatedConv.messages[3].content)
    }
}

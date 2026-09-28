package com.lichiai.data

import com.lichiai.spy.model.PlatformProfile
import com.lichiai.ui.activity.AssistantActivityState
import com.lichiai.web.model.WebActivityState
import kotlinx.serialization.Serializable

enum class Role { user, assistant, system }

@Serializable
data class Attachment(
    val type: String,        // "image" | "file"
    val uri: String,         // content:// or file path
    val mimeType: String,
    val name: String,
    val sizeBytes: Long = 0
)

@Serializable
data class Message(
    val id: String,
    val role: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val webActivity: WebActivityState? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val requestId: String? = null,
    val taskActivity: AssistantActivityState? = null,
    val spyProfile: PlatformProfile? = null,
    val spyProfiles: List<PlatformProfile> = emptyList()
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val conversationStateJson: String? = null
)

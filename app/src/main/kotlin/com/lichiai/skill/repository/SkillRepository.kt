package com.lichiai.skill.repository

import android.content.Context
import android.util.Log
import com.lichiai.skill.model.Skill
import com.lichiai.skill.model.SkillRiskLevel
import com.lichiai.skill.model.SkillSource
import com.lichiai.skill.model.SkillVersion
import com.lichiai.skill.parser.SkillParser
import com.lichiai.skill.validator.SkillValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class SkillMetadataEntity(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val source: String,
    val enabled: Boolean,
    val riskLevel: String,
    val createdAt: Long,
    val updatedAt: Long,
    val versionHistory: List<SkillVersion> = emptyList(),
    val userEditable: Boolean = true
)

/**
 * Sandboxed, persistent repository for Lichi Skill System V1.
 *
 * File Structure:
 * filesDir/skills/
 *   ├── system/
 *   │   └── <skill-id>/
 *   │       ├── SKILL.md
 *   │       └── metadata.json
 *   ├── user/
 *   ├── imported/
 *   └── generated/
 */
class SkillRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillRepository"
        private const val DIR_SKILLS = "skills"
        private const val FILE_SKILL_MD = "SKILL.md"
        private const val FILE_META_JSON = "metadata.json"

        @Volatile
        private var instance: SkillRepository? = null

        fun getInstance(context: Context): SkillRepository {
            return instance ?: synchronized(this) {
                instance ?: SkillRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val rootDir = File(context.filesDir, DIR_SKILLS).apply {
        if (!exists()) mkdirs()
    }

    private val systemDir = File(rootDir, "system").apply { if (!exists()) mkdirs() }
    private val userDir = File(rootDir, "user").apply { if (!exists()) mkdirs() }
    private val importedDir = File(rootDir, "imported").apply { if (!exists()) mkdirs() }
    private val generatedDir = File(rootDir, "generated").apply { if (!exists()) mkdirs() }

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                seedSystemSkillsIfEmpty()
                reloadSkillsFromDisk()
            }
        }
    }

    private fun getSubDirForSource(source: SkillSource): File {
        return when (source) {
            SkillSource.SYSTEM -> systemDir
            SkillSource.USER -> userDir
            SkillSource.IMPORTED -> importedDir
            SkillSource.AGENT_GENERATED -> generatedDir
        }
    }

    private fun reloadSkillsFromDisk() {
        val loaded = mutableListOf<Skill>()
        val allSubDirs = listOf(systemDir, userDir, importedDir, generatedDir)

        for (subDir in allSubDirs) {
            val skillFolders = subDir.listFiles() ?: continue
            for (folder in skillFolders) {
                if (!folder.isDirectory) continue
                val skillFile = File(folder, FILE_SKILL_MD)
                if (!skillFile.exists()) continue

                val mdContent = runCatching { skillFile.readText() }.getOrNull() ?: continue
                val metaFile = File(folder, FILE_META_JSON)
                val meta = if (metaFile.exists()) {
                    runCatching {
                        json.decodeFromString<SkillMetadataEntity>(metaFile.readText())
                    }.getOrNull()
                } else null

                val parsed = SkillParser.parse(
                    markdown = mdContent,
                    defaultId = folder.name,
                    source = when (subDir.name) {
                        "system" -> SkillSource.SYSTEM
                        "imported" -> SkillSource.IMPORTED
                        "generated" -> SkillSource.AGENT_GENERATED
                        else -> SkillSource.USER
                    }
                )

                val combined = if (meta != null) {
                    parsed.copy(
                        id = meta.id,
                        name = meta.name.ifBlank { parsed.name },
                        description = meta.description.ifBlank { parsed.description },
                        version = meta.version.ifBlank { parsed.version },
                        enabled = meta.enabled,
                        riskLevel = runCatching { SkillRiskLevel.valueOf(meta.riskLevel) }.getOrDefault(parsed.riskLevel),
                        createdAt = meta.createdAt,
                        updatedAt = meta.updatedAt,
                        versionHistory = meta.versionHistory,
                        userEditable = meta.userEditable
                    )
                } else {
                    parsed
                }

                loaded.add(combined)
            }
        }

        _skills.value = loaded.sortedWith(
            compareBy<Skill> { it.source != SkillSource.SYSTEM }
                .thenBy { it.name.lowercase() }
        )
    }

    suspend fun createSkill(markdown: String, source: SkillSource = SkillSource.USER): Result<Skill> = mutex.withLock {
        val validation = SkillValidator.validate(markdown)
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.errors.joinToString(", ")))
        }

        val skill = SkillParser.parse(validation.sanitizedMarkdown, source = source)
        val targetDir = File(getSubDirForSource(source), skill.id)
        if (targetDir.exists()) {
            return Result.failure(IllegalStateException("A skill with ID '${skill.id}' already exists."))
        }

        targetDir.mkdirs()
        File(targetDir, FILE_SKILL_MD).writeText(skill.markdownContent)
        writeMetadata(targetDir, skill)

        reloadSkillsFromDisk()
        Log.i(TAG, "Created skill: ${skill.name} (${skill.id}) from $source")
        Result.success(skill)
    }

    suspend fun updateSkill(skillId: String, newMarkdown: String, changeReason: String): Result<Skill> = mutex.withLock {
        val existing = _skills.value.firstOrNull { it.id == skillId }
            ?: return Result.failure(NoSuchElementException("Skill '$skillId' not found."))

        if (existing.source == SkillSource.SYSTEM && !existing.userEditable) {
            return Result.failure(IllegalStateException("System skills cannot be modified."))
        }

        val validation = SkillValidator.validate(newMarkdown, skillId)
        if (!validation.isValid) {
            return Result.failure(IllegalArgumentException(validation.errors.joinToString(", ")))
        }

        val currentVersion = existing.version
        val nextVersion = incrementVersion(currentVersion)

        val newHistory = existing.versionHistory + SkillVersion(
            version = currentVersion,
            markdownContent = existing.markdownContent,
            timestamp = System.currentTimeMillis(),
            changeReason = changeReason.ifBlank { "User update" }
        )

        val parsed = SkillParser.parse(validation.sanitizedMarkdown, defaultId = skillId, source = existing.source)
        val updatedSkill = parsed.copy(
            id = skillId,
            version = nextVersion,
            enabled = existing.enabled,
            source = existing.source,
            createdAt = existing.createdAt,
            updatedAt = System.currentTimeMillis(),
            versionHistory = newHistory,
            userEditable = existing.userEditable
        )

        val targetDir = File(getSubDirForSource(existing.source), skillId)
        if (!targetDir.exists()) targetDir.mkdirs()

        File(targetDir, FILE_SKILL_MD).writeText(updatedSkill.markdownContent)
        writeMetadata(targetDir, updatedSkill)

        reloadSkillsFromDisk()
        Log.i(TAG, "Updated skill: ${updatedSkill.name} to v${updatedSkill.version}")
        Result.success(updatedSkill)
    }

    suspend fun rollbackSkill(skillId: String, targetVersion: String): Result<Skill> = mutex.withLock {
        val existing = _skills.value.firstOrNull { it.id == skillId }
            ?: return Result.failure(NoSuchElementException("Skill '$skillId' not found."))

        val targetHistorical = existing.versionHistory.firstOrNull { it.version == targetVersion }
            ?: return Result.failure(NoSuchElementException("Version '$targetVersion' not found in history of skill '$skillId'."))

        val restoredHistory = existing.versionHistory + SkillVersion(
            version = existing.version,
            markdownContent = existing.markdownContent,
            timestamp = System.currentTimeMillis(),
            changeReason = "Rollback to version $targetVersion"
        )

        val restored = SkillParser.parse(targetHistorical.markdownContent, defaultId = skillId, source = existing.source).copy(
            id = skillId,
            version = targetVersion,
            enabled = existing.enabled,
            source = existing.source,
            createdAt = existing.createdAt,
            updatedAt = System.currentTimeMillis(),
            versionHistory = restoredHistory,
            userEditable = existing.userEditable
        )

        val targetDir = File(getSubDirForSource(existing.source), skillId)
        File(targetDir, FILE_SKILL_MD).writeText(restored.markdownContent)
        writeMetadata(targetDir, restored)

        reloadSkillsFromDisk()
        Log.i(TAG, "Rolled back skill: ${restored.name} to v$targetVersion")
        Result.success(restored)
    }

    suspend fun toggleSkill(skillId: String, enabled: Boolean): Result<Skill> = mutex.withLock {
        val existing = _skills.value.firstOrNull { it.id == skillId }
            ?: return Result.failure(NoSuchElementException("Skill '$skillId' not found."))

        val updated = existing.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
        val targetDir = File(getSubDirForSource(existing.source), skillId)
        writeMetadata(targetDir, updated)

        reloadSkillsFromDisk()
        Log.i(TAG, "Skill ${existing.name} enabled state changed to: $enabled")
        Result.success(updated)
    }

    suspend fun deleteSkill(skillId: String): Result<Unit> = mutex.withLock {
        val existing = _skills.value.firstOrNull { it.id == skillId }
            ?: return Result.failure(NoSuchElementException("Skill '$skillId' not found."))

        if (existing.source == SkillSource.SYSTEM) {
            return Result.failure(IllegalStateException("System skills are protected and cannot be deleted."))
        }

        val targetDir = File(getSubDirForSource(existing.source), skillId)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }

        reloadSkillsFromDisk()
        Log.i(TAG, "Deleted skill: ${existing.name} ($skillId)")
        Result.success(Unit)
    }

    suspend fun exportSkill(skillId: String): Result<String> = mutex.withLock {
        val existing = _skills.value.firstOrNull { it.id == skillId }
            ?: return Result.failure(NoSuchElementException("Skill '$skillId' not found."))

        Result.success(SkillParser.toMarkdown(existing))
    }

    private fun writeMetadata(targetDir: File, skill: Skill) {
        val entity = SkillMetadataEntity(
            id = skill.id,
            name = skill.name,
            description = skill.description,
            version = skill.version,
            source = skill.source.name,
            enabled = skill.enabled,
            riskLevel = skill.riskLevel.name,
            createdAt = skill.createdAt,
            updatedAt = skill.updatedAt,
            versionHistory = skill.versionHistory,
            userEditable = skill.userEditable
        )
        File(targetDir, FILE_META_JSON).writeText(json.encodeToString(entity))
    }

    private fun incrementVersion(current: String): String {
        val parts = current.split(".")
        return if (parts.size == 2) {
            val major = parts[0].toIntOrNull() ?: 1
            val minor = (parts[1].toIntOrNull() ?: 0) + 1
            "$major.$minor"
        } else {
            "1.1"
        }
    }

    private fun seedSystemSkillsIfEmpty() {
        val instagramDir = File(systemDir, "instagram-assistant")
        if (!instagramDir.exists()) {
            instagramDir.mkdirs()
            val md = """
                # Instagram Assistant

                ## Purpose
                Automate messaging and navigation on Instagram safely and reliably.

                ## When to use
                Use when the user asks to send a direct message, search a user, or interact with Instagram.

                ## When NOT to use
                Do NOT use for general knowledge questions about Instagram or account deletion.

                ## Workflow
                1. Open Instagram using package "com.instagram.android".
                2. Wait for the feed or home UI to settle.
                3. Locate and tap the Direct Messages / Chat icon or search icon.
                4. Find the requested contact by name in the chat list or search bar.
                5. Open the conversation with the contact.
                6. Type the exact requested message into the text composer.
                7. Verify that the composer contains the expected message before sending.
                8. Tap Send and verify that the message appears in the conversation history.

                ## Safety
                - Never send a blank message.
                - Never claim the message was sent without verifying it appears in the chat thread.
            """.trimIndent()
            File(instagramDir, FILE_SKILL_MD).writeText(md)
            val parsed = SkillParser.parse(md, defaultId = "instagram-assistant", source = SkillSource.SYSTEM)
            writeMetadata(instagramDir, parsed.copy(riskLevel = SkillRiskLevel.HIGH, userEditable = false))
        }

        val whatsappDir = File(systemDir, "whatsapp-assistant")
        if (!whatsappDir.exists()) {
            whatsappDir.mkdirs()
            val md = """
                # WhatsApp Assistant

                ## Purpose
                Send messages and navigate conversations on WhatsApp.

                ## When to use
                Use when the user explicitly requests sending a WhatsApp message or checking chats.

                ## When NOT to use
                Do NOT use for normal voice calls (UniversalCallEngine handles calls).

                ## Workflow
                1. Open WhatsApp using package "com.whatsapp".
                2. Tap the search icon or new chat button.
                3. Type the contact name to filter conversations.
                4. Tap on the matching contact to open the chat window.
                5. Type the message content into the message input field.
                6. Verify the input field contents carefully.
                7. Tap the Send button.
                8. Verify the message bubble appears with a clock, single, or double check mark.

                ## Safety
                - Always verify the recipient contact name before typing or sending.
                - Never report completion if the send button was not tapped.
            """.trimIndent()
            File(whatsappDir, FILE_SKILL_MD).writeText(md)
            val parsed = SkillParser.parse(md, defaultId = "whatsapp-assistant", source = SkillSource.SYSTEM)
            writeMetadata(whatsappDir, parsed.copy(riskLevel = SkillRiskLevel.HIGH, userEditable = false))
        }

        val youtubeDir = File(systemDir, "youtube-assistant")
        if (!youtubeDir.exists()) {
            youtubeDir.mkdirs()
            val md = """
                # YouTube Assistant

                ## Purpose
                Search for videos, songs, and content on YouTube and initiate playback.

                ## When to use
                Use when the user asks to play a video, watch a song, or search content on YouTube.

                ## When NOT to use
                Do NOT use for Spotify or audio streaming when music app is specified.

                ## Workflow
                1. Open YouTube using package "com.google.android.youtube".
                2. Tap the search icon in the top app bar.
                3. Type the search terms provided by the user.
                4. Submit search and inspect video search results.
                5. Tap the most relevant video title or thumbnail.
                6. Verify video playback begins.

                ## Safety
                - Ensure the selected video matches the user's intent.
            """.trimIndent()
            File(youtubeDir, FILE_SKILL_MD).writeText(md)
            val parsed = SkillParser.parse(md, defaultId = "youtube-assistant", source = SkillSource.SYSTEM)
            writeMetadata(youtubeDir, parsed.copy(riskLevel = SkillRiskLevel.LOW, userEditable = false))
        }
    }
}

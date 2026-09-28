package com.lichiai.agent.fs

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Isolated, sandboxed file system for Autonomous Agent V2.
 *
 * All operations are strictly confined within context.filesDir/agent_workspace/.
 * Enforces path traversal prevention.
 *
 * Maintains:
 * - todo.md: Task checklist (rewritten on update)
 * - results.md: Accumulated results (appended without duplication)
 */
class AgentFileSystem(context: Context) {

    companion object {
        private const val TAG = "AgentFileSystem"
        private const val WORKSPACE_DIR_NAME = "agent_workspace"
        const val FILE_TODO = "todo.md"
        const val FILE_RESULTS = "results.md"
    }

    private val workspaceDir: File = File(context.filesDir, WORKSPACE_DIR_NAME).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    /**
     * Resets or prepares the workspace for a fresh agent task.
     */
    fun initNewTask(userGoal: String) {
        try {
            writeTodo("# Task: $userGoal\n\n- [ ] Analyze goal and initiate perception\n")
            val resultsFile = resolveSafeFile(FILE_RESULTS)
            if (!resultsFile.exists()) {
                resultsFile.writeText("# Results & Findings\n\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed initializing workspace", e)
        }
    }

    /**
     * Verifies that the requested relative path resolves inside the sandboxed workspace.
     * Throws SecurityException if path traversal is detected.
     */
    @Throws(SecurityException::class)
    fun resolveSafeFile(relativePath: String): File {
        val sanitized = relativePath.trim().removePrefix("/").removePrefix("./")
        val target = File(workspaceDir, sanitized)
        val canonicalTarget = target.canonicalPath
        val canonicalWorkspace = workspaceDir.canonicalPath

        if (!canonicalTarget.startsWith(canonicalWorkspace)) {
            throw SecurityException("Access denied: path traversal attempt for path '$relativePath'")
        }
        return target
    }

    fun readFile(relativePath: String): String {
        return try {
            val file = resolveSafeFile(relativePath)
            if (file.exists()) file.readText(Charsets.UTF_8) else ""
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file '$relativePath'", e)
            "Error reading file: ${e.message}"
        }
    }

    fun writeFile(relativePath: String, content: String, append: Boolean = false): Boolean {
        return try {
            val file = resolveSafeFile(relativePath)
            file.parentFile?.mkdirs()
            if (append) {
                file.appendText(content, Charsets.UTF_8)
            } else {
                file.writeText(content, Charsets.UTF_8)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing file '$relativePath'", e)
            false
        }
    }

    fun readTodo(): String = readFile(FILE_TODO)

    /**
     * Updates todo.md by completely rewriting it according to agent specifications.
     */
    fun writeTodo(content: String): Boolean {
        return writeFile(FILE_TODO, content, append = false)
    }

    fun readResults(): String = readFile(FILE_RESULTS)

    /**
     * Appends finding to results.md without duplicating existing lines.
     */
    fun appendResults(newFinding: String): Boolean {
        return try {
            val current = readResults()
            val trimmedFinding = newFinding.trim()
            if (trimmedFinding.isBlank()) return true

            if (current.contains(trimmedFinding)) {
                // Already recorded, avoid duplication
                return true
            }

            val toAppend = "\n- $trimmedFinding\n"
            writeFile(FILE_RESULTS, toAppend, append = true)
        } catch (e: Exception) {
            Log.e(TAG, "Error appending to results.md", e)
            false
        }
    }

    fun getFileSystemSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("=== todo.md ===")
        val todo = readTodo()
        sb.appendLine(if (todo.isBlank()) "(Empty)" else todo)
        sb.appendLine("=== results.md ===")
        val res = readResults()
        sb.appendLine(if (res.isBlank()) "(Empty)" else res)
        return sb.toString()
    }
}

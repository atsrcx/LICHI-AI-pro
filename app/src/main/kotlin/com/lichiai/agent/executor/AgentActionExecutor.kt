package com.lichiai.agent.executor

import android.content.Context
import android.content.Intent
import android.util.Log
import com.lichiai.agent.accessibility.LichiAccessibilityService
import com.lichiai.agent.fs.AgentFileSystem
import com.lichiai.agent.model.AgentAction
import com.lichiai.agent.model.AndroidState
import com.lichiai.agentvision.coordinate.CoordinateMapper
import com.lichiai.agentvision.model.AgentVisualEvent
import com.lichiai.agentvision.model.VisualActionType
import com.lichiai.agentvision.model.VisualCoordinateSpace
import com.lichiai.agentvision.model.VisualPhase
import com.lichiai.agentvision.model.VisualPosition
import com.lichiai.agentvision.model.VisualSource
import com.lichiai.agentvision.telemetry.AgentVisionTelemetryHub
import kotlinx.coroutines.delay

/**
 * Executes structured Agent actions against the Android system and sandboxed workspace.
 *
 * Enforces strict action validation against the catalog.
 */
class AgentActionExecutor(
    private val context: Context,
    private val fileSystem: AgentFileSystem,
    private val webIntelligenceManager: com.lichiai.web.WebIntelligenceManager = com.lichiai.web.WebIntelligenceManager.getInstance(context)
) {

    companion object {
        private const val TAG = "AgentActionExecutor"
        val ALLOWED_ACTIONS = setOf(
            "tap", "type", "scroll", "back", "home", "recents",
            "open_app", "wait", "read_element", "read_file", "write_file", "done",
            "web_search", "web_images", "web_news", "web_extract", "web_research",
            "web_verify", "web_compare", "web_open"
        )

        private val KNOWN_MAPPINGS = mapOf(
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "instagram app" to "com.instagram.android",
            "इंस्टाग्राम" to "com.instagram.android",
            "whatsapp" to "com.whatsapp",
            "wp" to "com.whatsapp",
            "whatsapp app" to "com.whatsapp",
            "whatsapp messenger" to "com.whatsapp",
            "व्हाट्सएप" to "com.whatsapp",
            "व्हाट्सऐप" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "telegram app" to "org.telegram.messenger",
            "टेलीग्राम" to "org.telegram.messenger",
            "settings" to "com.android.settings",
            "phone settings" to "com.android.settings",
            "सेटिंग्स" to "com.android.settings",
            "setting" to "com.android.settings",
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "youtube app" to "com.google.android.youtube",
            "google youtube" to "com.google.android.youtube",
            "यूट्यूब" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "spotify app" to "com.spotify.music",
            "spotify music" to "com.spotify.music",
            "स्पॉटिफ़ाई" to "com.spotify.music",
            "chrome" to "com.android.chrome",
            "chrome browser" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "क्रोम" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "google mail" to "com.google.android.gm",
            "जीमेल" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "मैप्स" to "com.google.android.apps.maps",
            "messages" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging",
            "मैसेज" to "com.google.android.apps.messaging",
            "clock" to "com.google.android.deskclock",
            "alarm" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator",
            "camera" to "com.google.android.GoogleCamera",
            "twitter" to "com.twitter.android",
            "ट्विटर" to "com.twitter.android",
            "x" to "com.twitter.android",
            "uber" to "com.ubercab",
            "उबर" to "com.ubercab",
            "zomato" to "com.application.zomato",
            "ज़ोमैटो" to "com.application.zomato",
            "swiggy" to "in.swiggy.android",
            "स्विगी" to "in.swiggy.android"
        )

        private val resolvedPackageCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    var isDone: Boolean = false
        private set
    var doneSummary: String = ""
        private set

    fun reset() {
        isDone = false
        doneSummary = ""
    }

    /**
     * Executes an agent action and returns an observation string.
     */
    suspend fun executeAction(
        action: AgentAction,
        currentState: AndroidState
    ): String {
        val name = action.name.trim().lowercase()

        // 1. Strict catalog validation
        if (name !in ALLOWED_ACTIONS) {
            return "ERROR: Unknown action '$name'. Allowed actions are: ${ALLOWED_ACTIONS.joinToString(", ")}"
        }

        val accessibility = LichiAccessibilityService.getInstance()

        return try {
            when (name) {
                "tap" -> {
                    val idx = action.paramInt("element_index", -1)
                    if (idx < 0) return "ERROR: Missing or invalid 'element_index' parameter for tap."
                    if (accessibility == null) {
                        return "ERROR: Accessibility service is not running. Cannot tap UI."
                    }

                    // Extract real bounds
                    val rect = accessibility.getNodeBounds(idx)
                    val element = currentState.elements.firstOrNull { it.index == idx }
                    val targetBounds = if (rect != null) CoordinateMapper.mapAndroidBoundsToScreen(rect)
                    else element?.bounds?.let { CoordinateMapper.parseBoundsString(it) }
                    val targetPos = CoordinateMapper.calculateCenter(targetBounds)
                    val label = element?.text?.ifBlank { element.contentDescription } ?: "element #$idx"

                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            source = VisualSource.ANDROID_AGENT,
                            actionType = VisualActionType.TAP,
                            phase = VisualPhase.STARTED,
                            targetPosition = targetPos,
                            targetBounds = targetBounds,
                            targetIdentifier = label,
                            operationalDescription = "Clicking $label",
                            isPositionAvailable = targetBounds != null
                        )
                    )

                    val success = accessibility.performTap(idx)
                    delay(500) // allow UI to respond

                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            source = VisualSource.ANDROID_AGENT,
                            actionType = VisualActionType.TAP,
                            phase = if (success) VisualPhase.COMPLETED else VisualPhase.FAILED,
                            targetPosition = targetPos,
                            targetBounds = targetBounds,
                            targetIdentifier = label,
                            operationalDescription = if (success) "Clicked $label" else "Failed to click $label",
                            resultSummary = if (success) "Tapped element [$idx]." else "Tap failed"
                        )
                    )

                    if (success) "Tapped element [$idx]." else "FAILED: Tap on element [$idx] failed or element disappeared."
                }

                "type" -> {
                    val idx = action.paramInt("element_index", -1)
                    val text = action.param("text", "")
                    if (idx < 0) return "ERROR: Missing or invalid 'element_index' for type."
                    if (accessibility == null) {
                        return "ERROR: Accessibility service is not running. Cannot type text."
                    }

                    val rect = accessibility.getNodeBounds(idx)
                    val element = currentState.elements.firstOrNull { it.index == idx }
                    val targetBounds = if (rect != null) CoordinateMapper.mapAndroidBoundsToScreen(rect)
                    else element?.bounds?.let { CoordinateMapper.parseBoundsString(it) }
                    val targetPos = CoordinateMapper.calculateCenter(targetBounds)

                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            source = VisualSource.ANDROID_AGENT,
                            actionType = VisualActionType.TYPE,
                            phase = VisualPhase.EXECUTING,
                            targetPosition = targetPos,
                            targetBounds = targetBounds,
                            textLength = text.length,
                            typedMaskedText = text,
                            operationalDescription = "Typing into field...",
                            isPositionAvailable = targetBounds != null
                        )
                    )

                    val success = accessibility.performType(idx, text)
                    delay(300)

                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            source = VisualSource.ANDROID_AGENT,
                            actionType = VisualActionType.TYPE,
                            phase = if (success) VisualPhase.COMPLETED else VisualPhase.FAILED,
                            targetPosition = targetPos,
                            targetBounds = targetBounds,
                            operationalDescription = if (success) "Typed text" else "Typing failed",
                            resultSummary = if (success) "Typed into element [$idx]." else "Failed to type"
                        )
                    )

                    if (success) "Typed \"$text\" into element [$idx]." else "FAILED: Unable to set text on element [$idx]."
                }

                "scroll" -> {
                    val dir = action.param("direction", "DOWN").uppercase()
                    if (accessibility == null) {
                        return "ERROR: Accessibility service is not running. Cannot scroll."
                    }

                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            source = VisualSource.ANDROID_AGENT,
                            actionType = VisualActionType.SCROLL,
                            phase = VisualPhase.EXECUTING,
                            scrollDeltaY = if (dir == "DOWN") 1f else -1f,
                            operationalDescription = "Scrolling screen $dir"
                        )
                    )

                    val success = accessibility.performScroll(dir)
                    delay(600)

                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            source = VisualSource.ANDROID_AGENT,
                            actionType = VisualActionType.SCROLL,
                            phase = if (success) VisualPhase.COMPLETED else VisualPhase.FAILED,
                            operationalDescription = if (success) "Scrolled $dir" else "Scroll failed"
                        )
                    )

                    if (success) "Scrolled screen $dir." else "FAILED: Scroll $dir failed."
                }

                "back" -> {
                    if (accessibility == null) return "ERROR: Accessibility service not available for back."
                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            source = VisualSource.ANDROID_AGENT,
                            actionType = VisualActionType.BACK,
                            phase = VisualPhase.EXECUTING,
                            operationalDescription = "Navigating Back"
                        )
                    )
                    val success = accessibility.performBack()
                    delay(500)
                    if (success) "Pressed Back." else "FAILED: Back gesture failed."
                }

                "home" -> {
                    if (accessibility == null) return "ERROR: Accessibility service not available for home."
                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            source = VisualSource.ANDROID_AGENT,
                            actionType = VisualActionType.NAVIGATE,
                            phase = VisualPhase.EXECUTING,
                            operationalDescription = "Navigating Home"
                        )
                    )
                    val success = accessibility.performHome()
                    delay(500)
                    if (success) "Navigated Home." else "FAILED: Home gesture failed."
                }

                "recents" -> {
                    if (accessibility == null) return "ERROR: Accessibility service not available for recents."
                    val success = accessibility.performRecents()
                    delay(500)
                    if (success) "Opened Recent Apps." else "FAILED: Recents gesture failed."
                }

                "open_app" -> {
                    val rawPkg = action.param("package_name", "").ifBlank { action.param("app_name", "") }.trim()
                    if (rawPkg.isBlank()) return "ERROR: 'package_name' parameter missing for open_app."
                    val pkg = resolvePackageName(rawPkg)

                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            source = VisualSource.ANDROID_AGENT,
                            actionType = VisualActionType.NAVIGATE,
                            phase = VisualPhase.STARTED,
                            operationalDescription = "Opening $rawPkg"
                        )
                    )

                    val pm = context.packageManager
                    val launchIntent = pm.getLaunchIntentForPackage(pkg)
                    if (launchIntent == null) {
                        // Check if package exists at all
                        val isInstalled = try {
                            pm.getPackageInfo(pkg, 0)
                            true
                        } catch (_: Throwable) {
                            false
                        }
                        return if (isInstalled) {
                            "APP_LAUNCH_FAILED: App '$pkg' is installed but cannot be launched directly via default launcher intent."
                        } else {
                            "APP_NOT_INSTALLED: App '$rawPkg' ($pkg) is not installed on this device. Fallback to Browser or Web Search is available."
                        }
                    }

                    try {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        delay(1200) // Wait for app window to render

                        val fgPkg = accessibility?.getActivePackage() ?: ""
                        if (fgPkg.isNotBlank() && !fgPkg.equals(pkg, ignoreCase = true) && !pkg.contains(fgPkg) && !fgPkg.contains(pkg)) {
                            "APP_LAUNCHED_WRONG_STATE: Sent launch intent for '$pkg', but foreground package is '$fgPkg'. Please re-perceive screen."
                        } else {
                            "APP_LAUNCHED: Successfully launched app '$pkg'. Foreground verified."
                        }
                    } catch (e: Exception) {
                        "APP_LAUNCH_FAILED: Exception while launching '$pkg': ${e.message}"
                    }
                }

                "wait" -> {
                    val sec = action.paramInt("seconds", 2).coerceIn(1, 10)
                    delay(sec * 1000L)
                    "Waited $sec second(s)."
                }

                "read_element" -> {
                    val idx = action.paramInt("element_index", -1)
                    val elem = currentState.elements.firstOrNull { it.index == idx }
                    if (elem != null) {
                        "Element [$idx]: ${elem.toReadableString()}"
                    } else {
                        "Element [$idx] not found in active screen hierarchy."
                    }
                }

                "read_file" -> {
                    val filename = action.param("filename", "todo.md")
                    val content = fileSystem.readFile(filename)
                    "File '$filename' content:\n$content"
                }

                "write_file" -> {
                    val filename = action.param("filename", "")
                    val content = action.param("content", "")
                    val mode = action.param("mode", "overwrite").lowercase()
                    if (filename.isBlank()) return "ERROR: 'filename' required for write_file."

                    if (filename == AgentFileSystem.FILE_TODO) {
                        fileSystem.writeTodo(content)
                        "Updated todo.md."
                    } else if (filename == AgentFileSystem.FILE_RESULTS) {
                        fileSystem.appendResults(content)
                        "Appended entry to results.md."
                    } else {
                        val append = mode == "append"
                        val success = fileSystem.writeFile(filename, content, append = append)
                        if (success) "Wrote to '$filename'." else "FAILED writing to '$filename'."
                    }
                }

                "done" -> {
                    isDone = true
                    doneSummary = action.param("summary", "Task completed.")
                    AgentVisionTelemetryHub.emitEvent(
                        AgentVisualEvent(
                            source = VisualSource.ANDROID_AGENT,
                            actionType = VisualActionType.SUCCESS,
                            phase = VisualPhase.COMPLETED,
                            operationalDescription = "Task completed",
                            resultSummary = doneSummary
                        )
                    )
                    "Task marked as DONE: $doneSummary"
                }

                "web_search" -> {
                    val q = action.param("query", "")
                    if (q.isBlank()) return "ERROR: 'query' required for web_search."
                    val resp = webIntelligenceManager.executeSearch(q, isImageSearch = false)
                    val text = resp.results.take(5).joinToString("\n\n") { r ->
                        "[${r.citationId}] ${r.title} (${r.domain}):\n${r.snippet}"
                    }
                    if (text.isBlank()) "No web results found for \"$q\"." else "Web Search Results for \"$q\":\n$text"
                }

                "web_images" -> {
                    val q = action.param("query", "")
                    if (q.isBlank()) return "ERROR: 'query' required for web_images."
                    val resp = webIntelligenceManager.executeSearch(q, isImageSearch = true)
                    val text = resp.images.take(5).joinToString("\n") { img ->
                        "- [Image: ${img.title}] URL: ${img.imageUrl} (${img.sourceDomain})"
                    }
                    if (text.isBlank()) "No images found for \"$q\"." else "Image Results for \"$q\":\n$text"
                }

                "web_news" -> {
                    val q = action.param("query", "")
                    if (q.isBlank()) return "ERROR: 'query' required for web_news."
                    val resp = webIntelligenceManager.executeSearch(q, isNewsSearch = true)
                    val text = resp.results.take(5).joinToString("\n\n") { r ->
                        "- ${r.title} (${r.domain}) [${r.publishedAt ?: "recent"}]:\n  ${r.snippet}"
                    }
                    if (text.isBlank()) "No news found for \"$q\"." else "News Results for \"$q\":\n$text"
                }

                "web_extract", "web_open" -> {
                    val url = action.param("url", "")
                    if (url.isBlank()) return "ERROR: 'url' required for $name."
                    val contentMap = webIntelligenceManager.extractContent(listOf(url))
                    val text = contentMap[url] ?: "Could not extract content from $url"
                    "Extracted Content ($url):\n${text.take(800)}"
                }

                "web_research" -> {
                    val q = action.param("query", "")
                    if (q.isBlank()) return "ERROR: 'query' required for web_research."
                    val resp = webIntelligenceManager.executeSearch(q)
                    val formatted = webIntelligenceManager.buildWebContextPrompt(resp)
                    if (formatted.isBlank()) "No research findings for \"$q\"." else "Deep Web Research for \"$q\":\n$formatted"
                }

                "web_verify" -> {
                    val claim = action.param("claim", action.param("query", ""))
                    if (claim.isBlank()) return "ERROR: 'claim' required for web_verify."
                    val resp = webIntelligenceManager.executeSearch(claim)
                    val fact = resp.factEvidence
                    val sb = StringBuilder()
                    sb.append("Web Fact Verification for: \"$claim\"\n")
                    if (fact != null) {
                        sb.append("Evidence Level: ${fact.evidenceLevel}\n")
                        if (fact.primaryValue != null) sb.append("Primary Verified Value: ${fact.primaryValue}\n")
                        if (fact.officialPrice != null) sb.append("Official Price (${fact.officialDomain}): ${fact.officialPrice}\n")
                        if (fact.retailPrice != null) sb.append("Retailer Price (${fact.retailDomain}): ${fact.retailPrice}\n")
                        if (fact.disagreementNotice != null) sb.append("Disagreement Note: ${fact.disagreementNotice}\n")
                    } else {
                        sb.append("Status: Unverified / Insufficient Evidence\n")
                    }
                    sb.append("Verified Sources: ${resp.results.size} checked.")
                    sb.toString()
                }

                "web_compare" -> {
                    val itemA = action.param("item_a", "")
                    val itemB = action.param("item_b", "")
                    val query = if (itemA.isNotBlank() && itemB.isNotBlank()) "$itemA vs $itemB comparison" else action.param("query", "")
                    if (query.isBlank()) return "ERROR: 'item_a' and 'item_b' (or 'query') required for web_compare."
                    val resp = webIntelligenceManager.executeSearch(query)
                    val formatted = webIntelligenceManager.buildWebContextPrompt(resp)
                    if (formatted.isBlank()) "No comparison findings for \"$query\"." else "Factual Web Comparison for \"$query\":\n$formatted"
                }

                else -> "ERROR: Unhandled action '$name'."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing action $name", e)
            "ERROR executing action '$name': ${e.message}"
        }
    }

    private fun resolvePackageName(input: String): String {
        var lower = input.lowercase().trim()
        for (prefix in listOf("mere phone ki ", "mere phone mein ", "mere phone me ", "phone ki ", "phone mein ", "open ", "launch ", "chalao ")) {
            if (lower.startsWith(prefix)) {
                lower = lower.removePrefix(prefix).trim()
            }
        }
        for (suffix in listOf(" app", " application", " kholo", " chalao", " open karo", " laga do", " lagao")) {
            if (lower.endsWith(suffix)) {
                lower = lower.removeSuffix(suffix).trim()
            }
        }

        KNOWN_MAPPINGS[lower]?.let { return it }
        resolvedPackageCache[lower]?.let { return it }

        if (lower.contains(".") && !lower.contains(" ")) {
            return input.trim()
        }
        try {
            val pm = context.packageManager
            // Tier 1: Query Launcher Intent Activities
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
            for (info in resolveInfos) {
                val label = info.loadLabel(pm).toString().lowercase().trim()
                val pkg = info.activityInfo.packageName.lowercase().trim()
                if (label == lower || pkg == lower || label.contains(lower) || pkg.contains(lower)) {
                    resolvedPackageCache[lower] = info.activityInfo.packageName
                    return info.activityInfo.packageName
                }
            }

            // Tier 2: Query Installed Applications fallback
            val installedApps = pm.getInstalledApplications(0)
            val match = installedApps.firstOrNull { appInfo ->
                appInfo.packageName.lowercase().contains(lower) ||
                    pm.getApplicationLabel(appInfo).toString().lowercase().contains(lower)
            }
            if (match != null) {
                resolvedPackageCache[lower] = match.packageName
                return match.packageName
            }
        } catch (_: Throwable) {}
        return input.trim()
    }
}

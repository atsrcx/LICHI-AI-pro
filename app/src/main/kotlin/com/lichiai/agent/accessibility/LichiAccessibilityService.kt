package com.lichiai.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lichiai.agent.model.AndroidElement
import java.util.concurrent.atomic.AtomicInteger

/**
 * LichiAccessibilityService provides system-level perception and interaction
 * for Autonomous Agent V2.
 *
 * Implements:
 * - Window node hierarchy inspection
 * - Interactive element indexing (0..N)
 * - Click, setText, scroll, and system navigation gestures
 */
class LichiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LichiAccessibility"

        @Volatile
        private var instance: LichiAccessibilityService? = null

        fun getInstance(): LichiAccessibilityService? = instance

        fun isServiceRunning(): Boolean = instance != null
    }

    private var activePackageName: String = ""
    private var activeActivityName: String = ""

    fun getActivePackage(): String = rootInActiveWindow?.packageName?.toString() ?: activePackageName

    // Stores node mapping for the current perception cycle
    private val indexedNodes = mutableMapOf<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "LichiAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { activePackageName = it.toString() }
            event.className?.let { activeActivityName = it.toString() }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "LichiAccessibilityService interrupted")
        clearCachedNodes()
    }

    override fun onDestroy() {
        Log.i(TAG, "LichiAccessibilityService destroyed")
        clearCachedNodes()
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }

    private fun clearCachedNodes() {
        try {
            indexedNodes.values.forEach {
                try { it.recycle() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        indexedNodes.clear()
    }

    /**
     * Inspects active window and returns list of indexed interactive UI elements.
     */
    @Synchronized
    fun captureInteractiveElements(): Pair<String, List<AndroidElement>> {
        clearCachedNodes()
        val elements = mutableListOf<AndroidElement>()
        val root = rootInActiveWindow ?: return Pair(activePackageName, emptyList())

        val indexCounter = AtomicInteger(0)
        try {
            traverseNode(root, indexCounter, elements)
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing accessibility tree", e)
        }

        val pkg = root.packageName?.toString() ?: activePackageName
        return Pair(pkg, elements)
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        counter: AtomicInteger,
        outList: MutableList<AndroidElement>
    ) {
        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val resourceId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isCheckable = node.isCheckable
        val isChecked = node.isChecked
        val isVisible = node.isVisibleToUser

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val boundsStr = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]"

        val isInteractive = isClickable || isEditable || isCheckable ||
                (text.isNotBlank() && (rect.width() > 0 && rect.height() > 0)) ||
                (contentDesc.isNotBlank() && (rect.width() > 0 && rect.height() > 0))

        if (isVisible && isInteractive && rect.width() > 0 && rect.height() > 0) {
            val idx = counter.getAndIncrement()
            // Save node clone for execution
            val nodeCopy = AccessibilityNodeInfo.obtain(node)
            indexedNodes[idx] = nodeCopy

            outList.add(
                AndroidElement(
                    index = idx,
                    className = className,
                    text = text,
                    contentDescription = contentDesc,
                    resourceId = resourceId,
                    bounds = boundsStr,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    isCheckable = isCheckable,
                    isChecked = isChecked,
                    isEnabled = node.isEnabled
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, counter, outList)
        }
    }

    /**
     * Gets screen bounds for the element with index [elementIndex].
     */
    fun getNodeBounds(elementIndex: Int): Rect? {
        val node = indexedNodes[elementIndex] ?: return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return if (rect.width() > 0 && rect.height() > 0) rect else null
    }

    /**
     * Performs a click/tap on the element with index [elementIndex].
     */
    fun performTap(elementIndex: Int): Boolean {
        val node = indexedNodes[elementIndex]
        if (node != null) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            // If node isn't directly clickable, click parent
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                parent = parent.parent
            }
            // Fallback: Gesture tap on center of node bounds
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                return performGestureClick(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }
        return false
    }

    /**
     * Types text into the element with index [elementIndex].
     */
    fun performType(elementIndex: Int, text: String): Boolean {
        val node = indexedNodes[elementIndex] ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return true
        }
        // Fallback: focus then set text
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Performs a scroll on the active screen or scrollable container.
     */
    fun performScroll(direction: String): Boolean {
        val dir = direction.uppercase()
        val root = rootInActiveWindow ?: return false

        // Try node scroll action first
        val scrollAction = if (dir == "DOWN" || dir == "RIGHT") {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        val scrollableNode = findScrollableNode(root)
        if (scrollableNode != null && scrollableNode.performAction(scrollAction)) {
            return true
        }

        // Gesture swipe fallback
        val metrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.75f
        val endY = metrics.heightPixels * 0.25f

        val path = Path()
        when (dir) {
            "DOWN" -> {
                path.moveTo(centerX, startY)
                path.lineTo(centerX, endY)
            }
            "UP" -> {
                path.moveTo(centerX, endY)
                path.lineTo(centerX, startY)
            }
            "RIGHT" -> {
                path.moveTo(metrics.widthPixels * 0.8f, metrics.heightPixels / 2f)
                path.lineTo(metrics.widthPixels * 0.2f, metrics.heightPixels / 2f)
            }
            "LEFT" -> {
                path.moveTo(metrics.widthPixels * 0.2f, metrics.heightPixels / 2f)
                path.lineTo(metrics.widthPixels * 0.8f, metrics.heightPixels / 2f)
            }
            else -> {
                path.moveTo(centerX, startY)
                path.lineTo(centerX, endY)
            }
        }
        return dispatchGesturePath(path, 350)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun performGestureClick(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        return dispatchGesturePath(path, 50)
    }

    private fun dispatchGesturePath(path: Path, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
}

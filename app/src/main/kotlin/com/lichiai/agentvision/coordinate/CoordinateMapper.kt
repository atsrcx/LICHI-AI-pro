package com.lichiai.agentvision.coordinate

import android.graphics.Rect
import com.lichiai.agentvision.model.VisualBounds
import com.lichiai.agentvision.model.VisualCoordinateSpace
import com.lichiai.agentvision.model.VisualPosition

/**
 * CoordinateMapper provides robust, zero-guess conversions between coordinate spaces:
 * - DOM (CSS pixels in WebView viewport)
 * - WEB_VIEW (Android View pixels relative to WebView origin)
 * - COMPOSE (Coordinates relative to Compose parent layout)
 * - WINDOW (Window decor relative coordinates)
 * - SCREEN (Global physical screen pixels)
 */
object CoordinateMapper {

    /**
     * Converts DOM bounding rect into Screen coordinates given WebView offset and scale.
     */
    fun mapDomToScreen(
        domRect: VisualBounds,
        webViewScreenX: Float,
        webViewScreenY: Float,
        contentScale: Float = 1.0f
    ): VisualBounds {
        if (!domRect.isValid()) return VisualBounds.ZERO
        val scaledLeft = domRect.left * contentScale
        val scaledTop = domRect.top * contentScale
        val scaledRight = domRect.right * contentScale
        val scaledBottom = domRect.bottom * contentScale

        return VisualBounds(
            left = webViewScreenX + scaledLeft,
            top = webViewScreenY + scaledTop,
            right = webViewScreenX + scaledRight,
            bottom = webViewScreenY + scaledBottom
        )
    }

    /**
     * Maps Android AccessibilityNodeInfo screen bounds to VisualBounds.
     */
    fun mapAndroidBoundsToScreen(rect: Rect): VisualBounds {
        if (rect.right <= rect.left || rect.bottom <= rect.top) {
            return VisualBounds.ZERO
        }
        return VisualBounds(
            left = rect.left.toFloat(),
            top = rect.top.toFloat(),
            right = rect.right.toFloat(),
            bottom = rect.bottom.toFloat()
        )
    }

    /**
     * Parses bounds string from format "[left,top][right,bottom]" (Android accessibility)
     * or "top,left,width,height" (Browser DOM).
     */
    fun parseBoundsString(boundsStr: String): VisualBounds? {
        val trimmed = boundsStr.trim()
        if (trimmed.isBlank()) return null

        // 1. Android style: "[120,450][340,510]"
        if (trimmed.startsWith("[") && trimmed.contains("][") && trimmed.endsWith("]")) {
            return try {
                val parts = trimmed.removePrefix("[").removeSuffix("]").split("][")
                if (parts.size == 2) {
                    val p1 = parts[0].split(",")
                    val p2 = parts[1].split(",")
                    if (p1.size == 2 && p2.size == 2) {
                        val left = p1[0].trim().toFloat()
                        val top = p1[1].trim().toFloat()
                        val right = p2[0].trim().toFloat()
                        val bottom = p2[1].trim().toFloat()
                        if (right > left && bottom > top) {
                            VisualBounds(left, top, right, bottom)
                        } else null
                    } else null
                } else null
            } catch (_: Throwable) {
                null
            }
        }

        // 2. DOM style: "top,left,width,height" e.g. "120,45,200,40"
        if (trimmed.contains(",")) {
            return try {
                val p = trimmed.split(",").map { it.trim().toFloat() }
                if (p.size == 4) {
                    val top = p[0]
                    val left = p[1]
                    val width = p[2]
                    val height = p[3]
                    if (width > 0 && height > 0) {
                        VisualBounds.fromLtwh(left, top, width, height)
                    } else null
                } else null
            } catch (_: Throwable) {
                null
            }
        }

        return null
    }

    /**
     * Converts a coordinate from one space to target space given translation offsets.
     */
    fun transformPosition(
        pos: VisualPosition,
        fromSpace: VisualCoordinateSpace,
        toSpace: VisualCoordinateSpace,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        scale: Float = 1.0f
    ): VisualPosition {
        if (fromSpace == toSpace) return pos

        var currentX = pos.x
        var currentY = pos.y

        // Normalize to intermediate Screen space
        if (fromSpace == VisualCoordinateSpace.DOM || fromSpace == VisualCoordinateSpace.WEB_VIEW) {
            currentX = (currentX * scale) + offsetX
            currentY = (currentY * scale) + offsetY
        } else if (fromSpace == VisualCoordinateSpace.COMPOSE) {
            currentX += offsetX
            currentY += offsetY
        }

        // Convert from Screen to target
        if (toSpace == VisualCoordinateSpace.COMPOSE) {
            currentX -= offsetX
            currentY -= offsetY
        }

        return VisualPosition(currentX, currentY)
    }

    /**
     * Calculates center position for given VisualBounds, or null if bounds are invalid.
     */
    fun calculateCenter(bounds: VisualBounds?): VisualPosition? {
        if (bounds == null || !bounds.isValid()) return null
        return VisualPosition(bounds.centerX, bounds.centerY)
    }
}

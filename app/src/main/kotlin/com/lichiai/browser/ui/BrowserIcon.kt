package com.lichiai.browser.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val BrowserGlobeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "BrowserGlobe",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Outer circle
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f
        ) {
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
        }
        // Equator horizontal line
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f
        ) {
            moveTo(2f, 12f)
            lineTo(22f, 12f)
        }
        // Central meridian ellipse
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f
        ) {
            moveTo(12f, 2f)
            curveTo(8.5f, 5f, 6.5f, 8.5f, 6.5f, 12f)
            curveTo(6.5f, 15.5f, 8.5f, 19f, 12f, 22f)
            curveTo(15.5f, 19f, 17.5f, 15.5f, 17.5f, 12f)
            curveTo(17.5f, 8.5f, 15.5f, 5f, 12f, 2f)
            close()
        }
    }.build()
}

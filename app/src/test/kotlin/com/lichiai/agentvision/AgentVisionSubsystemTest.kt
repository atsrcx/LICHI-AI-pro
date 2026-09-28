package com.lichiai.agentvision

import android.graphics.Rect
import com.lichiai.agentvision.coordinate.CoordinateMapper
import com.lichiai.agentvision.model.AgentVisionPreset
import com.lichiai.agentvision.model.AgentVisionSettings
import com.lichiai.agentvision.model.AgentVisualEvent
import com.lichiai.agentvision.model.CursorShape
import com.lichiai.agentvision.model.CursorSize
import com.lichiai.agentvision.model.CursorStyleConfig
import com.lichiai.agentvision.model.GlowLevel
import com.lichiai.agentvision.model.MovementSpeed
import com.lichiai.agentvision.model.TargetHighlightStyle
import com.lichiai.agentvision.model.VisualActionType
import com.lichiai.agentvision.model.VisualBounds
import com.lichiai.agentvision.model.VisualCoordinateSpace
import com.lichiai.agentvision.model.VisualPhase
import com.lichiai.agentvision.model.VisualPosition
import com.lichiai.agentvision.model.VisualSource
import com.lichiai.agentvision.telemetry.AgentVisionTelemetryHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentVisionSubsystemTest {

    @Before
    fun setUp() {
        AgentVisionTelemetryHub.resetAll()
    }

    @Test
    fun testVisualBoundsCalculations() {
        val bounds = VisualBounds(left = 100f, top = 200f, right = 300f, bottom = 400f)
        assertTrue(bounds.isValid())
        assertEquals(200f, bounds.width, 0.001f)
        assertEquals(200f, bounds.height, 0.001f)
        assertEquals(200f, bounds.centerX, 0.001f)
        assertEquals(300f, bounds.centerY, 0.001f)

        val zeroBounds = VisualBounds.ZERO
        assertFalse(zeroBounds.isValid())
    }

    @Test
    fun testCoordinateMapperBoundsParsing() {
        // 1. Android Accessibility Node format: [left,top][right,bottom]
        val androidBounds = CoordinateMapper.parseBoundsString("[100,200][300,450]")
        assertNotNull(androidBounds)
        assertEquals(100f, androidBounds!!.left, 0.001f)
        assertEquals(200f, androidBounds.top, 0.001f)
        assertEquals(300f, androidBounds.right, 0.001f)
        assertEquals(450f, androidBounds.bottom, 0.001f)

        // 2. DOM format: top,left,width,height
        val domBounds = CoordinateMapper.parseBoundsString("50,20,180,60")
        assertNotNull(domBounds)
        assertEquals(20f, domBounds!!.left, 0.001f)
        assertEquals(50f, domBounds.top, 0.001f)
        assertEquals(200f, domBounds.right, 0.001f)
        assertEquals(110f, domBounds.bottom, 0.001f)

        // 3. Invalid inputs
        assertNull(CoordinateMapper.parseBoundsString(""))
        assertNull(CoordinateMapper.parseBoundsString("invalid"))
        assertNull(CoordinateMapper.parseBoundsString("[0,0][0,0]"))
    }

    @Test
    fun testCoordinateMapperDomToScreenTransformation() {
        val domRect = VisualBounds.fromLtwh(50f, 100f, 200f, 40f)
        val mapped = CoordinateMapper.mapDomToScreen(
            domRect = domRect,
            webViewScreenX = 0f,
            webViewScreenY = 120f,
            contentScale = 1.5f
        )

        assertEquals(75f, mapped.left, 0.001f) // 50 * 1.5 + 0
        assertEquals(270f, mapped.top, 0.001f) // 100 * 1.5 + 120
        assertEquals(375f, mapped.right, 0.001f) // 250 * 1.5 + 0
        assertEquals(330f, mapped.bottom, 0.001f) // 140 * 1.5 + 120
    }

    @Test
    fun testAndroidRectMapping() {
        val rect = Rect().apply {
            left = 50
            top = 100
            right = 350
            bottom = 200
        }
        val visualBounds = CoordinateMapper.mapAndroidBoundsToScreen(rect)
        assertTrue(visualBounds.isValid())
        assertEquals(50f, visualBounds.left, 0.001f)
        assertEquals(100f, visualBounds.top, 0.001f)
        assertEquals(350f, visualBounds.right, 0.001f)
        assertEquals(200f, visualBounds.bottom, 0.001f)
    }

    @Test
    fun testAgentVisionTelemetryHubTaskIsolation() {
        val taskA = "task_alpha"
        val taskB = "task_beta"

        // Emit event for Task A
        AgentVisionTelemetryHub.emitEvent(
            AgentVisualEvent(
                taskId = taskA,
                source = VisualSource.ANDROID_AGENT,
                actionType = VisualActionType.TAP,
                targetPosition = VisualPosition(100f, 200f),
                operationalDescription = "Task A click"
            )
        )

        // Emit event for Task B
        AgentVisionTelemetryHub.emitEvent(
            AgentVisualEvent(
                taskId = taskB,
                source = VisualSource.BROWSER_AGENT,
                actionType = VisualActionType.TYPE,
                targetPosition = VisualPosition(300f, 400f),
                operationalDescription = "Task B typing"
            )
        )

        val sessionA = AgentVisionTelemetryHub.getSession(taskA)
        val sessionB = AgentVisionTelemetryHub.getSession(taskB)

        assertNotNull(sessionA)
        assertNotNull(sessionB)

        assertEquals(taskA, sessionA!!.taskId)
        assertEquals(VisualSource.ANDROID_AGENT, sessionA.source)
        assertEquals(100f, sessionA.cursorPosition.x, 0.001f)
        assertEquals("Task A click", sessionA.statusText)

        assertEquals(taskB, sessionB!!.taskId)
        assertEquals(VisualSource.BROWSER_AGENT, sessionB.source)
        assertEquals(300f, sessionB.cursorPosition.x, 0.001f)
        assertEquals("Task B typing", sessionB.statusText)
    }

    @Test
    fun testSensitiveDataRedaction() {
        val taskId = "privacy_test_task"

        AgentVisionTelemetryHub.emitEvent(
            AgentVisualEvent(
                taskId = taskId,
                source = VisualSource.ANDROID_AGENT,
                actionType = VisualActionType.TYPE,
                operationalDescription = "Typing password: SecretPassword123! token=abc_xyz_token",
                typedMaskedText = "super_secret_pin"
            )
        )

        val session = AgentVisionTelemetryHub.getSession(taskId)
        assertNotNull(session)
        assertFalse(session!!.statusText.contains("SecretPassword123!"))
        assertFalse(session.statusText.contains("abc_xyz_token"))
        assertTrue(session.statusText.contains("[PROTECTED]"))
        assertFalse(session.typingMasked.contains("super_secret_pin"))
    }

    @Test
    fun testPresetConfigurations() {
        val lichiConfig = AgentVisionSettings.getPresetConfig(AgentVisionPreset.LICHI_DEFAULT)
        assertEquals(CursorShape.LICHI, lichiConfig.shape)
        assertEquals(0xFF2563EBL, lichiConfig.colorArgb)

        val classicConfig = AgentVisionSettings.getPresetConfig(AgentVisionPreset.DESKTOP_CLASSIC)
        assertEquals(CursorShape.CLASSIC_ARROW, classicConfig.shape)
        assertEquals(TargetHighlightStyle.OUTLINE, classicConfig.targetHighlightStyle)

        val devConfig = AgentVisionSettings.getPresetConfig(AgentVisionPreset.DEVELOPER)
        assertEquals(CursorShape.CROSSHAIR, devConfig.shape)
        assertTrue(devConfig.coordinateDisplay)
        assertTrue(devConfig.developerDebugInfo)

        val accessibilityConfig = AgentVisionSettings.getPresetConfig(AgentVisionPreset.ACCESSIBILITY)
        assertEquals(CursorSize.EXTRA_LARGE, accessibilityConfig.size)
        assertEquals(TargetHighlightStyle.PULSE, accessibilityConfig.targetHighlightStyle)
    }

    @Test
    fun testReplayDataSafety() {
        val taskId = "replay_task"

        AgentVisionTelemetryHub.emitEvent(
            AgentVisualEvent(
                taskId = taskId,
                actionType = VisualActionType.MOVE,
                phase = VisualPhase.STARTED
            )
        )
        AgentVisionTelemetryHub.emitEvent(
            AgentVisualEvent(
                taskId = taskId,
                actionType = VisualActionType.TAP,
                phase = VisualPhase.COMPLETED,
                resultSummary = "Clicked search"
            )
        )

        val replay = AgentVisionTelemetryHub.getReplayData(taskId)
        assertNotNull(replay)
        assertEquals(2, replay!!.events.size)
        assertEquals(VisualActionType.MOVE, replay.events[0].actionType)
        assertEquals(VisualActionType.TAP, replay.events[1].actionType)
    }
}

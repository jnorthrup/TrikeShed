package narchy.spacegraph.navigation

import borg.trikeshed.lib.*
import narchy.spacegraph.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Expected values are upstream packages/navigation/src/follow.test.ts, copied by hand.


class FollowTest {
    private val positionOf: (String) -> Vec3? = { id ->
        when (id) {
            "node-1" -> Vec3(100.0, 200.0, 0.0)
            "node-2" -> Vec3(300.0, 400.0, 0.0)
            else -> null
        }
    }
    private val newNodeIds = _l["node-1", "node-2"]

    // describe('computeFollowFrame')
    @Test fun returnsNullWhenFollowModeIsOff() {
        assertNull(computeFollowFrame(newNodeIds, true, FollowMode.OFF, positionOf))
    }

    @Test fun returnsNullWhenNoNewNodes() {
        assertNull(computeFollowFrame(emptyList(), true, FollowMode.NODE, positionOf))
    }

    @Test fun returnsNullWhenNoCurrentViewport() {
        assertNull(computeFollowFrame(newNodeIds, false, FollowMode.NODE, positionOf))
    }

    @Test fun returnsCameraMoveForNodeModeWithNewestNode() {
        val result = assertNotNull(computeFollowFrame(newNodeIds, true, FollowMode.NODE, positionOf))
        assertEquals("node-2", result.nodeId)
        assertFalse(result.applyFocusHighlight)
        assertEquals(400, result.dampingMs)
    }

    @Test fun returnsCameraMoveForTurnModeWithNewestNode() {
        val result = assertNotNull(computeFollowFrame(newNodeIds, true, FollowMode.TURN, positionOf))
        assertEquals("node-2", result.nodeId)
        assertFalse(result.applyFocusHighlight)
    }

    @Test fun returnsCameraMoveForNodeFocusModeWithFocusHighlight() {
        val result = assertNotNull(computeFollowFrame(newNodeIds, true, FollowMode.NODE_FOCUS, positionOf))
        assertEquals("node-2", result.nodeId)
        assertTrue(result.applyFocusHighlight)
        assertEquals(800, result.focusHighlightDuration)
    }

    @Test fun usesCustomDampingWhenProvided() {
        val result = assertNotNull(
            computeFollowFrame(newNodeIds, true, FollowMode.NODE, positionOf, dampingMs = 600),
        )
        assertEquals(600, result.dampingMs)
    }

    @Test fun usesCustomFocusHighlightDurationWhenProvided() {
        val result = assertNotNull(
            computeFollowFrame(newNodeIds, true, FollowMode.NODE_FOCUS, positionOf, focusHighlightDuration = 1200),
        )
        assertEquals(1200, result.focusHighlightDuration)
    }

    @Test fun returnsNullWhenPositionOfIsUnknownForNewestNode() {
        assertNull(computeFollowFrame(_l["unknown"], true, FollowMode.NODE, positionOf = { null }))
    }

    // describe('FollowMode constants')
    @Test fun defaultLiveFollowModeIsNodeFocus() {
        assertEquals("node+focus", DEFAULT_LIVE_FOLLOW_MODE.wire)
    }

    @Test fun turnFollowModeIsTurn() {
        assertEquals("turn", TURN_FOLLOW_MODE.wire)
    }

    @Test fun nodeFollowModeIsNode() {
        assertEquals("node", NODE_FOLLOW_MODE.wire)
    }

    @Test fun offFollowModeIsOff() {
        assertEquals("off", OFF_FOLLOW_MODE.wire)
    }
}

package narchy.spacegraph.navigation

import borg.trikeshed.lib.*
import narchy.spacegraph.Vec3

// Port of upstream packages/navigation/src/follow.ts at 125b369.
// Streaming frame policy for live graph updates: computes the camera move when new nodes arrive.
// Deviation: upstream takes the renderer viewport and graph nodes as objects. Follow reads only whether a
// viewport exists and only the newest node's id, so this port takes `hasViewport` and the node ids.

enum class FollowMode(val wire: String) {
    OFF("off"),
    TURN("turn"),
    NODE("node"),
    NODE_FOCUS("node+focus"),
}

/** Result of computing a follow frame. */
data class CameraMove(
    /** The node id to frame, if any. */
    val nodeId: String?,
    /** Whether to apply neighborhood focus highlight to the node. */
    val applyFocusHighlight: Boolean,
    /** Duration for the focus highlight cross-fade, in ms. */
    val focusHighlightDuration: Int,
    /** Damping duration for camera animation, in ms. */
    val dampingMs: Int,
)

/** Default follow mode for live sessions. */
val DEFAULT_LIVE_FOLLOW_MODE: FollowMode = FollowMode.NODE_FOCUS

/** Follow mode for turn-based framing. */
val TURN_FOLLOW_MODE: FollowMode = FollowMode.TURN

/** Follow mode for node-based framing without focus highlight. */
val NODE_FOLLOW_MODE: FollowMode = FollowMode.NODE

/** Follow mode disabled. */
val OFF_FOLLOW_MODE: FollowMode = FollowMode.OFF

/**
 * Camera move for streaming updates, or null if no move is needed.
 * `newNodeIds` is in streaming order; the last id is the newest node.
 */
fun computeFollowFrame(
    newNodeIds: List<String>,
    hasViewport: Boolean,
    followMode: FollowMode,
    positionOf: (String) -> Vec3?,
    dampingMs: Int = 400,
    focusHighlightDuration: Int = 800,
): CameraMove? {
    if (followMode == FollowMode.OFF) return null
    if (newNodeIds.isEmpty()) return null
    if (!hasViewport) return null

    val newestNodeId = newNodeIds.last()
    positionOf(newestNodeId) ?: return null

    return when (followMode) {
        FollowMode.TURN, FollowMode.NODE -> CameraMove(newestNodeId, false, 0, dampingMs)
        FollowMode.NODE_FOCUS -> CameraMove(newestNodeId, true, focusHighlightDuration, dampingMs)
        FollowMode.OFF -> null
    }
}

package borg.trikeshed.forge.blackboard

import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.graph.causalGraphNode
import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals
import kotlin.math.sqrt

class ForceLayoutTest {

    @Test
    fun springRelaxationDrivesNodesToPositions() {
        val index = CausalGraphNodeIndex()
        val numNodes = 50

        val nodeIds = Array(numNodes) { i -> "node-$i" }

        for (i in 0 until numNodes) {
            val parents = if (i == 0) {
                emptyList()
            } else if (i <= 10) {
                listOf(nodeIds[0])
            } else {
                emptyList()
            }

            index.addOrGet(causalGraphNode(
                nodeId = nodeIds[i],
                opId = "test",
                opVersion = "1.0",
                parentNodeIds = parents,
                inputFingerprint = "test",
                blackboard = blackboardContext("test-board"),
                causalClock = i.toLong(),
                topoOrdinal = i,
                outputHash = null
            ))
        }

        val camera = ForgeBlackboardCamera.IDENTITY
        val (layoutCamera, positions) = forceLayout(index, camera, 200)

        // Assert camera has moved
        assertTrue(layoutCamera.zoom != camera.zoom || layoutCamera.x != camera.x || layoutCamera.y != camera.y, "Camera should have updated position/zoom")

        val pos0 = positions["node-0"] ?: throw AssertionError("Missing node-0 position")
        val pos1 = positions["node-1"] ?: throw AssertionError("Missing node-1 position")
        val pos20 = positions["node-20"] ?: throw AssertionError("Missing node-20 position")

        assertNotEquals(pos0, pos1, "node-0 and node-1 should not have the same position")
        assertNotEquals(pos0, pos20, "node-0 and node-20 should not have the same position")

        val dist1 = sqrt((pos0.screenX - pos1.screenX) * (pos0.screenX - pos1.screenX) + (pos0.screenY - pos1.screenY) * (pos0.screenY - pos1.screenY))
        val dist20 = sqrt((pos0.screenX - pos20.screenX) * (pos0.screenX - pos20.screenX) + (pos0.screenY - pos20.screenY) * (pos0.screenY - pos20.screenY))

        assertTrue(dist1 > 0.0)
        assertTrue(dist20 > 0.0)
    }
}

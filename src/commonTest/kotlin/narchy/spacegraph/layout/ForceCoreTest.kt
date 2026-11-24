package narchy.spacegraph.layout

// Upstream test/layout/force-core.test.ts at 125b369, 2 cases, expected values copied from upstream.
// Upstream's plain-object nodes are ForceNode here: upstream's "without requiring a Vector3.set method" has no
// Kotlin counterpart, since positions are SimVector, so that case checks only the randomized finite result.

import narchy.spacegraph.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun plainNodes(count: Int): List<ForceNode> =
    List(count) { i ->
        ForceNode(
            id = "node-$i",
            position = SimVector(0.0, 0.0, 0.0),
            depth = null,
            width = 0.0,
            height = null,
            meta = null,
        )
    }

class ForceCoreTest {
    // 'randomizes plain-object positions without requiring a Vector3.set method'
    @Test
    fun randomizesPositionsOfPlainNodes() {
        val core = ForceCore(ForceCoreConfig(randomize = true, seed = 7, iterations = 0))
        val nodes = plainNodes(8)

        core.initializePositions(nodes, null)

        for (node in nodes) {
            assertTrue(node.position.x.isFinite())
            assertTrue(node.position.y.isFinite())
            assertTrue(node.position.z.isFinite())
            assertNotEquals(Vec3(0.0, 0.0, 0.0), Vec3(node.position.x, node.position.y, node.position.z))
        }
    }

    // 'randomizes deterministically for a fixed seed and preserves previous positions'
    @Test
    fun randomizesDeterministicallyForAFixedSeedAndPreservesPreviousPositions() {
        val seed = 7
        val first = plainNodes(8)
        val second = plainNodes(8)
        ForceCore(ForceCoreConfig(randomize = true, seed = seed, iterations = 0)).initializePositions(first, null)
        ForceCore(ForceCoreConfig(randomize = true, seed = seed, iterations = 0)).initializePositions(second, null)
        assertEquals(first.map { Vec3(it.position.x, it.position.y, it.position.z) }, second.map { Vec3(it.position.x, it.position.y, it.position.z) })

        val previous = mapOf("node-0" to SimVector(11.0, 22.0, 33.0))
        val restored = plainNodes(2)
        ForceCore(ForceCoreConfig(randomize = true, seed = seed, iterations = 0)).initializePositions(restored, previous)
        assertEquals(Vec3(11.0, 22.0, 33.0), Vec3(restored[0].position.x, restored[0].position.y, restored[0].position.z))
    }
}

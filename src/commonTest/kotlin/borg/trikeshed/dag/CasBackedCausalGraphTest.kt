package borg.trikeshed.dag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import borg.trikeshed.job.CasStore

class CasBackedCausalGraphTest {
    @Test
    fun testSubmitAndTraverseNodes() {
        val casStore = CasStore.inMemory()
        val graph = CasBackedCausalGraph(casStore)

        val rootCid = graph.submitNode(
            causalKey = "root-key",
            deps = emptyList(),
            payload = """{"data":"root-payload"}"""
        )

        val childCid = graph.submitNode(
            causalKey = "child-key",
            deps = listOf(rootCid),
            payload = """{"data":"child-payload"}"""
        )

        // Snapshot is automatically updated to childCid in submitNode
        assertEquals(childCid, graph.rootCid)

        // Traverse from rootCid
        val visited = graph.traverse(childCid)

        assertEquals(2, visited.size)
        assertTrue(visited.contains(rootCid))
        assertTrue(visited.contains(childCid))

        // Recover nodes and edge explicitly
        val childBytes = casStore.get(childCid)!!
        val childDocStr = childBytes.decodeToString()
        assertTrue(childDocStr.contains(""""causalKey":"child-key""""))
        assertTrue(childDocStr.contains(""""deps":["${rootCid.value}"]"""))

        val rootBytes = casStore.get(rootCid)!!
        assertTrue(rootBytes.decodeToString().contains(""""causalKey":"root-key""""))
    }
}

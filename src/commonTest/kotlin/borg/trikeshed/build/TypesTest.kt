package borg.trikeshed.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypesTest {
    @Test
    fun testBuildScan() {
        val scan = BuildScan("scan1", 1500L, 10, true)
        assertEquals("scan1", scan.id)
        assertEquals(1500L, scan.durationMs)
        assertEquals(10, scan.tasksExecuted)
        assertTrue(scan.isCacheHit)
    }

    @Test
    fun testTaskGraph() {
        val dependencies = mapOf("taskA" to listOf("taskB", "taskC"))
        val graph = TaskGraph("taskA", dependencies)
        assertEquals("taskA", graph.rootTaskId)
        assertEquals(1, graph.dependencies.size)
        assertEquals(listOf("taskB", "taskC"), graph.dependencies["taskA"])
    }
}

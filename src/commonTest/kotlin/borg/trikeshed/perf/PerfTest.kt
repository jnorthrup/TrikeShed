package borg.trikeshed.perf

import borg.trikeshed.build.BuildScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerfTest {
    @Test
    fun testPerfAnalyzer() {
        val analyzer = DefaultPerfAnalyzer()
        val scan = BuildScan("scan1", 10000L, 5, false)
        val tasks = listOf(
            "taskFast" to 500L,
            "taskMedium" to 2500L,
            "taskSlow" to 6000L
        )

        val hotspots = analyzer.analyze(scan, tasks)
        assertEquals(2, hotspots.size)
        
        // Sorted by duration descending
        assertEquals("taskSlow", hotspots[0].taskId)
        assertTrue(hotspots[0].recommendation.contains("Consider enabling task caching"))
        
        assertEquals("taskMedium", hotspots[1].taskId)
        assertTrue(hotspots[1].recommendation.contains("Ensure caching is configured properly"))
    }
}

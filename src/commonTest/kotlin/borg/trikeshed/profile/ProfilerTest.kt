package borg.trikeshed.profile

import borg.trikeshed.build.TaskGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfilerTest {
    @Test
    fun testProfilerLifecycle() {
        val profiler = DefaultGradleProfilerIntegration()
        val sessionId = profiler.startProfiling()
        assertNotNull(sessionId)
        assertTrue(sessionId.startsWith("session-"))

        val scan = profiler.stopProfiling(sessionId)
        assertEquals(sessionId, scan.id)
        assertTrue(scan.durationMs > 0)
    }

    @Test
    fun testAnalyzeTaskGraph() {
        val profiler = DefaultGradleProfilerIntegration()
        val smallGraph = TaskGraph("root", mapOf("taskA" to listOf("taskB")))
        assertTrue(profiler.analyzeTaskGraph(smallGraph).isEmpty())

        val largeDeps = (1..105).associate { "task\$it" to listOf("dep") }
        val largeGraph = TaskGraph("root", largeDeps)
        val issues = profiler.analyzeTaskGraph(largeGraph)
        assertEquals(1, issues.size)
        assertTrue(issues[0].contains("too complex"))
    }
}

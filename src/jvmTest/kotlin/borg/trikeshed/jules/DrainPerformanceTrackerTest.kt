package borg.trikeshed.jules

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrainPerformanceTrackerTest {

    @Test
    fun testDrainMetricsTracking() {
        DrainPerformanceTracker.reset()
        val tempDir = Files.createTempDirectory("drain_perf_test").toFile()
        try {
            DrainPerformanceTracker.initLogFile(tempDir)
            val msg1 = DrainPerformanceTracker.recordDrainBatch(5, 1000L)
            assertTrue(msg1.contains("batch_dps=5.000 drains/sec"))
            assertEquals(5L, DrainPerformanceTracker.getCumulativeDrains())
            assertEquals(1000L, DrainPerformanceTracker.getCumulativeDurationMs())
            assertEquals(5.0, DrainPerformanceTracker.getOverallDps(), 0.001)

            val msg2 = DrainPerformanceTracker.recordDrainBatch(5, 1000L)
            assertTrue(msg2.contains("total_count=10"))
            assertEquals(10L, DrainPerformanceTracker.getCumulativeDrains())
            assertEquals(2000L, DrainPerformanceTracker.getCumulativeDurationMs())
            assertEquals(5.0, DrainPerformanceTracker.getOverallDps(), 0.001)

            val logFile = File(tempDir, "logs/drain_performance.log")
            assertTrue(logFile.exists())
            val lines = logFile.readLines()
            assertEquals(2, lines.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

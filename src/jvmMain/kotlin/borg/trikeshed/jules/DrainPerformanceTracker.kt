package borg.trikeshed.jules

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks and logs drains-per-second performance metrics for the flywheel driver.
 * Records batch-level and cumulative statistics, writing to both stdout and a persistent log file.
 */
object DrainPerformanceTracker {
    private val totalDrained = AtomicLong(0L)
    private val totalDurationMs = AtomicLong(0L)
    private var logFile: File? = null

    fun initLogFile(repoDir: File) {
        if (logFile == null) {
            val logsDir = File(repoDir, "logs")
            if (!logsDir.exists()) logsDir.mkdirs()
            logFile = File(logsDir, "drain_performance.log")
        }
    }

    fun recordDrainBatch(count: Int, durationMs: Long): String {
        val safeDurationMs = durationMs.coerceAtLeast(1L)
        val currentTotal = totalDrained.addAndGet(count.toLong())
        val currentTotalMs = totalDurationMs.addAndGet(safeDurationMs)

        val batchDps = count.toDouble() / (safeDurationMs.toDouble() / 1000.0)
        val overallDps = currentTotal.toDouble() / (currentTotalMs.toDouble() / 1000.0)

        val message = String.format(
            "[FLYWHEEL] DRAIN-PERF batch_count=%d batch_ms=%d batch_dps=%.3f drains/sec | total_count=%d total_ms=%d overall_dps=%.3f drains/sec",
            count, safeDurationMs, batchDps, currentTotal, currentTotalMs, overallDps
        )
        println(message)

        logFile?.let { file ->
            try {
                file.appendText("${System.currentTimeMillis()} $message\n")
            } catch (_: Exception) {}
        }
        return message
    }

    fun getCumulativeDrains(): Long = totalDrained.get()
    fun getCumulativeDurationMs(): Long = totalDurationMs.get()
    fun getOverallDps(): Double {
        val ms = totalDurationMs.get()
        return if (ms > 0) totalDrained.get().toDouble() / (ms.toDouble() / 1000.0) else 0.0
    }

    fun reset() {
        totalDrained.set(0L)
        totalDurationMs.set(0L)
    }
}

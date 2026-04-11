package borg.literbike.ccek.agent8888.request_factory

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Operations tracking metrics for RequestFactory batch operations.
 */
class OperationsTracker(
    maxRecentErrors: Int = 100
) {
    /// Total number of operations processed
    private val totalOperations = AtomicLong(0)
    /// Number of successful operations
    private val successCount = AtomicLong(0)
    /// Number of failed operations
    private val errorCount = AtomicLong(0)
    /// Number of find operations
    private val findCount = AtomicLong(0)
    /// Number of persist operations
    private val persistCount = AtomicLong(0)
    /// Number of delete operations
    private val deleteCount = AtomicLong(0)
    /// Total processing time in microseconds
    private val totalProcessingTimeUs = AtomicLong(0)
    /// Recent errors (capped for memory safety)
    private val recentErrors = mutableListOf<TrackedError>()
    private var maxRecentErrors: Int = maxRecentErrors
        set(value) {
            field = value
        }

    companion object {
        fun new(): OperationsTracker = OperationsTracker()
        fun withMaxErrors(maxRecentErrors: Int): OperationsTracker =
            OperationsTracker(maxRecentErrors = maxRecentErrors)
    }

    /**
     * Record the start of an operation batch. Returns a BatchTimer.
     */
    fun recordBatchStart(count: Int): BatchTimer {
        return BatchTimer(this, count)
    }

    /**
     * Record a successful find operation
     */
    fun recordFindSuccess() {
        findCount.incrementAndGet()
        successCount.incrementAndGet()
        totalOperations.incrementAndGet()
    }

    /**
     * Record a successful persist operation
     */
    fun recordPersistSuccess() {
        persistCount.incrementAndGet()
        successCount.incrementAndGet()
        totalOperations.incrementAndGet()
    }

    /**
     * Record a successful delete operation
     */
    fun recordDeleteSuccess() {
        deleteCount.incrementAndGet()
        successCount.incrementAndGet()
        totalOperations.incrementAndGet()
    }

    /**
     * Record a failed operation
     */
    @Synchronized
    fun recordError(operationType: String, entityType: String, error: String) {
        errorCount.incrementAndGet()
        totalOperations.incrementAndGet()

        val trackedError = TrackedError(
            timestamp = Instant.now(),
            operationType = operationType,
            entityType = entityType,
            error = error
        )

        recentErrors.add(trackedError)
        if (recentErrors.size > maxRecentErrors) {
            recentErrors.removeAt(0)
        }
    }

    /**
     * Record processing time for a batch
     */
    fun recordProcessingTime(microseconds: Long) {
        totalProcessingTimeUs.addAndGet(microseconds)
    }

    /**
     * Get current metrics snapshot
     */
    @Synchronized
    fun getMetrics(): OperationsMetrics {
        val total = totalOperations.get()
        val success = successCount.get()
        val errors = errorCount.get()
        val processingTime = totalProcessingTimeUs.get()

        return OperationsMetrics(
            totalOperations = total,
            successCount = success,
            errorCount = errors,
            findCount = findCount.get(),
            persistCount = persistCount.get(),
            deleteCount = deleteCount.get(),
            totalProcessingTimeUs = processingTime,
            avgProcessingTimeUs = if (total > 0) processingTime.toDouble() / total.toDouble() else 0.0,
            successRate = if (total > 0) success.toDouble() / total.toDouble() else 1.0,
            recentErrors = recentErrors.toList()
        )
    }

    /**
     * Reset all metrics
     */
    @Synchronized
    fun reset() {
        totalOperations.set(0)
        successCount.set(0)
        errorCount.set(0)
        findCount.set(0)
        persistCount.set(0)
        deleteCount.set(0)
        totalProcessingTimeUs.set(0)
        recentErrors.clear()
    }
}

/**
 * A tracked error for observability
 */
@Serializable
data class TrackedError(
    val timestamp: Instant,
    val operationType: String,
    val entityType: String,
    val error: String
)

/**
 * Snapshot of operations metrics
 */
@Serializable
data class OperationsMetrics(
    val totalOperations: Long,
    val successCount: Long,
    val errorCount: Long,
    val findCount: Long,
    val persistCount: Long,
    val deleteCount: Long,
    val totalProcessingTimeUs: Long,
    val avgProcessingTimeUs: Double,
    val successRate: Double,
    val recentErrors: List<TrackedError>
)

/**
 * RAII timer for batch operations
 */
class BatchTimer(
    private val tracker: OperationsTracker,
    private val count: Int
) {
    private val start = TimeSource.Monotonic.markNow()

    /**
     * Finish the timer and return elapsed microseconds
     */
    fun finish(): Long {
        val elapsed = start.elapsedNow()
        val micros = elapsed.inWholeMicroseconds
        tracker.recordProcessingTime(micros)
        return micros
    }

    // Auto-finish on garbage collection (RAII pattern)
    protected fun finalize() {
        val elapsed = start.elapsedNow()
        val micros = elapsed.inWholeMicroseconds
        tracker.recordProcessingTime(micros)
    }
}

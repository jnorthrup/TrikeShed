package borg.literbike.request_factory

import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Operations tracking metrics for RequestFactory batch operations.
 */
class OperationsTracker(
    maxRecentErrors: Int = 100
) {
    /**
     * Total number of operations processed
     */
    private val totalOperations = AtomicLong(0L)

    /**
     * Number of successful operations
     */
    private val successCount = AtomicLong(0L)

    /**
     * Number of failed operations
     */
    private val errorCount = AtomicLong(0L)

    /**
     * Number of find operations
     */
    private val findCount = AtomicLong(0L)

    /**
     * Number of persist operations
     */
    private val persistCount = AtomicLong(0L)

    /**
     * Number of delete operations
     */
    private val deleteCount = AtomicLong(0L)

    /**
     * Total processing time in microseconds
     */
    private val totalProcessingTimeUs = AtomicLong(0L)

    /**
     * Recent errors (capped for memory safety)
     */
    private val recentErrors: MutableList<TrackedError> = mutableListOf()
    private val maxRecentErrors: Int = maxRecentErrors

    companion object {
        fun new() = OperationsTracker()
        fun withMaxErrors(maxRecentErrors: Int) = OperationsTracker(maxRecentErrors = maxRecentErrors)
    }

    /**
     * Record the start of an operation batch
     */
    fun recordBatchStart(count: Int): BatchTimer {
        return BatchTimer(this, count, Clock.systemUTC().instant())
    }

    /**
     * Record a successful find operation
     */
    fun recordFindSuccess() {
        findCount.incrementAndFetch()
        successCount.incrementAndFetch()
        totalOperations.incrementAndFetch()
    }

    /**
     * Record a successful persist operation
     */
    fun recordPersistSuccess() {
        persistCount.incrementAndFetch()
        successCount.incrementAndFetch()
        totalOperations.incrementAndFetch()
    }

    /**
     * Record a successful delete operation
     */
    fun recordDeleteSuccess() {
        deleteCount.incrementAndFetch()
        successCount.incrementAndFetch()
        totalOperations.incrementAndFetch()
    }

    /**
     * Record a failed operation
     */
    fun recordError(operationType: String, entityType: String, error: String) {
        errorCount.incrementAndFetch()
        totalOperations.incrementAndFetch()

        val trackedError = TrackedError(
            timestamp = Clock.systemUTC().instant(),
            operationType = operationType,
            entityType = entityType,
            error = error
        )

        synchronized(recentErrors) {
            recentErrors.add(trackedError)
            if (recentErrors.size > maxRecentErrors) {
                recentErrors.removeAt(0)
            }
        }
    }

    /**
     * Record processing time for a batch
     */
    fun recordProcessingTime(microseconds: Long) {
        totalProcessingTimeUs.addAndFetch(microseconds)
    }

    /**
     * Get current metrics snapshot
     */
    fun getMetrics(): OperationsMetrics {
        val total = totalOperations.get()
        val success = successCount.get()
        val errors = errorCount.get()
        val processingTime = totalProcessingTimeUs.get()

        val errorsCopy = synchronized(recentErrors) { recentErrors.toList() }

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
            recentErrors = errorsCopy
        )
    }

    /**
     * Reset all metrics
     */
    fun reset() {
        totalOperations.set(0L)
        successCount.set(0L)
        errorCount.set(0L)
        findCount.set(0L)
        persistCount.set(0L)
        deleteCount.set(0L)
        totalProcessingTimeUs.set(0L)
        synchronized(recentErrors) { recentErrors.clear() }
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
    private val count: Int,
    private val start: Instant
) {
    fun finish(): Long {
        val elapsed = ChronoUnit.MICROS.between(start, Clock.systemUTC().instant())
        tracker.recordProcessingTime(elapsed)
        return elapsed
    }
}

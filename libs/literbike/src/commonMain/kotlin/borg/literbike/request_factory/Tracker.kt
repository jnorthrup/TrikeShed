package borg.literbike.request_factory

import kotlinx.atomicfu.AtomicLong
import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Operations tracking metrics for RequestFactory batch operations.
 */
class OperationsTracker(
    maxRecentErrors: Int = 100
) {
    /**
     * Total number of operations processed
     */
    private val totalOperations: AtomicLong = atomic(0L)

    /**
     * Number of successful operations
     */
    private val successCount: AtomicLong = atomic(0L)

    /**
     * Number of failed operations
     */
    private val errorCount: AtomicLong = atomic(0L)

    /**
     * Number of find operations
     */
    private val findCount: AtomicLong = atomic(0L)

    /**
     * Number of persist operations
     */
    private val persistCount: AtomicLong = atomic(0L)

    /**
     * Number of delete operations
     */
    private val deleteCount: AtomicLong = atomic(0L)

    /**
     * Total processing time in microseconds
     */
    private val totalProcessingTimeUs: AtomicLong = atomic(0L)

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
        return BatchTimer(this, count, Clock.System.now())
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
    fun recordError(operationType: String, entityType: String, error: String) {
        errorCount.incrementAndGet()
        totalOperations.incrementAndGet()

        val trackedError = TrackedError(
            timestamp = Clock.System.now(),
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
        totalProcessingTimeUs.addAndGet(microseconds)
    }

    /**
     * Get current metrics snapshot
     */
    fun getMetrics(): OperationsMetrics {
        val total = totalOperations.value
        val success = successCount.value
        val errors = errorCount.value
        val processingTime = totalProcessingTimeUs.value

        val errorsCopy = synchronized(recentErrors) { recentErrors.toList() }

        return OperationsMetrics(
            totalOperations = total,
            successCount = success,
            errorCount = errors,
            findCount = findCount.value,
            persistCount = persistCount.value,
            deleteCount = deleteCount.value,
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
        totalOperations.value = 0L
        successCount.value = 0L
        errorCount.value = 0L
        findCount.value = 0L
        persistCount.value = 0L
        deleteCount.value = 0L
        totalProcessingTimeUs.value = 0L
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
        val elapsed = Clock.System.now() - start
        val micros = elapsed.toLong(kotlinx.datetime.DateTimeUnit.MICROSECOND)
        tracker.recordProcessingTime(micros)
        return micros
    }
}

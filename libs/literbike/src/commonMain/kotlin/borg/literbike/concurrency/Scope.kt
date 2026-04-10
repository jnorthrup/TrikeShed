package borg.literbike.concurrency

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlin.coroutines.*

/**
 * Simplified Structured concurrency scopes - no tokio
 *
 * Uses std futures and userspace executor.
 */

/**
 * Job state
 */
enum class JobState {
    New,
    Active,
    Completed,
    Cancelled,
    Failed
}

/**
 * Job interface for cancellable computations
 */
interface Job {
    fun cancel()
    fun isActive(): Boolean
    fun isCompleted(): Boolean
    fun isCancelled(): Boolean
}

/**
 * Concrete job implementation
 */
class CoroutineJob : Job {
    private var state: JobState = JobState.New
    private val mutex = Any()

    companion object {
        fun new() = CoroutineJob()
    }

    fun markActive() = synchronized(mutex) { state = JobState.Active }
    fun markCompleted() = synchronized(mutex) { state = JobState.Completed }
    fun markFailed() = synchronized(mutex) { state = JobState.Failed }

    override fun cancel() = synchronized(mutex) { state = JobState.Cancelled }
    override fun isActive(): Boolean = synchronized(mutex) { state == JobState.Active }
    override fun isCompleted(): Boolean = synchronized(mutex) {
        state == JobState.Completed || state == JobState.Failed
    }
    override fun isCancelled(): Boolean = synchronized(mutex) { state == JobState.Cancelled }
}

/**
 * CoroutineScope - structured concurrency scope
 */
class CoroutineScope : Job {
    private var cancelled: Boolean = false
    private val mutex = Any()
    private val children: MutableList<Job> = mutableListOf()
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)

    companion object {
        fun new() = CoroutineScope()
    }

    /**
     * Launch a coroutine in this scope
     */
    fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job {
        val job = CoroutineJob()
        job.markActive()
        synchronized(mutex) { children.add(job) }

        scope.launch {
            try {
                block()
                job.markCompleted()
            } catch (e: CancellationException) {
                job.markCancelled()
            } catch (e: Throwable) {
                job.markFailed()
            }
        }

        return job
    }

    override fun cancel() = synchronized(mutex) {
        cancelled = true
        children.forEach { it.cancel() }
    }

    override fun isActive(): Boolean = synchronized(mutex) { !cancelled }
    override fun isCompleted(): Boolean = synchronized(mutex) { cancelled && children.all { it.isCompleted() } }
    override fun isCancelled(): Boolean = synchronized(mutex) { cancelled }
}

/**
 * SupervisorScope - supervisor that doesn't fail on child errors
 */
class SupervisorScope {
    private var cancelled: Boolean = false
    private val mutex = Any()
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default
    )

    companion object {
        fun new() = SupervisorScope()
    }

    /**
     * Launch a fire-and-forget coroutine
     */
    fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Result<Unit>): CoroutineJob {
        val job = CoroutineJob()
        job.markActive()

        scope.launch {
            try {
                block()
                job.markCompleted()
            } catch (e: CancellationException) {
                job.markCancelled()
            } catch (e: Throwable) {
                // Supervisor doesn't fail on child errors
                job.markFailed()
            }
        }

        return job
    }

    fun isCancelled(): Boolean = synchronized(mutex) { cancelled }

    fun cancel() = synchronized(mutex) {
        cancelled = true
        scope.cancel()
    }
}

/**
 * High-level coroutine scope function
 */
suspend inline fun <T> coroutineScope(crossinline block: suspend kotlinx.coroutines.CoroutineScope.() -> Result<T>): Result<T> {
    return kotlinx.coroutines.coroutineScope {
        block()
    }
}

/**
 * High-level supervisor scope function
 */
suspend inline fun <T> supervisorScope(crossinline block: suspend kotlinx.coroutines.CoroutineScope.() -> Result<T>): Result<T> {
    return kotlinx.coroutines.supervisorScope {
        block()
    }
}

/**
 * Spawn a coroutine
 */
fun CoroutineScope.spawn(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job {
    return launch(block)
}

/**
 * Launch a fire-and-forget coroutine
 */
fun SupervisorScope.launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Result<Unit>): CoroutineJob {
    return launch(block)
}

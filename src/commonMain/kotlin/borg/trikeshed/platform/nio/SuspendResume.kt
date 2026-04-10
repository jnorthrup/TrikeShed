package borg.trikeshed.platform.nio

import borg.trikeshed.platform.concurrency.CancellationError

/**
 * Suspend/Resume primitives using futures and channels
 *
 * This module provides suspend/resume functionality without relying on
 * Kotlin coroutines async/await directly. Instead, it uses futures polling
 * and channels to achieve similar behavior.
 */

/**
 * Suspension state
 */
sealed class SuspensionState<out T> {
    object Running : SuspensionState<Nothing>()
    data class Suspended<T>(val value: T) : SuspensionState<T>()
    data class Resumed<T>(val value: T) : SuspensionState<T>()
    data class Completed<T>(val value: T) : SuspensionState<T>()
    object Cancelled : SuspensionState<Nothing>()
}

/**
 * Token for suspending and resuming tasks
 */
class SuspendToken<T> {
    private var state: SuspensionState<T> = SuspensionState.Running
    private var resumeRequested = false
    private var resumeValue: T? = null

    companion object {
        fun <T> create(initial: T): SuspendToken<T> = SuspendToken()
    }

    fun isSuspended(): Boolean = state is SuspensionState.Suspended
    fun isCompleted(): Boolean = state is SuspensionState.Completed
    fun isCancelled(): Boolean = state is SuspensionState.Cancelled

    fun suspend(value: T) {
        state = SuspensionState.Suspended(value)
    }

    fun resume(value: T) {
        resumeValue = value
        resumeRequested = true
    }

    fun complete(value: T) {
        state = SuspensionState.Completed(value)
    }

    fun cancel() {
        state = SuspensionState.Cancelled
    }

    fun pollSuspend(): SuspensionState<T> {
        return when (state) {
            is SuspensionState.Suspended, SuspensionState.Running -> {
                if (resumeRequested) {
                    val value = resumeValue
                    resumeRequested = false
                    if (value != null) {
                        state = SuspensionState.Resumed(value)
                        SuspensionState.Resumed(value)
                    } else {
                        state
                    }
                } else {
                    state
                }
            }
            is SuspensionState.Resumed<*> -> {
                state = SuspensionState.Running
                SuspensionState.Running
            }
            is SuspensionState.Completed<*> -> state
            SuspensionState.Cancelled -> state
        }
    }

    fun nowOrNever(): Result<T>? {
        return when (val s = pollSuspend()) {
            is SuspensionState.Resumed<T> -> Result.success(s.value)
            is SuspensionState.Completed<T> -> Result.success(s.value)
            SuspensionState.Cancelled -> Result.failure(CancellationError("Suspended operation cancelled"))
            else -> null
        }
    }
}

/**
 * Future for suspended operations
 */
class SuspendFuture<T>(private val token: SuspendToken<T>) {
    companion object {
        fun <T> create(token: SuspendToken<T>): SuspendFuture<T> = SuspendFuture(token)
    }

    fun suspend(value: T) { token.suspend(value) }
    fun resume(value: T) { token.resume(value) }
    fun cancel() { token.cancel() }
    fun nowOrNever(): Result<T>? = token.nowOrNever()
}

/**
 * Continuation for suspended tasks
 */
class Continuation<T, R>(
    val input: T,
    private val continuation: (T) -> R
) {
    companion object {
        fun <T, R> create(input: T, continuation: (T) -> R): Continuation<T, R> {
            return Continuation(input, continuation)
        }
    }

    fun execute(): R = continuation(input)
}

/**
 * Continuation scheduler for managing suspended task continuations
 */
class ContinuationScheduler<T, R> {
    private val pending = ArrayDeque<Continuation<T, R>>()

    companion object {
        fun <T, R> create(): ContinuationScheduler<T, R> = ContinuationScheduler()
    }

    fun schedule(cont: Continuation<T, R>) {
        pending.addLast(cont)
    }

    fun pollContinuations(): R? {
        if (pending.isEmpty()) return null
        return pending.removeFirst().execute()
    }

    fun isEmpty(): Boolean = pending.isEmpty()
}

/**
 * Reactor continuation that can be suspended and resumed
 */
class ReactorContinuation<T>(initial: T) {
    private var stepCount: Long = 0
    private val suspendToken = SuspendToken.create(initial)

    companion object {
        fun <T> create(initial: T): ReactorContinuation<T> = ReactorContinuation(initial)
    }

    fun step(): Long {
        stepCount++
        return stepCount
    }

    fun currentStep(): Long = stepCount

    fun suspend(value: T) { suspendToken.suspend(value) }
    fun resume(value: T) { suspendToken.resume(value) }
    fun token(): SuspendToken<T> = suspendToken
}

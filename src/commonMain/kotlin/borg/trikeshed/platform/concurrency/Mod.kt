package borg.trikeshed.platform.concurrency

/**
 * Structured concurrency patterns using CCEK (CoroutineContext Element Key)
 *
 * This module provides structured concurrency patterns inspired by Kotlin coroutines
 * using the Trikeshed CCEK pattern.
 *
 * Key concepts:
 * - CCEK: CoroutineContext Element Key (trait-based keyed services)
 * - Jobs: Unit of structured work with cancellation
 * - Deferred: Suspended computation with result
 * - Channels: Message passing between jobs
 */

// Re-export channel module
export { Channel, ChannelCapacity, SendError, RecvError, channel } from "./channel"

/**
 * Result type for coroutine operations that can be cancelled
 */
class CancellationError(message: String) : Throwable("CancellationError: $message") {
    val messageText: String = message
}

/**
 * CCEK - Coroutine Context Element Key
 * Mirrors Kotlin's CoroutineContext.Key pattern
 */
interface CcekKey {
    val keyId: String
    // Element type is determined by the associated value
}

/**
 * CCEK Element - Coroutine Context Element
 * Mirrors Kotlin's CoroutineContext.Element
 */
interface CcekElement

/**
 * CCEK Context - collection of keyed elements
 * Mirrors Kotlin's CoroutineContext
 */
class CcekContext private constructor(
    private val elements: MutableMap<String, CcekElement>
) {
    constructor() : this(mutableMapOf())

    fun <T : CcekElement> with(key: String, element: T): CcekContext {
        val newElements = elements.toMutableMap()
        newElements[key] = element
        return CcekContext(newElements)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : CcekElement> get(key: String): T? {
        return elements[key] as? T
    }

    fun minusKey(key: String): CcekContext {
        val newElements = elements.toMutableMap()
        newElements.remove(key)
        return CcekContext(newElements)
    }
}

/**
 * Job trait - unit of structured work
 */
interface Job {
    fun isActive(): Boolean
    fun cancel()
}

/**
 * Cancellation token for structured cancellation
 */
class CancellationToken {
    private var cancelled: Boolean = false

    fun isCancelled(): Boolean = cancelled
    fun cancel() { cancelled = true }
}

/**
 * Suspend token for suspend/resume without coroutines
 */
class SuspendToken<T>(initial: T) {
    sealed class State<out T> {
        object Running : State<Nothing>()
        data class Suspended<T>(val value: T) : State<T>()
        data class Resumed<T>(val value: T) : State<T>()
        data class Complete<T>(val value: T) : State<T>()
        object Cancelled : State<Nothing>()
    }

    private var state: State<T> = State.Running

    fun isSuspended(): Boolean = state is State.Suspended

    fun suspend(value: T) { state = State.Suspended(value) }
    fun resume(value: T) { state = State.Resumed(value) }
    fun complete(value: T) { state = State.Complete(value) }
    fun cancel() { state = State.Cancelled }

    fun pollState(): State<T> = state
}

/**
 * Limited dispatcher placeholder
 */
class LimitedDispatcher {
    companion object {
        fun create() = LimitedDispatcher()
    }
}

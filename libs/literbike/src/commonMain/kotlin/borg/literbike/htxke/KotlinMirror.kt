package borg.literbike.htxke

/**
 * CCEK Key - 1:1 with Element, passive SDK provider
 *
 * Keys are hierarchical in source code to match runtime hierarchy.
 * Kotlin: `interface Key<E : Element>`
 */

/**
 * Key trait - 1:1 with Element
 */
interface Key<E : Element> {
    fun factory(): E
}

/**
 * KeyAny - type-erased key for minusKey operations
 */
object KeyAny

/**
 * Element trait - base for all coroutine context elements
 */
interface Element {
    val key: Key<*>
}

/**
 * Job interface - combines Element and Coroutine
 */
interface Job : Element, Coroutine {
    fun isActive(): Boolean
    fun isCompleted(): Boolean
    fun join()
    fun cancel()
}

/**
 * Coroutine - marker interface
 */
interface Coroutine

/**
 * CoroutineScope interface
 */
interface CoroutineScope {
    val coroutineContext: CoroutineContext
}

/**
 * CoroutineContext interface
 */
interface CoroutineContext {
    operator fun <E : Element> get(key: Key<E>): E?
    fun minusKey(key: KeyAny): CoroutineContext
    val size: Int
}

/**
 * EmptyCoroutineContext - default empty context
 */
object EmptyCoroutineContext : CoroutineContext, Element {
    override val key: Key<*> get() = KeyAny

    override fun <E : Element> get(key: Key<E>): E? = null

    override fun minusKey(key: KeyAny): CoroutineContext = this

    override val size: Int get() = 0
}

/**
 * Flow interface
 */
interface Flow<out T> {
    suspend fun <R> collect(collector: FlowCollector<T>): R
}

/**
 * FlowCollector interface
 */
interface FlowCollector<in T> {
    suspend fun emit(value: T)
}

/**
 * Coroutine scope builder
 */
suspend fun <R> coroutineScope(block: suspend () -> R): R = block()

/**
 * Channel result enum
 */
sealed class ChannelResult<out T> {
    data class Success<T>(val value: T) : ChannelResult<T>()
    object Closed : ChannelResult<Nothing>()
    object Empty : ChannelResult<Nothing>()
    object Full : ChannelResult<Nothing>()
}

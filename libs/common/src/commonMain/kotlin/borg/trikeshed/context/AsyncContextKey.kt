package borg.trikeshed.context

import kotlin.coroutines.CoroutineContext

/**
 * Enhanced key lookup for [AsyncContextElement].
 *
 * Each Key encodes its semantic identity through a bitmask, allowing
 * for efficient O(1) membership tests and bitwise-composed contexts.
 */
abstract class AsyncContextKey<E : AsyncContextElement>(
    val name: String = "UnnamedContextKey",
    val mask: Long = 0L
) : CoroutineContext.Key<E> {

    override fun toString(): String = "AsyncContextKey($name, mask=$mask)"

    /**
     * Bitwise-OR combination of this key with another.
     */
    infix fun or(other: AsyncContextKey<*>): Long = this.mask or other.mask

    /**
     * Test if a combined mask contains this key's semantic identity.
     */
    fun inMask(combinedMask: Long): Boolean = (combinedMask and mask) == mask

    companion object {
        val NioUserspaceKeyMask: Long = 1L shl 0
        val LiburingKeyMask: Long = 1L shl 1
        val FanoutDispatcherKeyMask: Long = 1L shl 2
    }
}

package borg.trikeshed.lib

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Interface for resources that can be opened and closed.
 */
interface Usable : Closeable {
    /**
     * contract - does no harm if called more than once
     */
    fun open()
    /**
     * contract - does no harm if called more than once
     */
    override fun close()
}

/**
 * Executes the given [block] function on this resource and then closes it down correctly whether an exception
 * is thrown or not.
 */
@OptIn(ExperimentalContracts::class)
inline fun <T : Usable, R> T.use(block: (T) -> R): R {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    open()
    var exception: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        exception = e
        throw e
    } finally {
        when (exception) {
            null -> close()
            else -> {
                try {
                    close()
                } catch (closeException: Throwable) {
                    exception.addSuppressed(closeException)
                }
            }
        }
    }
}

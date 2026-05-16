package borg.trikeshed.userspace.nio.spi.digest

import kotlin.coroutines.CoroutineContext

/**
 * SM3 interface — coroutine context key for dependency injection.
 * Chinese national hash standard (GB/T 32907-2016).
 */
interface Sm3 : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<Sm3>

    fun hash(data: ByteArray): ByteArray
    fun hmac(key: ByteArray, data: ByteArray): ByteArray
}
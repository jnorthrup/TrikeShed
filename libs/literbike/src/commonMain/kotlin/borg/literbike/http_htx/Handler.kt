package borg.literbike.http_htx

/**
 * HTTP-HTX Handler - Protocol handlers
 *
 * This module CANNOT see matcher, listener, reactor, timer.
 */

import kotlin.native.concurrent.AtomicLong

/**
 * HandlerKey - protocol handler factory
 */
object HandlerKey {
    val FACTORY: () -> HandlerElement = { HandlerElement() }
}

/**
 * HandlerElement - tracks protocol handling counts
 */
class HandlerElement {
    private val httpCount = AtomicLong(0)
    private val http2Count = AtomicLong(0)
    private val http3Count = AtomicLong(0)
    private val unknownCount = AtomicLong(0)

    fun handleHttp1() { httpCount.incrementAndFetch() }
    fun handleHttp2() { http2Count.incrementAndFetch() }
    fun handleHttp3() { http3Count.incrementAndFetch() }
    fun handleUnknown() { unknownCount.incrementAndFetch() }

    fun stats(): HandlerStats = HandlerStats(
        http1 = httpCount.get().toULong(),
        http2 = http2Count.get().toULong(),
        http3 = http3Count.get().toULong(),
        unknown = unknownCount.get().toULong()
    )
}

/**
 * Handler stats
 */
data class HandlerStats(
    val http1: ULong,
    val http2: ULong,
    val http3: ULong,
    val unknown: ULong
) {
    fun total(): ULong = http1 + http2 + http3 + unknown
}

/**
 * Protocol handler interface
 */
interface ProtocolHandler {
    val protocol: String
    fun handle(data: ByteArray): HandlerResult
}

/**
 * Handler result
 */
sealed class HandlerResult {
    data class Handled(val bytesProcessed: Int) : HandlerResult()
    object NeedMoreData : HandlerResult()
    data class Error(val message: String) : HandlerResult()
}

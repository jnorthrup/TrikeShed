package borg.literbike.http_htx

/**
 * HTTP-HTX Listener - HTTP socket listener
 *
 * This module CANNOT see matcher, reactor, timer, handler.
 */

import kotlin.native.concurrent.AtomicInt

/**
 * ListenerKey - listener factory
 */
object ListenerKey {
    val FACTORY: () -> ListenerElement = { ListenerElement("0.0.0.0:80") }
}

/**
 * ListenerElement - HTTP socket listener state
 */
class ListenerElement(
    val bindAddr: String,
    var fd: Int = -1,
    val backlog: UInt = 128u
) {
    private val acceptedConnections = AtomicInt(0)

    companion object {
        fun new(bindAddr: String): ListenerElement = ListenerElement(bindAddr)
    }

    fun incrementAccepted() { acceptedConnections.incrementAndGet() }

    fun accepted(): Int = acceptedConnections.get()
}

package borg.literbike.ccek.agent8888

import java.util.concurrent.atomic.AtomicInteger

/**
 * Agent8888 Listener - TCP socket listener
 *
 * This module manages TCP listener socket state.
 * It only knows about itself and the core traits.
 */

/**
 * ListenerKey - manages TCP listener socket
 */
object ListenerKey : Key<ListenerElement> {
    const val DEFAULT_BIND_ADDR: String = "0.0.0.0:8888"

    override fun factory(): ListenerElement = ListenerElement(DEFAULT_BIND_ADDR)

    fun create(bindAddr: String): ListenerElement = ListenerElement(bindAddr)
}

/**
 * ListenerElement - TCP listener state
 */
class ListenerElement(
    val bindAddr: String,
    val fd: Int = -1,
    val backlog: UInt = 128u
) : Element {
    private val acceptedConnections = AtomicInteger(0)

    constructor(bindAddr: String) : this(bindAddr, -1, 128u)

    fun incrementAccepted() {
        acceptedConnections.incrementAndFetch()
    }

    fun accepted(): Int = acceptedConnections.get()

    override fun keyType(): Any = ListenerKey
    override fun asAny(): Any = this
}

package borg.trikeshed.couch.runtime

import borg.trikeshed.couch.htx.HtxBlock
import borg.trikeshed.couch.userspace.nio.ReactorSupervisor
import borg.trikeshed.couch.userspace.nio.SessionContext
import borg.trikeshed.context.ElementLifecycleState
import kotlinx.coroutines.channels.Channel as KChannel

/**
 * High-level Reactor API wrapper.
 * Combines the Supervisor job (ReactorSupervisor) with domain-specific runtime methods.
 */
class Reactor(val realm: String = "default") {

    val supervisor = ReactorSupervisor(realm)

    // Proxy lifecycle to the supervisor
    val state: ElementLifecycleState get() = supervisor.state

    fun open() = supervisor.open()
    fun activate() = supervisor.activate()
    fun drain() = supervisor.drain()
    fun close() = supervisor.close()

    /** Run a block with an injected SessionContext. */
    suspend fun <T> withSessionContext(sessionId: String, block: suspend SessionContext.() -> T): T {
        return supervisor.withSessionContext(sessionId, block)
    }

    /** Find a session context. */
    fun session(sessionId: String): SessionContext? = supervisor.session(sessionId)

    /** All open sessions. */
    val sessions: Map<String, SessionContext> get() = supervisor.sessions
}

package borg.trikeshed.couch.runtime

import borg.trikeshed.couch.userspace.nio.ReactorSupervisor

/**
 * Minimal runtime facade over [ReactorSupervisor].
 *
 * Owns the lifecycle of all protocol recognizers, parse scopes, and session
 * dispatches. Delegates state transitions to the underlying supervisor.
 *
 * Lifecycle: CREATED → OPEN → ACTIVE → DRAINING → CLOSED
 */
class Reactor(realm: String = "default") {
    internal val supervisor: ReactorSupervisor = ReactorSupervisor(realm)

    /** The realm identifier this reactor operates within. */
    val realm: String get() = supervisor.realm

    /** Current lifecycle state. */
    val state: ReactorSupervisor.ReactorState get() = supervisor.state

    fun open() = supervisor.open()
    fun activate() = supervisor.activate()
    fun drain() = supervisor.drain()
    fun close() = supervisor.close()
}

package borg.trikeshed.couch.userspace.nio

import borg.trikeshed.lib.Series
import borg.trikeshed.couch.htx.HtxBlock
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Reactor as SupervisoryJob host.
 *
 * Owns the lifecycle of all protocol recognizers, parse scopes, and session
 * dispatches. Each child is a BranchScope that lives under this SupervisorJob.
 * Children are launched via a clean palette — no ad hoc coroutineScope {} inside IO loops.
 *
 * Lifecycle: CREATED → OPEN → ACTIVE → DRAINING → CLOSED
 * Sealing is the synchronization boundary — CLOSED means all children sealed.
 *
 * Session contexts ARE CoroutineContext.Element instances (keyed by their Key).
 * They are stored in the sessions map and injected into branch coroutines.
 */
class ReactorSupervisor(
    val realm: String,
    override val key: CoroutineContext.Key<ReactorSupervisor> = ReactorSupervisorKey,
) : AbstractCoroutineContextElement(key) {

    val supervisor: CompletableJob = SupervisorJob()

    // State transitions are driven from the reactor thread only (single-threaded access).
   var _state: ReactorState = ReactorState.CREATED
    val state: ReactorState get() = _state

    // Each registered protocol recognizer or branch lives here
   val _branches = mutableMapOf<String, BranchScope>()

    // Fanout: keyed by session id → SessionContext (IS a CoroutineContext.Element)
   val _sessions = mutableMapOf<String, SessionContext>()

    // Context keys injected into all branch coroutines
   val _contextPalette = mutableMapOf<CoroutineContext.Key<*>, Any?>()

    enum class ReactorState {
        CREATED,
        OPEN,
        ACTIVE,
        DRAINING,
        CLOSED,
    }

    fun open() {
        check(_state == ReactorState.CREATED) { "open() requires CREATED, was $_state" }
        _state = ReactorState.OPEN
    }

    fun activate() {
        check(_state == ReactorState.OPEN) { "activate() requires OPEN, was $_state" }
        _state = ReactorState.ACTIVE
    }

    fun drain() {
        if (_state == ReactorState.CLOSED) return
        _state = ReactorState.DRAINING
        supervisor.complete()
    }

    fun close() {
        if (_state == ReactorState.CLOSED) return
        _state = ReactorState.CLOSED
        supervisor.complete()
    }

    /**
     * Clean launch palette — only these entry points, no bare coroutineScope {}.
     * Each launch is a named branch under this SupervisorJob.
     */
    fun launchBranch(
        name: String,
        channel: KChannel<HtxBlock>,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        check(_state == ReactorState.ACTIVE) { "Cannot launch branch in state $_state" }
        val branch = BranchScope(name, channel, supervisor)
        _branches[name] = branch
        // Build context: fold palette entries into supervisor
        val ctx: CoroutineContext = _contextPalette.entries.fold(supervisor as CoroutineContext) { acc, (k, v) ->
            acc + ContextElementImpl(k, v)
        }
        return CoroutineScope(ctx).launch {
            block()
        }
    }

    /** Look up a branch by name. */
    fun branch(name: String): BranchScope? = _branches[name]

    /** Look up a session context by id. */
    fun session(sessionId: String): SessionContext? = _sessions[sessionId]

    /** All sessions — for testing and advanced use. */
    val sessions: Map<String, SessionContext> get() = _sessions.toMap()

    /** All branches — for testing and advanced use. */
    val branches: Map<String, BranchScope> get() = _branches.toMap()

    /**
     * Surgical context key insertion — does not replace existing keys.
     * Returns self for chaining.
     */
    fun withKey(key: CoroutineContext.Key<*>, element: Any?): ReactorSupervisor {
        _contextPalette[key] = element
        return this
    }

    /** Current context palette. */
    val contextPalette: Map<CoroutineContext.Key<*>, Any?> get() = _contextPalette.toMap()

    /**
     * Run a block with a session context, creating it lazily.
     * The session's SessionContext (a CoroutineContext.Element) is injected
     * into the block's coroutine context so handlers can access it via
     * coroutineContext[SessionContextKey].
     */
    suspend fun <T> withSessionContext(
        sessionId: String,
        block: suspend SessionContext.() -> T,
    ): T {
        val ctx = _sessions.getOrPut(sessionId) { SessionContext(sessionId) }
        return coroutineScope {
            ctx.block()
        }
    }
}

/**
 * A wrapper that holds a key/value pair as a CoroutineContext.Element.
 */data class ContextElementImpl(
    override val key: CoroutineContext.Key<*>,
   val value: Any?,
) : AbstractCoroutineContextElement(key) {
    @Suppress("UNCHECKED_CAST")
    fun <T> get(): T? = value as T?
}

/** Singleton key for ReactorSupervisor in CoroutineContext. */
object ReactorSupervisorKey : CoroutineContext.Key<ReactorSupervisor>

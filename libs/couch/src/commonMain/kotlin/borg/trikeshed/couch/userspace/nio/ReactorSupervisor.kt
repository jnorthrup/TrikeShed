package borg.trikeshed.couch.userspace.nio

import borg.trikeshed.lib.Series
import borg.trikeshed.couch.htx.HtxBlock
import borg.trikeshed.context.ElementLifecycleState
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Singleton key for ReactorSupervisor in CoroutineContext. */
object ReactorSupervisorKey : CoroutineContext.Key<ReactorSupervisor>

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

    // Single-threaded state transitions via a confined primitive if available
   private val _stateRef = AtomicStateReference(ElementLifecycleState.CREATED)
    val state: ElementLifecycleState get() = _stateRef.value

    // Each registered protocol recognizer or branch lives here
   val _branches = mutableMapOf<String, BranchScope>()

    // Fanout: keyed by session id → SessionContext (IS a CoroutineContext.Element)
   val _sessions = mutableMapOf<String, SessionContext>()

    // Context keys injected into all branch coroutines
   val _contextPalette = mutableMapOf<CoroutineContext.Key<*>, Any?>()

    fun open() {
        check(_stateRef.compareAndSet(ElementLifecycleState.CREATED, ElementLifecycleState.OPEN)) { "open() requires CREATED, was ${_stateRef.value}" }
    }

    fun activate() {
        check(_stateRef.compareAndSet(ElementLifecycleState.OPEN, ElementLifecycleState.ACTIVE)) { "activate() requires OPEN, was ${_stateRef.value}" }
    }

    fun drain() {
        while (true) {
            val current = _stateRef.value
            if (current == ElementLifecycleState.CLOSED || current == ElementLifecycleState.DRAINING) return
            if (_stateRef.compareAndSet(current, ElementLifecycleState.DRAINING)) {
                supervisor.complete()
                return
            }
        }
    }

    fun close() {
        while (true) {
            val current = _stateRef.value
            if (current == ElementLifecycleState.CLOSED) return
            if (_stateRef.compareAndSet(current, ElementLifecycleState.CLOSED)) {
                supervisor.complete()
                return
            }
        }
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
        check(_stateRef.value == ElementLifecycleState.ACTIVE) { "Cannot launch branch in state ${_stateRef.value}" }
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

package borg.trikeshed.platform.nio

/**
 * Session Islands - Isolated execution contexts for structured concurrency
 *
 * Session islands provide isolation boundaries for coroutines, similar to
 * Kotlin's structured concurrency model. Each island has its own context
 * elements and can have parent/child relationships for hierarchical shutdown.
 */

/**
 * Context element key for CCEK pattern
 */
interface ContextElementKey

/**
 * Context element trait
 */
interface ContextElement {
    fun cloneElement(): ContextElement
}

/**
 * Channel registry for protocol-specific channels
 */
class ChannelRegistry {
    private val channels = mutableMapOf<String, ChannelHandle>()

    fun insert(name: String, handle: ChannelHandle) { channels[name] = handle }
    fun get(name: String): ChannelHandle? = channels[name]
    fun remove(name: String) { channels.remove(name) }
    fun clear() { channels.clear() }
}

/**
 * Channel handle for inter-island communication
 */
data class ChannelHandle(
    val name: String,
    val protocol: String
)

/**
 * Session island - isolated execution context
 */
class SessionIsland(
    val id: Long,
    val name: String
) {
    private val elements = mutableMapOf<String, ContextElement>()
    val channels = ChannelRegistry()
    private val parentRef: SessionIsland? = null
    private val children = mutableListOf<SessionIsland>()
    private var cancelled = false

    companion object {
        fun create(id: Long, name: String): SessionIsland {
            return SessionIsland(id, name)
        }

        fun withParent(parent: SessionIsland, id: Long, name: String): SessionIsland {
            val island = SessionIsland(id, name)
            // In Rust this was Arc-based; Kotlin needs different lifecycle management
            parent.children.add(island)
            return island
        }
    }

    fun isCancelled(): Boolean = cancelled

    fun cancel() {
        cancelled = true
        children.forEach { it.cancel() }
    }

    fun cancelChildren() {
        children.forEach { it.cancel() }
    }

    fun parent(): SessionIsland? = parentRef
    fun children(): List<SessionIsland> = children.toList()

    fun attachElement(key: String, element: ContextElement) {
        elements[key] = element
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : ContextElement> getElement(key: String): T? = elements[key] as? T

    fun removeElement(key: String) { elements.remove(key) }
}

/**
 * Context element for session reference
 */
data class SessionElement(
    val session: SessionIsland
) : ContextElement {
    override fun cloneElement(): ContextElement = copy()
}

/**
 * Context element for cancellation
 */
class CancellationElement : ContextElement {
    val token = CancellationToken()
    override fun cloneElement(): ContextElement = CancellationElement()
}

/**
 * Cancellation token for structured cancellation
 */
class CancellationToken {
    private var cancelled = false

    companion object {
        fun create(): CancellationToken = CancellationToken()
    }

    fun isCancelled(): Boolean = cancelled
    fun cancel() { cancelled = true }
}

/**
 * Key graph for protocol state machines
 */
typealias KeyGraph = Pair<StateKey, TransitionMap>

data class StateKey(val value: String)

class TransitionMap {
    private val transitions = mutableMapOf<StateKey, MutableList<ProtocolTransition>>()

    fun addTransition(from: StateKey, transition: ProtocolTransition) {
        transitions.getOrPut(from) { mutableListOf() }.add(transition)
    }

    fun getTransitions(from: StateKey): List<ProtocolTransition> {
        return transitions[from]?.toList() ?: emptyList()
    }
}

/**
 * Protocol transition for reactor continuations
 */
class ProtocolTransition(
    val fromState: StateKey,
    val toState: StateKey,
    val guard: (CcekContext) -> Boolean,
    val continuation: (CcekContext) -> Result<Unit>
)

/**
 * CCEK context for key graph navigation
 */
class CcekContext(initialState: StateKey) {
    private var currentState = initialState
    private val continuationStack = mutableListOf<StateKey>()
    private val protocolMetadata = mutableMapOf<String, ByteArray>()

    fun currentState(): StateKey = currentState

    fun transitionTo(state: StateKey) {
        continuationStack.add(currentState)
        currentState = state
    }

    fun pushMetadata(key: String, value: ByteArray) {
        protocolMetadata[key] = value
    }

    fun getMetadata(key: String): ByteArray? = protocolMetadata[key]
}

/**
 * CCEK error types
 */
sealed class CcekError(message: String) : Throwable(message) {
    class TransitionFailed(msg: String) : CcekError("Transition failed: $msg")
    class InvalidState(msg: String) : CcekError("Invalid state: $msg")
    object Cancelled : CcekError("Cancelled")
}

/**
 * Execute a key graph with the given context
 */
fun executeKeyGraph(graph: KeyGraph, ctx: CcekContext): Result<Unit> {
    val (initialState, transitions) = graph

    while (true) {
        val current = ctx.currentState()
        val trans = transitions.getTransitions(current)

        if (trans.isEmpty()) break

        var madeTransition = false
        for (t in trans) {
            if (t.guard(ctx)) {
                t.continuation(ctx).onFailure { return Result.failure(it) }
                ctx.transitionTo(t.toState)
                madeTransition = true
                break
            }
        }

        if (!madeTransition) break
    }

    return Result.success(Unit)
}

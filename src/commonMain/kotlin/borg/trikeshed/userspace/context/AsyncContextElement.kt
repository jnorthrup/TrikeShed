package borg.trikeshed.userspace.context

import kotlin.coroutines.CoroutineContext

/**
 * Lifecycle states for any [AsyncContextElement].
 *
 * State machine:
 *   CREATED -> OPEN -> (ACTIVE | DRAINING) -> CLOSED
 *
 * Fanout channels may be DRAINING while remaining completions are processed
 * before transitioning to CLOSED.
 */
enum class ElementLifecycleState {
    /** Element created but resource not yet acquired. */
    CREATED,
    /** Resource acquired; ready for I/O or fanout operations. */
    OPEN,
    /** Actively processing completions or dispatching to fanout subscribers. */
    ACTIVE,
    /** No new work accepted; draining remaining completions before close. */
    DRAINING,
    /** Resource fully released; element is terminal. */
    CLOSED
}

/**
 * Base interface for all TrikeShed coroutine context elements.
 *
 * Each element is associated with exactly one [AsyncContextKey] singleton.
 * Lifecycle transitions are monotonically forward-only (no resurrection).
 *
 * Fanout semantics: an element may channel completions to N downstream
 * subscribers via [fanoutSubscribers]. The element is responsible for
 * dispatching completions to all subscribers atomically from its perspective.
 */
interface AsyncContextElement : CoroutineContext.Element {

    /** Current lifecycle state. Transitions are monotonically forward-only. */
    val lifecycleState: ElementLifecycleState

    /**
     * Ordered list of downstream fanout subscribers.
     * Each subscriber is an [AsyncContextElement] that will receive
     * channelized completions from this element.
     */
    val fanoutSubscribers: List<AsyncContextElement>

    /**
     * Transition this element to [ElementLifecycleState.OPEN].
     * Idempotent if already OPEN or later.
     */
    suspend fun open()

    /**
     * Begin draining: stop accepting new work, process remaining completions,
     * then transition to [ElementLifecycleState.CLOSED].
     */
    suspend fun drain()

    /**
     * Immediately close the underlying resource.
     * If in ACTIVE state, performs a hard close without draining.
     */
    suspend fun close()
}

/**
 * Abstract base for elements tied to the NIO userspace key.
 * Context lookup: ctx[AsyncContextKey.NioUserspaceKey] returns NioUserspaceElement? (type-safe).
 */
abstract class NioUserspaceElement : AsyncContextElement {
    override val key: CoroutineContext.Key<NioUserspaceElement> get() = AsyncContextKey.NioUserspaceKey
}

/**
 * Abstract base for elements tied to the liburing facade key.
 * Context lookup: ctx[AsyncContextKey.LiburingKey] returns LiburingElement? (type-safe).
 */
abstract class LiburingElement : AsyncContextElement {
    override val key: CoroutineContext.Key<LiburingElement> get() = AsyncContextKey.LiburingKey
}

/**
 * Abstract base for fanout dispatcher elements.
 * Context lookup: ctx[AsyncContextKey.FanoutDispatcherKey] returns FanoutDispatcherElement? (type-safe).
 */
abstract class FanoutDispatcherElement : AsyncContextElement {
    override val key: CoroutineContext.Key<FanoutDispatcherElement> get() = AsyncContextKey.FanoutDispatcherKey
}

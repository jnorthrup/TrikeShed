package borg.trikeshed.userspace.context

import kotlin.coroutines.CoroutineContext

/**
 * Sealed hierarchy of CoroutineContext.Key singletons that serve as the structured-async
 * encapsulation boundary for TrikeShed userspace NIO and liburing facade.
 *
 * Design: "coroutine -> context -> key -> element" downward creation flow.
 * Keys are static singletons (object) implementing Key<ConcreteElement>.
 * Each Key is parameterized with its own concrete Element type for type-safe context lookup.
 * Elements carry lifecycle state and enable channelized async fanout.
 *
 * Keys are compared by identity (===) not equality (==).
 *
 * Type safety: ctx[NioUserspaceKey] returns NioUserspaceElement? without unsafe cast.
 *              ctx[LiburingKey] returns LiburingElement? without unsafe cast.
 *              ctx[FanoutDispatcherKey] returns FanoutDispatcherElement? without unsafe cast.
 */
sealed class AsyncContextKey {

    /**
     * Key for TrikeShed Userspace NIO context elements.
     * Singleton object: always compare by identity (===).
     * Type-parameterized as Key<NioUserspaceElement> for safe context lookup.
     */
    object NioUserspaceKey : AsyncContextKey(), CoroutineContext.Key<NioUserspaceElement>

    /**
     * Key for liburing facade context elements.
     * Singleton object: always compare by identity (===).
     * Type-parameterized as Key<LiburingElement> for safe context lookup.
     */
    object LiburingKey : AsyncContextKey(), CoroutineContext.Key<LiburingElement>

    /**
     * Key for channelized fanout dispatcher context elements.
     * Singleton object: always compare by identity (===).
     * Type-parameterized as Key<FanoutDispatcherElement> for safe context lookup.
     */
    object FanoutDispatcherKey : AsyncContextKey(), CoroutineContext.Key<FanoutDispatcherElement>
}

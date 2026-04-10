package borg.trikeshed.platform.ccek.core

/**
 * CCEK Core — Context-Chain Element Key
 *
 * Three foundational abstractions ported from literbike Rust CCEK:
 *
 * - [Key]: Passive SDK provider. Defines a factory that produces exactly one [Element].
 *   Keys are immutable and carry constants (type info, defaults).
 * - [Element]: Type-erased stored value in a Context. Each Element knows its Key type.
 * - [Context]: Immutable copy-on-write hierarchical chain. Supports `plus` (add),
 *   `minus` (remove), `get` (lookup by Key type). Lookup traverses head-to-tail
 *   (most recent to oldest), enabling hierarchical override.
 *
 * Architecture: All state flows through the Context chain. Each domain defines its
 * own Key/Element pair. Context provides hierarchical, immutable, type-safe lookups.
 * Composition without coupling — any assembly can be added/removed independently.
 */

/**
 * Key trait — passive SDK provider.
 *
 * A Key never takes action. It only provides:
 * - The [Element] type it produces
 * - A factory function to create the default Element
 * - Constants/metadata for the domain
 *
 * Each Key has exactly one Element type. The factory creates the canonical instance.
 */
interface Key<E : Element> {
    val elementClass: KClass<E>
    fun factory(): E
}

/**
 * Element trait — stored value in a Context.
 *
 * Elements are type-erased (`Any`), send-safe, and know their Key type.
 * Implementations should be immutable or copy-on-write.
 */
interface Element {
    val keyType: KClass<out Key<*>>
}

/**
 * Context — immutable copy-on-write hierarchical chain.
 *
 * Like a functional linked list: `plus` prepends, `minus` removes by Key type,
 * `get` traverses head-to-tail returning the first matching Element.
 *
 * Usage:
 * ```
 * val empty = Context.empty()
 * val withHtx = empty.plus(HtxKey, HtxElement(ticket))
 * val withQuic = withHtx.plus(QuicKey, QuicEngine(config))
 * val htx = withQuic.get<HtxElement>() // finds the HtxElement
 * val withoutQuic = withQuic.minus(QuicKey)
 * ```
 */
class Context private constructor(
    private val head: Pair<Key<*>, Element>?,
    private val tail: Context?
) {
    companion object {
        private val EMPTY = Context(null, null)
        fun empty(): Context = EMPTY
    }

    /** Add an element to the front of the chain (hierarchical override). */
    fun <E : Element> plus(key: Key<E>, element: E): Context =
        Context(key, this)

    /** Remove all elements matching the given Key type. */
    fun <E : Element> minus(key: Key<E>): Context {
        if (head?.elementClass == key.elementClass) return tail ?: EMPTY
        return tail?.minus(key)?.let { newTail ->
            head?.let { Context(it.first, newTail) } ?: EMPTY
        } ?: this
    }

    /** Look up an Element by Key type — head-to-tail traversal (most recent wins). */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified E : Element> get(): E? {
        if (head?.elementClass == E::class) return head.second as? E
        return tail?.get()
    }

    /** Check if the Context contains an Element for the given Key type. */
    fun <E : Element> contains(key: Key<E>): Boolean =
        get<E>() != null

    /** Number of elements in the chain. */
    val size: Int
        get() = if (head == null) 0 else 1 + (tail?.size ?: 0)

    val isEmpty: Boolean get() = head == null
    val isNotEmpty: Boolean get() = !isEmpty
}

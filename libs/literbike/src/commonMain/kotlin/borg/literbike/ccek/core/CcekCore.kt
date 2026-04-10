package borg.literbike.ccek.core

/**
 * CCEK Core — Context-Chain Element Key (literbike canonical copy)
 *
 * This is the authoritative CCEK implementation that drives all literbike modules.
 * Mirrors the Rust `ccek/core/src/lib.rs` structure:
 * - [Key]: passive provider with FACTORY
 * - [Element]: stored value, knows its Key type
 * - [Context]: immutable CoW chain, plus/minus/get
 */

/**
 * Key — passive SDK provider.
 *
 * A Key never acts. It defines:
 * - The Element type it produces (via reified type)
 * - A factory to create the canonical Element instance
 */
interface Key<E : Element> {
    val elementClass: KClass<E>
    fun factory(): E
}

/**
 * Element — stored value in a Context.
 *
 * Each Element is type-erased, send-safe, and knows its Key type.
 */
interface Element {
    val keyType: KClass<out Key<*>>
}

/**
 * Context — immutable copy-on-write hierarchical chain.
 *
 * Lookup traverses head-to-tail (most recent → oldest).
 * This enables hierarchical override: newer bindings shadow older ones.
 */
class Context private constructor(
    private val head: Pair<Key<*>, Element>?,
    private val tail: Context?
) {
    companion object {
        private val EMPTY = Context(null, null)
        fun empty(): Context = EMPTY
    }

    /** Prepend element to chain (newest binds first). */
    fun <E : Element> plus(key: Key<E>, element: E): Context =
        Context(key, this)

    /** Remove all bindings matching Key type. */
    fun <E : Element> minus(key: Key<E>): Context {
        if (head?.elementClass == key.elementClass) return tail ?: EMPTY
        return tail?.minus(key)?.let { newTail ->
            head?.let { Context(it.first, newTail) } ?: EMPTY
        } ?: this
    }

    /** Lookup by Key type — returns first match (head-to-tail). */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified E : Element> get(): E? {
        if (head?.elementClass == E::class) return head.second as? E
        return tail?.get()
    }

    /** Contains check. */
    fun <E : Element> contains(key: Key<E>): Boolean =
        get<E>() != null

    val size: Int get() = if (head == null) 0 else 1 + (tail?.size ?: 0)
    val isEmpty: Boolean get() = head == null
    val isNotEmpty: Boolean get() = !isEmpty

    /** For debugging — shows chain from head to tail. */
    override fun toString(): String {
        val elements = mutableListOf<String>()
        var current: Context? = this
        while (current?.head != null) {
            elements.add(current.head!!.second.toString())
            current = current.tail
        }
        return "Context[${elements.joinToString(", ")}]"
    }
}

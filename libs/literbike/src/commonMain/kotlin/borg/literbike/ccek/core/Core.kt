package borg.literbike.ccek.core

/**
 * CCEK Core - Shared traits and Context for all assemblies
 *
 * This is the foundation that all assemblies (agent8888, quic, sctp) depend on.
 * Provides:
 * - Element trait (1:1 with Key)
 * - Key trait (passive SDK provider)
 * - Context (hierarchical COW chain)
 *
 * Ported from Rust: /Users/jim/work/literbike/src/ccek/core/src/lib.rs
 *
 * Usage:
 * ```kotlin
 * object MyKey : Key<MyElement> {
 *     override val elementClass = MyElement::class
 *     override fun factory() = MyElement(0)
 * }
 *
 * data class MyElement(val value: Int) : Element {
 *     override val keyType = MyKey::class
 * }
 * ```
 */

// ============================================================================
// Element - stored in Context, knows its Key type
// ============================================================================

/**
 * Element trait - stored in Context, 1:1 with Key
 *
 * Each Element has exactly one Key that creates it.
 * The Element knows its Key's KClass for lookup.
 */
interface Element {
    /** Returns the KClass of this Element's Key */
    val keyType: KClass<out Key<*>>

    /** Downcast to specific type */
    fun <T : Any> cast(): T?
}

/** Type-safe downcast helper */
inline fun <reified T : Any> Element.downcast(): T? = this as? T

// ============================================================================
// Key - passive SDK provider
// ============================================================================

/**
 * Key trait - passive SDK provider, creates exactly one Element
 *
 * Key provides:
 * - factory(): Element constructor
 * - Consts, enums, strings for the protocol
 *
 * Key never takes action - it only provides the factory.
 */
interface Key<E : Element> {
    /** The KClass of the Element this Key creates (1:1 mapping) */
    val elementClass: KClass<E>

    /** Factory function - Context calls this to create Element */
    fun factory(): E
}

// ============================================================================
// Context - hierarchical COW chain
// ============================================================================

/**
 * Context - immutable Copy-on-Write hierarchical chain
 *
 * Each element in the chain is preceded by its parent (tail).
 * Lookup traverses from head to tail (most recent to oldest).
 *
 * ```text
 * Context.Empty
 *   .plus(ElementA)     → Cons { ElementA, tail: Empty }
 *   .plus(ElementB)     → Cons { ElementB, tail: Cons { ElementA, tail: Empty } }
 * ```
 *
 * Lookup: Context.get<MyKey>() → traverses chain → finds ElementA
 */
sealed class Context {
    object Empty : Context()

    data class Cons(
        val element: Element,
        val tail: Context
    ) : Context()

    companion object {
        /** Create new empty context */
        fun new(): Context = Empty
    }

    /** Add Element to chain (COW - returns new context) */
    fun plus(element: Element): Context = Cons(element, this)

    /** Convenience: add Key/Element pair */
    fun <E : Element> plus(key: Key<E>, element: E): Context = Cons(element, this)

    /** Lookup Element by Key type (reified) */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified E : Element> get(): E? = getByClass(E::class)

    private fun getByClass(targetClass: KClass<out Element>): Element? {
        return when (this) {
            is Empty -> null
            is Cons -> {
                if (element.keyType == targetClass) {
                    element
                } else {
                    tail.getByClass(targetClass)
                }
            }
        }
    }

    /** Lookup Element by Key (non-reified) */
    fun getByKey(key: Key<*>): Element? = getByClass(key.elementClass)

    /** Remove Element by Key type (COW) */
    fun minus(key: Key<*>): Context = minusByClass(key.elementClass)

    private fun minusByClass(targetClass: KClass<out Element>): Context {
        return when (this) {
            is Empty -> Empty
            is Cons -> {
                if (element.keyType == targetClass) {
                    tail.minusByClass(targetClass)
                } else {
                    Cons(element, tail.minusByClass(targetClass))
                }
            }
        }
    }

    /** Remove Element by class (reified) */
    inline fun <reified E : Element> minus(): Context = minusByClass(E::class)

    /** Check if context is empty */
    fun isEmpty(): Boolean = this is Empty

    /** Check if context is not empty */
    fun isNotEmpty(): Boolean = this !is Empty

    /** Get chain length */
    fun len(): Int = when (this) {
        is Empty -> 0
        is Cons -> 1 + tail.len()
    }

    /** Check if context contains element for key */
    fun <E : Element> contains(key: Key<E>): Boolean = getByKey(key) != null

    /** Check if context contains element by class */
    inline fun <reified E : Element> contains(): Boolean = get<E>() != null
}

// ============================================================================
// Context Element - for embedding Context in Context
// ============================================================================

/** Element that wraps a Context (for nested contexts) */
data class ContextElement(
    val context: Context,
    override val keyType: KClass<out Key<*>> = ContextElement::class
) : Element {
    override fun <T : Any> cast(): T? = this as? T
}

// ============================================================================
// Tests (mirroring Rust tests)
// ============================================================================

/** Test Key/Element pair */
object TestKey : Key<TestElement> {
    override val elementClass = TestElement::class
    override fun factory() = TestElement(42)
}

data class TestElement(
    val value: Int,
    override val keyType: KClass<out Key<*>> = TestKey::class
) : Element {
    override fun <T : Any> cast(): T? = this as? T
}

// Test: key factory produces correct value
fun testKeyFactory(): Boolean {
    val elem = TestKey.factory()
    return elem.value == 42
}

// Test: context plus/get
fun testContextPlusGet(): Boolean {
    val ctx = Context.new().plus(TestKey.factory())
    val elem = ctx.get<TestElement>()
    return elem?.value == 42
}

// Test: context minus removes key
fun testContextMinus(): Boolean {
    val ctx = Context.new().plus(TestKey.factory())
    val removed = ctx.minus(TestKey)
    return removed.isEmpty()
}

// Test: context chain length
fun testContextChainLen(): Boolean {
    val ctx = Context.new()
        .plus(TestKey.factory())
        .plus(TestKey.factory())
    return ctx.len() == 2
}

/**
 * Port of /Users/jim/work/literbike/src/reactor/context.rs
 *
 * Reactor CCEK Context Integration.
 *
 * NOTE: The Rust version references `crate::concurrency::ccek::ContextElement`
 * and `EmptyContext` from a sibling crate. Here we provide equivalent definitions
 * so that `ReactorService` can implement the same pattern.
 */
package borg.literbike.reactor

/**
 * Mirrors Rust trait from concurrency crate: `pub trait ContextElement`
 */
interface ContextElement {
    fun key(): String
    fun asAny(): Any
}

/**
 * Mirrors Rust struct from concurrency crate: `pub struct EmptyContext`
 *
 * Minimal empty context that supports `contains` check.
 */
object EmptyContext {
    operator fun plus(element: ContextElement): ContextSet =
        ContextSet(mutableListOf(element))
}

/**
 * A simple mutable set of ContextElements.
 */
class ContextSet(private val elements: MutableList<ContextElement> = mutableListOf()) :
    ContextElement {
    fun contains(key: String): Boolean = elements.any { it.key() == key }

    fun add(element: ContextElement) {
        elements.add(element)
    }

    override fun key(): String = "ContextSet"
    override fun asAny(): Any = this
}

/**
 * Mirrors Rust struct: `#[derive(Debug, Clone)] pub struct ReactorConfig`
 */
data class ReactorConfig(
    var selectTimeoutMs: Long = 100,
    var statsEnabled: Boolean = true,
)

/**
 * Mirrors Rust struct: `pub struct ReactorService`
 *
 * CCEK Context Element for Reactor Service.
 */
class ReactorService(
    val id: String,
    var config: ReactorConfig,
) : ContextElement {

    constructor() : this(
        id = "reactor-${ProcessHandle.current().pid()}",
        config = ReactorConfig(),
    )

    constructor(config: ReactorConfig) : this(
        id = "reactor-${ProcessHandle.current().pid()}",
        config = config,
    )

    override fun key(): String = "ReactorService"
    override fun asAny(): Any = this

    fun copy(): ReactorService = ReactorService(id = id, config = config.copy())
}

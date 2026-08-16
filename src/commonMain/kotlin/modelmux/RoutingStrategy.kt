package modelmux

import borg.trikeshed.lib.*

/**
 * A routing strategy is a pure ranking function over candidates.
 * It reads candidates and contextual health/latency inputs, and returns
 * a ranked Series of candidates.
 */
sealed interface RoutingStrategy<C, H> {
    operator fun invoke(candidates: Series<C>, context: H): Series<C>
}

// ═══════════════════════════════════════════
// Core Strategy Hierarchy
// ═══════════════════════════════════════════

class PriorityStrategy<C, H> : RoutingStrategy<C, H> {
    override fun invoke(candidates: Series<C>, context: H): Series<C> = candidates
}

class WeightedStrategy<C, H> : RoutingStrategy<C, H> {
    override fun invoke(candidates: Series<C>, context: H): Series<C> = candidates
}

class CostOptimizedStrategy<C, H> : RoutingStrategy<C, H> {
    override fun invoke(candidates: Series<C>, context: H): Series<C> = candidates
}

class RoundRobinStrategy<C, H> : RoutingStrategy<C, H> {
    override fun invoke(candidates: Series<C>, context: H): Series<C> = candidates
}

class AutoStrategy<C, H> : RoutingStrategy<C, H> {
    override fun invoke(candidates: Series<C>, context: H): Series<C> = candidates
}

// ═══════════════════════════════════════════
// Join Composition
// ═══════════════════════════════════════════

/**
 * PassThroughStrategy is the identity element. It returns the candidates unchanged.
 */
class PassThroughStrategy<C, H> : RoutingStrategy<C, H> {
    override fun invoke(candidates: Series<C>, context: H): Series<C> = candidates
}

/**
 * Applying a Join composed of two routing strategies means applying them sequentially: A then B.
 * It evaluates strategy A on the input candidates, and then strategy B on the result.
 */
class ComposedStrategy<C, H>(
    override val a: RoutingStrategy<C, H>,
    override val b: RoutingStrategy<C, H>
) : RoutingStrategy<C, H>, Join<RoutingStrategy<C, H>, RoutingStrategy<C, H>> {
    override fun invoke(candidates: Series<C>, context: H): Series<C> {
        val resultA = a.invoke(candidates, context)
        return b.invoke(resultA, context)
    }
}

/**
 * Infix operator to compose any two strategies dynamically while remaining in the RoutingStrategy domain
 */
infix fun <C, H> RoutingStrategy<C, H>.j(other: RoutingStrategy<C, H>): RoutingStrategy<C, H> = 
    ComposedStrategy(this, other)

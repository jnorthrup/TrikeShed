package borg.trikeshed.dag

import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.lib.view
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import borg.trikeshed.lib.right
import borg.trikeshed.lib.sortedWith

typealias AlphaPredicate = Join<String, Any?>

fun AlphaPredicate(a: String, b: Any?): AlphaPredicate = a j b

class ReteAlphaNode internal constructor(
    val predicate: AlphaPredicate,
) {
    private val matchingFacts = LinearHashMap<Pair<String, String>, ReteStoredFact>()

    var evaluationCount: Long = 0L
        private set

    internal fun accept(fact: ReteStoredFact) {
        evaluationCount++
        if (fact.fields[predicate.a] == predicate.b) {
            matchingFacts.set(fact.factId.pair, fact)
        } else {
            matchingFacts.remove(fact.factId.pair)
        }
    }

    internal fun retract(factId: FactId) {
        matchingFacts.remove(factId.pair)
    }

    fun facts(): Series<ReteStoredFact> = matchingFacts.entries().right
        .sortedWith(compareBy({ it.factId.a }, { it.factId.b }))
}

/**
 * Shared alpha predicate memories. Equal conditions from multiple rules resolve
 * to one node, so an asserted fact evaluates each distinct predicate once.
 */
class ReteAlphaMemory {
    private val nodes = LinearHashMap<Pair<String, Any?>, ReteAlphaNode>()

    fun register(predicate: AlphaPredicate): ReteAlphaNode {
        val existing = nodes.get(predicate.pair)
        if (existing != null) return existing

        val node = ReteAlphaNode(predicate)
        nodes.set(predicate.pair, node)
        return node
    }

    fun accept(fact: ReteStoredFact) {
        nodes.entries().view.forEach { (_, node) -> node.accept(fact) }
    }

    fun retract(factId: FactId) {
        nodes.entries().view.forEach { (_, node) -> node.retract(factId) }
    }
}

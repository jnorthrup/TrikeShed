package borg.trikeshed.dag

import borg.trikeshed.collections.associative.LinearHashMap

data class AlphaPredicate(
    val facetId: String,
    val expectedValue: Any?,
) {
    fun matches(fact: ReteStoredFact): Boolean =
        fact.fields[facetId] == expectedValue
}

class ReteAlphaNode internal constructor(
    val predicate: AlphaPredicate,
) {
    private val matchingFacts = LinearHashMap<FactId, ReteStoredFact>()

    var evaluationCount: Long = 0L
        private set

    internal fun accept(fact: ReteStoredFact) {
        evaluationCount++
        if (predicate.matches(fact)) {
            matchingFacts.set(fact.factId, fact)
        } else {
            matchingFacts.remove(fact.factId)
        }
    }

    internal fun retract(factId: FactId) {
        matchingFacts.remove(factId)
    }

    fun facts(): List<ReteStoredFact> = matchingFacts.entries()
        .map { it.second }
        .sortedWith(compareBy({ it.factId.partitionId }, { it.factId.localId }))
}

/**
 * Shared alpha predicate memories. Equal conditions from multiple rules resolve
 * to one node, so an asserted fact evaluates each distinct predicate once.
 */
class ReteAlphaMemory {
    private val nodes = LinearHashMap<AlphaPredicate, ReteAlphaNode>()

    fun register(predicate: AlphaPredicate): ReteAlphaNode {
        val existing = nodes.get(predicate)
        if (existing != null) return existing

        val node = ReteAlphaNode(predicate)
        nodes.set(predicate, node)
        return node
    }

    fun accept(fact: ReteStoredFact) {
        nodes.entries().forEach { (_, node) -> node.accept(fact) }
    }

    fun retract(factId: FactId) {
        nodes.entries().forEach { (_, node) -> node.retract(factId) }
    }
}

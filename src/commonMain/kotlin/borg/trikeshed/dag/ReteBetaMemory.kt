package borg.trikeshed.dag

import borg.trikeshed.collections.associative.LinearHashMap

data class BetaJoin(
    val leftFacetId: String,
    val rightFacetId: String,
)

data class BetaToken(
    val left: ReteStoredFact,
    val right: ReteStoredFact,
    val joinValue: Any?,
)

private data class BetaJoinKey(
    val partitionId: String,
    val value: Any?,
)

private data class BetaTokenId(
    val leftFactId: FactId,
    val rightFactId: FactId,
)

private class BetaBucket {
    val facts = LinearHashMap<FactId, ReteStoredFact>()
}

/**
 * Partitioned equality-join memory. Both inputs are indexed by join facet, and
 * token memory contains only current matching fact-version pairs.
 */
class ReteBetaMemory(
    private val join: BetaJoin,
) {
    private val leftIndex = LinearHashMap<BetaJoinKey, BetaBucket>()
    private val rightIndex = LinearHashMap<BetaJoinKey, BetaBucket>()
    private val leftKeys = LinearHashMap<FactId, BetaJoinKey>()
    private val rightKeys = LinearHashMap<FactId, BetaJoinKey>()
    private val tokenMemory = LinearHashMap<BetaTokenId, BetaToken>()

    fun acceptLeft(fact: ReteStoredFact) {
        retractLeft(fact.factId)
        if (!fact.fields.containsKey(join.leftFacetId)) return

        val key = BetaJoinKey(fact.factId.partitionId, fact.fields[join.leftFacetId])
        bucket(leftIndex, key).facts.set(fact.factId, fact)
        leftKeys.set(fact.factId, key)
        rightIndex.get(key)?.facts?.entries()?.forEach { (_, right) ->
            tokenMemory.set(
                BetaTokenId(fact.factId, right.factId),
                BetaToken(fact, right, key.value),
            )
        }
    }

    fun acceptRight(fact: ReteStoredFact) {
        retractRight(fact.factId)
        if (!fact.fields.containsKey(join.rightFacetId)) return

        val key = BetaJoinKey(fact.factId.partitionId, fact.fields[join.rightFacetId])
        bucket(rightIndex, key).facts.set(fact.factId, fact)
        rightKeys.set(fact.factId, key)
        leftIndex.get(key)?.facts?.entries()?.forEach { (_, left) ->
            tokenMemory.set(
                BetaTokenId(left.factId, fact.factId),
                BetaToken(left, fact, key.value),
            )
        }
    }

    fun retractLeft(factId: FactId): Boolean {
        val key = leftKeys.remove(factId) ?: return false
        val bucket = leftIndex.get(key)
        val removed = bucket?.facts?.remove(factId) != null
        if (bucket != null && bucket.facts.count == 0) leftIndex.remove(key)
        removeTokens { it.leftFactId == factId }
        return removed
    }

    fun retractRight(factId: FactId): Boolean {
        val key = rightKeys.remove(factId) ?: return false
        val bucket = rightIndex.get(key)
        val removed = bucket?.facts?.remove(factId) != null
        if (bucket != null && bucket.facts.count == 0) rightIndex.remove(key)
        removeTokens { it.rightFactId == factId }
        return removed
    }

    fun tokens(): List<BetaToken> = tokenMemory.entries()
        .map { it.second }
        .sortedWith(compareBy(
            { it.left.factId.partitionId },
            { it.left.factId.localId },
            { it.right.factId.localId },
        ))

    private fun bucket(
        index: borg.trikeshed.collections.associative.LinearHashMap<BetaJoinKey, BetaBucket>,
        key: BetaJoinKey,
    ): BetaBucket {
        val existing = index.get(key)
        if (existing != null) return existing
        val created = BetaBucket()
        index.set(key, created)
        return created
    }

    private fun removeTokens(predicate: (BetaTokenId) -> Boolean) {
        tokenMemory.entries().filter { predicate(it.first) }.forEach { tokenMemory.remove(it.first) }
    }
}

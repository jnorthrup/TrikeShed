package borg.trikeshed.dag

import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.SeriesBuffer
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.iterator
import borg.trikeshed.lib.j
import borg.trikeshed.lib.right

typealias BetaJoin = Twin<String>

fun BetaJoin(a: String, b: String): BetaJoin = a j b

typealias BetaToken = Twin<ReteStoredFact>

private typealias BetaJoinKey = Join<String, Any?>
private typealias BetaBucket = LinearHashMap<Pair<String, String>, ReteStoredFact>

/**
 * Partitioned equality-join memory. Tokens project the current matching
 * fact versions from the two input indexes.
 */
class ReteBetaMemory(
    private val join: BetaJoin,
) {
    private val leftIndex = LinearHashMap<Pair<String, Any?>, BetaBucket>()
    private val rightIndex = LinearHashMap<Pair<String, Any?>, BetaBucket>()
    private val leftKeys = LinearHashMap<Pair<String, String>, BetaJoinKey>()
    private val rightKeys = LinearHashMap<Pair<String, String>, BetaJoinKey>()

    fun acceptLeft(fact: ReteStoredFact) {
        retractLeft(fact.factId)
        if (!fact.fields.containsKey(join.a)) return

        val id = fact.factId.pair
        val key = fact.factId.a j fact.fields[join.a]
        bucket(leftIndex, key).set(id, fact)
        leftKeys.set(id, key)
    }

    fun acceptRight(fact: ReteStoredFact) {
        retractRight(fact.factId)
        if (!fact.fields.containsKey(join.b)) return

        val id = fact.factId.pair
        val key = fact.factId.a j fact.fields[join.b]
        bucket(rightIndex, key).set(id, fact)
        rightKeys.set(id, key)
    }

    fun retractLeft(factId: FactId): Boolean {
        val id = factId.pair
        val key = leftKeys.remove(id) ?: return false
        val bucket = leftIndex.get(key.pair)
        val removed = bucket?.remove(id) != null
        if (bucket != null && bucket.count == 0) leftIndex.remove(key.pair)
        return removed
    }

    fun retractRight(factId: FactId): Boolean {
        val id = factId.pair
        val key = rightKeys.remove(id) ?: return false
        val bucket = rightIndex.get(key.pair)
        val removed = bucket?.remove(id) != null
        if (bucket != null && bucket.count == 0) rightIndex.remove(key.pair)
        return removed
    }

    fun tokens(): Series<BetaToken> {
        val result = SeriesBuffer<BetaToken>()
        for ((key, left) in leftIndex.entries()) {
            val right = rightIndex.get(key)?.entries()?.right ?: continue
            for (l in left.entries().right) {
                for (r in right) result.add(l j r)
            }
        }
        result.sortWith(compareBy(
            { it.a.factId.a },
            { it.a.factId.b },
            { it.b.factId.b },
        ))
        return result.drain()
    }

    private fun bucket(
        index: LinearHashMap<Pair<String, Any?>, BetaBucket>,
        key: BetaJoinKey,
    ): BetaBucket {
        val existing = index.get(key.pair)
        if (existing != null) return existing
        val created = BetaBucket()
        index.set(key.pair, created)
        return created
    }
}

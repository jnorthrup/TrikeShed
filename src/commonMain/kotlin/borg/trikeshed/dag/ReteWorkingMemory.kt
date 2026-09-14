package borg.trikeshed.dag

import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.right
import borg.trikeshed.lib.filter
import borg.trikeshed.lib.sortedWith
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.job.ContentId

/** Partition joined with local identity. */
typealias FactId = Twin<String>

fun FactId(a: String, b: String): FactId = a j b

data class ReteStoredFact(
    val factId: FactId,
    val fields: Map<String, Any?>,
    val versionCid: ContentId,
    val board: BlackboardContext,
)

data class ReteAssertionResult(
    val isNew: Boolean,
    val fact: ReteStoredFact,
)

/**
 * Actor-confined current-version working memory for the production Rete network.
 * Stable fact identity is separate from the content CID of its current version.
 */
class ReteWorkingMemory {
    private val current = LinearHashMap<Pair<String, String>, ReteStoredFact>()

    suspend fun assert(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: ContentId,
        board: BlackboardContext,
    ): ReteAssertionResult {
        require(factId.a == board.id) {
            "fact partition ${factId.a} does not match board ${board.id}"
        }
        val existing = current.get(factId.pair)
        if (existing != null) {
            require(existing.versionCid == versionCid && existing.fields == fields) {
                "fact $factId already exists; use modify for a new version"
            }
            return ReteAssertionResult(isNew = false, fact = existing)
        }

        val fact = ReteStoredFact(factId, fields.toMap(), versionCid, board)
        current.set(factId.pair, fact)
        return ReteAssertionResult(isNew = true, fact = fact)
    }

    suspend fun modify(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: ContentId,
    ): ReteStoredFact {
        val existing = current.get(factId.pair)
            ?: error("cannot modify absent fact: $factId")
        val modified = existing.copy(fields = fields.toMap(), versionCid = versionCid)
        current.set(factId.pair, modified)
        return modified
    }

    suspend fun retract(factId: FactId): Boolean = current.remove(factId.pair) != null

    fun facts(factId: FactId): Series<ReteStoredFact> =
        current.get(factId.pair)?.let { s_[it] } ?: emptySeriesOf()

    /**
     * Every current fact across every partition, ordered by (partition, localId).
     * The projection surfaces (RDF/KIF over the plane, `/api/rete/facts`) read
     * this; call it through [ReteNetwork.snapshot] so the read is serialized
     * with the writers.
     */
    fun all(): Series<ReteStoredFact> =
        current.entries().right.sortedWith(compareBy({ it.factId.a }, { it.factId.b }))

    fun query(
        board: BlackboardContext,
        facet: Join<String, Any?>,
    ): Series<ReteStoredFact> = current.entries().right
        .filter { it.factId.a == board.id && it.fields[facet.a] == facet.b }
        .sortedWith(compareBy { it.factId.b })
}

package borg.trikeshed.dag

import borg.trikeshed.collections.LinearHashMap
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.job.ContentId

data class FactId(
    val partitionId: String,
    val localId: String,
)

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
    private val current = LinearHashMap<FactId, ReteStoredFact>()

    fun assert(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: ContentId,
        board: BlackboardContext,
    ): ReteAssertionResult {
        require(factId.partitionId == board.id) {
            "fact partition ${factId.partitionId} does not match board ${board.id}"
        }
        val existing = current.get(factId)
        if (existing != null) {
            require(existing.versionCid == versionCid && existing.fields == fields) {
                "fact $factId already exists; use modify for a new version"
            }
            return ReteAssertionResult(isNew = false, fact = existing)
        }

        val fact = ReteStoredFact(factId, fields.toMap(), versionCid, board)
        current.set(factId, fact)
        return ReteAssertionResult(isNew = true, fact = fact)
    }

    fun modify(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: ContentId,
    ): ReteStoredFact {
        val existing = current.get(factId)
            ?: error("cannot modify absent fact: $factId")
        val modified = existing.copy(fields = fields.toMap(), versionCid = versionCid)
        current.set(factId, modified)
        return modified
    }

    fun retract(factId: FactId): Boolean = current.remove(factId) != null

    fun facts(factId: FactId): List<ReteStoredFact> =
        current.get(factId)?.let(::listOf) ?: emptyList()

    fun query(
        board: BlackboardContext,
        facet: Pair<String, Any?>,
    ): List<ReteStoredFact> = current.entries()
        .asSequence()
        .map { it.second }
        .filter { it.factId.partitionId == board.id && it.fields[facet.first] == facet.second }
        .sortedWith(compareBy({ it.factId.partitionId }, { it.factId.localId }))
        .toList()
}

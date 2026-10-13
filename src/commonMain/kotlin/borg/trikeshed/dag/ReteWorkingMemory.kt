package borg.trikeshed.dag

import borg.trikeshed.collections.associative.LinearHashMap
import borg.trikeshed.collections.SeriesBuffer
import borg.trikeshed.lib.*
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
    // FactId's partition selects its primary table; a partition read never scans other boards.
    private val current = LinearHashMap<String, LinearHashMap<String, ReteStoredFact>>()

    suspend fun assert(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: ContentId,
        board: BlackboardContext,
    ): ReteAssertionResult {
        require(factId.a == board.id) {
            "fact partition ${factId.a} does not match board ${board.id}"
        }
        val partition = current[factId.a] ?: LinearHashMap<String, ReteStoredFact>().also {
            current[factId.a] = it
        }
        val existing = partition[factId.b]
        if (existing != null) {
            require(existing.versionCid == versionCid && existing.fields == fields) {
                "fact $factId already exists; use modify for a new version"
            }
            return ReteAssertionResult(isNew = false, fact = existing)
        }

        val fact = ReteStoredFact(factId, fields.toMap(), versionCid, board)
        partition[factId.b] = fact
        return ReteAssertionResult(isNew = true, fact = fact)
    }

    suspend fun modify(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: ContentId,
    ): ReteStoredFact {
        val partition = current[factId.a]
            ?: error("cannot modify absent fact: $factId")
        val existing = partition[factId.b]
            ?: error("cannot modify absent fact: $factId")
        val modified = existing.copy(fields = fields.toMap(), versionCid = versionCid)
        partition[factId.b] = modified
        return modified
    }

    suspend fun retract(factId: FactId): Boolean {
        val partition = current[factId.a] ?: return false
        val removed = partition.remove(factId.b) != null
        if (partition.count == 0) current.remove(factId.a)
        return removed
    }

    fun facts(factId: FactId): Series<ReteStoredFact> =
        current[factId.a]?.get(factId.b)?.let { s_[it] } ?: emptySeriesOf()

    /**
     * Every current fact across every partition, ordered by (partition, localId).
     * The projection surfaces (RDF/KIF over the plane, `/api/rete/facts`) read
     * this; call it through [ReteNetwork.snapshot] so the read is serialized
     * with the writers.
     */
    fun all(): Series<ReteStoredFact> = all(current.entries().left)

    /** Same ordering as [all], selecting partitions before allocation and sorting. */
    fun all(partitions: Series<String>): Series<ReteStoredFact> {
        val facts = SeriesBuffer<ReteStoredFact>()
        val selected = mutableSetOf<String>()
        for (partition in partitions.view) {
            if (!selected.add(partition)) continue
            val entries = current[partition]?.entries() ?: continue
            for (entry in entries.view) facts += entry.b
        }
        facts.sortWith(compareBy({ it.factId.a }, { it.factId.b }))
        return facts.drain()
    }

    fun query(
        board: BlackboardContext,
        facet: Join<String, Any?>,
    ): Series<ReteStoredFact> = current[board.id]
        ?.entries { _, fact -> fact.fields[facet.a] == facet.b }?.right
        ?.sortedWith(compareBy { it.factId.b }) ?: emptySeriesOf()
}

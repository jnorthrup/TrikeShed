@file:Suppress("ObjectPropertyName")

package borg.trikeshed.memory.ontology

/**
 * Facet Phase — Substrate Phase (sealed [SubstratePhase]).
 *
 * The two operational phases of memory access from the Foundation Agent Memory
 * System diagram: Retrieval ("Calling Memory for the historical information")
 * and WriteBack ("Based on the human feedback..."). These are phase ANCHORS —
 * they describe *when* a memory operation occurs, not *where* data is stored.
 * They belong to this phase facet, never to the storage-leaf facet
 * [MemorySubstrate].
 */
sealed interface SubstratePhase {

    /** One-line gloss derived from the diagram's phase labels. */
    val gloss: String
}

/** Retrieval phase — "Calling Memory for the historical information." */
object Retrieval : SubstratePhase {
    override val gloss: String = "Calling Memory for the historical information — the read phase where stored traces are queried into the working context"
}

/** Write-back phase — "Based on the human feedback..." */
object WriteBack : SubstratePhase {
    override val gloss: String = "Based on the human feedback — the write phase where experience, preference, and outcome are persisted back into memory"
}

@file:Suppress("ObjectPropertyName")

package borg.trikeshed.memory.ontology

/**
 * Facet A — Memory Substrate (sealed [MemorySubstrate]).
 *
 * The physical storage medium that backs a memory trace. From the Foundation
 * Agent Memory System diagram (Figure 2) reconciled with arXiv 2602.06052v4 §3:
 * substrate is split into parametric (inside the model) and non-parametric
 * (outside the model) storage. These are closed marker hierarchies — every
 * leaf is a storage-free singleton marker carrying a one-line gloss.
 *
 * Phase anchors ([Retrieval], [WriteBack]) live in their own facet
 * [SubstratePhase]; they are NOT storage leaves.
 */
sealed interface MemorySubstrate {

    /** One-line gloss derived from the paper's wording. */
    val gloss: String

    /**
     * Internal parametric substrate — storage baked into the model's own
     * parameters (weights, activations, KV cache). Mutated only via training
     * or gradient-based write-back.
     */
    sealed interface InternalParametric : MemorySubstrate

    /**
     * External non-parametric substrate — storage outside the model's
     * parameters, accessed via retrieval rather than gradient flow.
     */
    sealed interface ExternalNonParametric : MemorySubstrate
}

// ── Internal parametric leaves ──────────────────────────────────────

/** Tuned model weights — the persistent parametric memory updated by training. */
object ModelWeights : MemorySubstrate.InternalParametric {
    override val gloss: String = "Persistent parametric memory encoded in the model's trained weights, updated by gradient-based write-back"
}

/** Latent activations / hidden states held in memory across a forward pass. */
object LatentState : MemorySubstrate.InternalParametric {
    override val gloss: String = "Transient parametric memory carried as latent activations within a single forward pass"
}

/** Key-value cache retained for in-context token attention. */
object KvCache : MemorySubstrate.InternalParametric {
    override val gloss: String = "Parametric memory held in the key-value attention cache across the current context window"
}

// ── External non-parametric leaves ──────────────────────────────────

/** Dense embedding store for similarity-based retrieval. */
object VectorStore : MemorySubstrate.ExternalNonParametric {
    override val gloss: String = "Non-parametric memory stored as dense embedding vectors retrieved by similarity search"
}

/** Graph / tree / relational structure store. */
object StructuralStore : MemorySubstrate.ExternalNonParametric {
    override val gloss: String = "Non-parametric memory stored as structural graphs, trees, or relational schemas retrieved by traversal"
}

/** Raw or annotated text records. */
object TextRecords : MemorySubstrate.ExternalNonParametric {
    override val gloss: String = "Non-parametric memory stored as text records retrieved by lexical or semantic search"
}

/** Hierarchical file-system-style store with directory trees. */
object HierarchicalStore : MemorySubstrate.ExternalNonParametric {
    override val gloss: String = "Non-parametric memory stored in a hierarchical path-addressed tree supporting differential file access"
}

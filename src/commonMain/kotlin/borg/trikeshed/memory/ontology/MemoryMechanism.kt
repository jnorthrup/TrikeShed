@file:Suppress("ObjectPropertyName")

package borg.trikeshed.memory.ontology

/**
 * Facet B — Memory Cognitive Mechanism (sealed [MemoryMechanism]).
 *
 * The five ATOMIC cognitive mechanisms from arXiv 2602.06052v4 §3.2,
 * organized under two sealed COMPOSITE tiers: [ShortTermMemory] (Sensory +
 * Working) and [LongTermMemory] (Episodic + Semantic + Procedural). The
 * composite tiers are the paper's short/long-term grouping; the five leaves
 * are the atomic mechanisms. Every leaf is a storage-free singleton marker
 * carrying a one-line gloss consistent with the paper's definitions.
 */
sealed interface MemoryMechanism {

    /** One-line gloss derived from the paper's five-atomic-mechanism definitions. */
    val gloss: String

    /**
     * Composite tier: short-term memory — brief-retention mechanisms that
     * hold perceptual signals and actively manipulated context under an
     * online budget.
     */
    sealed interface ShortTermMemory : MemoryMechanism

    /**
     * Composite tier: long-term memory — durable mechanisms that persist
     * episodes, facts, and skills beyond the current interaction.
     */
    sealed interface LongTermMemory : MemoryMechanism
}

/** Zero-cost semantic aliases retained for the public ontology vocabulary. */
typealias ShortTermMemory = MemoryMechanism.ShortTermMemory
typealias LongTermMemory = MemoryMechanism.LongTermMemory

// ── Short-term atomic leaves ────────────────────────────────────────

/** Sensory memory — brief retention of perceptual signals before higher processing. */
object Sensory : MemoryMechanism.ShortTermMemory {
    override val gloss: String = "Brief retention of perceptual signals before higher cognitive processing"
}

/** Working memory — temporary storage plus active manipulation under an online budget. */
object Working : MemoryMechanism.ShortTermMemory {
    override val gloss: String = "Temporary storage and active manipulation of task context and intermediate states under an online processing budget"
}

// ── Long-term atomic leaves ─────────────────────────────────────────

/** Episodic memory — contextual what/where/when/outcome records. */
object Episodic : MemoryMechanism.LongTermMemory {
    override val gloss: String = "Contextual records of what happened, where, when, and the outcome of specific events"
}

/** Semantic memory — abstract facts, concepts, and schemas. */
object Semantic : MemoryMechanism.LongTermMemory {
    override val gloss: String = "Abstract facts, concepts, and generalized schemas divorced from a specific episode"
}

/** Procedural memory — reusable skills, routines, and workflows. */
object Procedural : MemoryMechanism.LongTermMemory {
    override val gloss: String = "Reusable skills, routines, and workflows encoded as executable procedures"
}

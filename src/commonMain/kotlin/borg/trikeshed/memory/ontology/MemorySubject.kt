@file:Suppress("ObjectPropertyName")

package borg.trikeshed.memory.ontology

/**
 * Facet C — Memory Subject (sealed [MemorySubject]).
 *
 * Conceptual orientations from arXiv 2602.06052v4 §3.3. User-centric and
 * agent-centric are NOT exclusive partitions — a memory node may carry one
 * orientation marker without preventing the other. Every leaf is a
 * storage-free singleton marker carrying a one-line gloss.
 */
sealed interface MemorySubject {

    /** One-line gloss derived from the paper's subject-orientation definitions. */
    val gloss: String

    /**
     * User-centric orientation — memory organized around the user's identity,
     * preferences, and dialogue continuity.
     */
    sealed interface UserCentricMemory : MemorySubject

    /**
     * Agent-centric orientation — memory organized around the agent's own
     * tasks, solutions, and skill acquisition.
     */
    sealed interface AgentCentricMemory : MemorySubject
}

/** Zero-cost semantic aliases retained for the public ontology vocabulary. */
typealias UserCentricMemory = MemorySubject.UserCentricMemory
typealias AgentCentricMemory = MemorySubject.AgentCentricMemory

// ── User-centric leaves ─────────────────────────────────────────────

/** Memory management in dialogues — continuity and coherence across turns. */
object DialogueManagement : MemorySubject.UserCentricMemory {
    override val gloss: String = "Memory management in dialogues — maintaining continuity and coherence across conversational turns"
}

/** Persistent user simulation — modeling the user to predict their needs. */
object UserSimulation : MemorySubject.UserCentricMemory {
    override val gloss: String = "Persistent user simulation — modeling the user's behavior and preferences to anticipate their needs"
}

/** Long-term personalization — stable user preferences over time. */
object LongTermPersonalization : MemorySubject.UserCentricMemory {
    override val gloss: String = "Long-term personalization — accumulating stable user preferences to tailor responses across sessions"
}

/** Privacy-preserving memory — storing user data under privacy constraints. */
object PrivacyPreservingMemory : MemorySubject.UserCentricMemory {
    override val gloss: String = "Privacy-preserving memory — retaining user information under explicit privacy and consent constraints"
}

// ── Agent-centric leaves ────────────────────────────────────────────

/** Long-horizon tasks — memory for multi-step, extended-duration goals. */
object LongHorizonTasks : MemorySubject.AgentCentricMemory {
    override val gloss: String = "Long-horizon tasks — memory supporting planning and execution across extended multi-step goals"
}

/** Domain-specific long-tail solutions — rare but recurring problem resolutions. */
object LongTailSolutions : MemorySubject.AgentCentricMemory {
    override val gloss: String = "Domain-specific long-tail solutions — capturing resolutions to rare but recurring edge-case problems"
}

/** Cross-task knowledge transfer — reusing insight across different tasks. */
object CrossTaskTransfer : MemorySubject.AgentCentricMemory {
    override val gloss: String = "Cross-task knowledge transfer — reusing learned insight and patterns across different tasks"
}

/** Strategy and skill learning — acquiring reusable policies and routines. */
object StrategySkillLearning : MemorySubject.AgentCentricMemory {
    override val gloss: String = "Strategy and skill learning — acquiring and refining reusable policies, strategies, and routines"
}

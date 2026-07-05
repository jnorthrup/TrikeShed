package borg.trikeshed.lcnc.reduction

/**
 * Universal phase marker — unifies Forge MAP/REDUCE/REREDUCE, Confix scan/buildTree, CRMS BEFORE/AFTER.
 */
sealed interface ReductionPhase {
    object MAP      : ReductionPhase  // Forge MapStage, Confix scan0
    object REDUCE   : ReductionPhase  // Forge ReduceStage, Confix buildTree, CRMS fold
    object REREDUCE : ReductionPhase  // Forge RereduceStage only
    object BEFORE   : ReductionPhase  // CRMS phase=0 (L_GET, P_GET)
    object AFTER    : ReductionPhase  // CRMS phase=1 (L_SET, P_SET)
    data class CUSTOM(val name: String) : ReductionPhase  // extensibility
}

/**
 * Phase transition rules — which phases can follow which.
 */
interface PhaseTransition {
    fun canFollow(current: ReductionPhase, next: ReductionPhase): Boolean
    fun requiredPhases(): Set<ReductionPhase>
}

/**
 * Phase algebra interface combining transitions and phase validation.
 */
interface PhaseAlg {
    val transitions: PhaseTransition
    fun validateSequence(phases: List<ReductionPhase>): Boolean
    fun nextValidPhases(current: ReductionPhase): Set<ReductionPhase>
}

/**
 * Default implementations and factories.
 */
object LcncPhaseAlg {

    /** Additional Forge phases for pipeline composition — nested here so callers
     *  reference them as [LcncPhaseAlg.FILTER] (see LcncReductionCoreTest). */
    object FILTER : ReductionPhase
    object PROJECT : ReductionPhase
    object JOIN : ReductionPhase

    /** Forge: MAP → REDUCE → REREDUCE (optional) → FILTER/PROJECT/JOIN. */
    val forgeTransitions = object : PhaseTransition {
        override fun canFollow(current: ReductionPhase, next: ReductionPhase): Boolean {
            return when (current) {
                is ReductionPhase.MAP -> next in setOf(ReductionPhase.REDUCE, ReductionPhase.REREDUCE, FILTER, PROJECT, JOIN)
                is ReductionPhase.REDUCE -> next in setOf(ReductionPhase.REREDUCE, FILTER, PROJECT, JOIN)
                is ReductionPhase.REREDUCE -> next in setOf(FILTER, PROJECT, JOIN)
                else -> false
            }
        }

        override fun requiredPhases(): Set<ReductionPhase> = setOf(ReductionPhase.MAP, ReductionPhase.REDUCE)

        override fun toString(): String = "ForgeTransitions"
    }

    /** Confix: MAP (scan0) → REDUCE (buildTree) only. */
    val confixTransitions = object : PhaseTransition {
        override fun canFollow(current: ReductionPhase, next: ReductionPhase): Boolean {
            return when (current) {
                is ReductionPhase.MAP -> next == ReductionPhase.REDUCE
                else -> false
            }
        }

        override fun requiredPhases(): Set<ReductionPhase> = setOf(ReductionPhase.MAP, ReductionPhase.REDUCE)

        override fun toString(): String = "ConfixTransitions"
    }

    /** CRMS: BEFORE → AFTER (pairing only). */
    val crmsTransitions = object : PhaseTransition {
        override fun canFollow(current: ReductionPhase, next: ReductionPhase): Boolean {
            return when (current) {
                is ReductionPhase.BEFORE -> next == ReductionPhase.AFTER
                else -> false
            }
        }

        override fun requiredPhases(): Set<ReductionPhase> = setOf(ReductionPhase.BEFORE, ReductionPhase.AFTER)

        override fun toString(): String = "CrmsTransitions"
    }

    /** Exhaustive set of every concrete phase (sealed interfaces have no enumValues). */
    private val ALL_PHASES: Set<ReductionPhase> = setOf(
        ReductionPhase.MAP, ReductionPhase.REDUCE, ReductionPhase.REREDUCE,
        ReductionPhase.BEFORE, ReductionPhase.AFTER, FILTER, PROJECT, JOIN
    )

    /** Default phase algebra for a given transition table. */
    fun defaultPhaseAlg(transitions: PhaseTransition): PhaseAlg = object : PhaseAlg {
        override val transitions: PhaseTransition = transitions
        override fun validateSequence(phases: List<ReductionPhase>): Boolean {
            if (phases.isEmpty()) return false
            // Check first phase is required
            if (phases.first() !in transitions.requiredPhases()) return false
            // Check all transitions
            for (i in 1 until phases.size) {
                if (!transitions.canFollow(phases[i - 1], phases[i])) return false
            }
            return true
        }
        override fun nextValidPhases(current: ReductionPhase): Set<ReductionPhase> =
            ALL_PHASES.filter { transitions.canFollow(current, it) }.toSet()
    }

    /** Pre-configured phase algebras. */
    val forgePhaseAlg = defaultPhaseAlg(forgeTransitions)
    val confixPhaseAlg = defaultPhaseAlg(confixTransitions)
    val crmsPhaseAlg = defaultPhaseAlg(crmsTransitions)
}

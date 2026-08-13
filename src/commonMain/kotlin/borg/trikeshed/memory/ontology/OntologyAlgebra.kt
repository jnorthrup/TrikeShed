@file:Suppress("NonAsciiCharacters", "ObjectPropertyName")

package borg.trikeshed.memory.ontology

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.α
import borg.trikeshed.lib.size

/**
 * PRELOAD algebra conformance for the memory ontology (VAL-TAX-ALGEBRA).
 *
 * Classification composition flows entirely through [Join] / the [j] infix —
 * zero [Pair] usage. Facet enumerations are [Series] of marker types built
 * with the [s_] literal, navigated with [α] lazy projections. Categorical
 * idempotency holds: no `mutableListOf` is built only to be read, no
 * `.toList()` demotion of [Series].
 */

// ── Dense typealiases for three-facet classification ────────────────

/**
 * A three-facet marker composition: substrate `j` mechanism `j` subject.
 * Built with the [j] infix, never [Pair]. A memory node may carry all three
 * facets simultaneously via this Join chain.
 */
typealias MemoryFacetMark = Join<Join<MemorySubstrate, MemoryMechanism>, MemorySubject>

/**
 * Substrate `j` mechanism — the two-facet projection (subject elided).
 */
typealias SubstrateMechanism = Join<MemorySubstrate, MemoryMechanism>

// ── Facet enumerations as Series ────────────────────────────────────

/**
 * All seven storage-substrate leaf markers as a [Series], built with the
 * [s_] literal. Navigated with [α] projections, not index loops.
 */
val substrateLeaves: Series<MemorySubstrate> = s_[
    ModelWeights, LatentState, KvCache,
    VectorStore, StructuralStore, TextRecords, HierarchicalStore,
]

/**
 * The two substrate-phase anchor markers as a [Series].
 */
val substratePhases: Series<SubstratePhase> = s_[Retrieval, WriteBack]

/**
 * All five atomic cognitive-mechanism leaf markers as a [Series].
 */
val mechanismLeaves: Series<MemoryMechanism> = s_[Sensory, Working, Episodic, Semantic, Procedural]

/**
 * All eight subject-orientation leaf markers as a [Series].
 */
val subjectLeaves: Series<MemorySubject> = s_[
    DialogueManagement, UserSimulation, LongTermPersonalization, PrivacyPreservingMemory,
    LongHorizonTasks, LongTailSolutions, CrossTaskTransfer, StrategySkillLearning,
]

// ── α projections (lazy, never materialized) ────────────────────────

/** Gloss strings of every storage substrate, lazily projected via α. */
val substrateGlosses: Series<String> = substrateLeaves α { it.gloss }

/** Gloss strings of every cognitive mechanism, lazily projected via α. */
val mechanismGlosses: Series<String> = mechanismLeaves α { it.gloss }

/** Gloss strings of every subject orientation, lazily projected via α. */
val subjectGlosses: Series<String> = subjectLeaves α { it.gloss }

/** Gloss strings of every substrate phase anchor, lazily projected via α. */
val phaseGlosses: Series<String> = substratePhases α { it.gloss }

// ── Composition helpers ─────────────────────────────────────────────

/**
 * Compose a three-facet classification mark from its singleton markers using
 * the [j] infix. The result is a [MemoryFacetMark] — one Join chain, zero
 * wrapper allocation.
 */
fun markClassification(
    substrate: MemorySubstrate,
    mechanism: MemoryMechanism,
    subject: MemorySubject,
): MemoryFacetMark = substrate j mechanism j subject

/**
 * Pair a substrate with a mechanism (subject elided) using the [j] infix.
 */
fun markSubstrateMechanism(
    substrate: MemorySubstrate,
    mechanism: MemoryMechanism,
): SubstrateMechanism = substrate j mechanism

// ── Series-backed facet lookup ──────────────────────────────────────

/**
 * Series of all composite-facet groups for navigation. Each element is a
 * [Series] of the leaves in that group.
 */
val mechanismGroups: Series<Series<MemoryMechanism>> = s_[
    s_[Sensory, Working],
    s_[Episodic, Semantic, Procedural],
]

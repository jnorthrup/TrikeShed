@file:Suppress("NonAsciiCharacters", "ObjectPropertyName")

package borg.trikeshed.memory.ontology

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.α
import borg.trikeshed.memory.MemoryFile

/**
 * Zero-cost facet classification of [MemoryFile] artifacts (VAL-TAX-DOMAIN).
 *
 * Per the PRELOAD zero-cost taxonomy mandate, facet tags applicable to
 * MemoryStore artifacts are [@JvmInline value class]es over packed [Byte]
 * ordinals (matching the `ColumnId(val raw: Byte)` template) — never heap
 * [String]s as in-process identity. Gloss strings live only as marker
 * singleton properties (documentation), not per-artifact.
 *
 * A [classify] call maps a [MemoryFile] to its facet classification [Join]
 * so a classified memory is `classification j file` — one Join composition,
 * zero wrapper allocation, no `data class` per artifact. A collection of
 * classified memories is a [Series]; classification columns are projected
 * via [α].
 */

// ── @JvmInline value classes over Byte ordinals ─────────────────────

/**
 * Zero-cost substrate facet tag — [Byte] ordinal into [substrateLeaves].
 * Template: PRELOAD `ColumnId(val raw: Byte)`. No String identity field.
 */
@JvmInline
value class SubstrateMark(val raw: Byte) {
    companion object {
        val ModelWeights = SubstrateMark(0)
        val LatentState = SubstrateMark(1)
        val KvCache = SubstrateMark(2)
        val VectorStore = SubstrateMark(3)
        val StructuralStore = SubstrateMark(4)
        val TextRecords = SubstrateMark(5)
        val HierarchicalStore = SubstrateMark(6)

        /** Resolve a singleton marker to its zero-cost ordinal tag. */
        fun from(marker: MemorySubstrate): SubstrateMark = when (marker) {
            borg.trikeshed.memory.ontology.ModelWeights -> ModelWeights
            borg.trikeshed.memory.ontology.LatentState -> LatentState
            borg.trikeshed.memory.ontology.KvCache -> KvCache
            borg.trikeshed.memory.ontology.VectorStore -> VectorStore
            borg.trikeshed.memory.ontology.StructuralStore -> StructuralStore
            borg.trikeshed.memory.ontology.TextRecords -> TextRecords
            borg.trikeshed.memory.ontology.HierarchicalStore -> HierarchicalStore
        }
    }
}

/**
 * Zero-cost mechanism facet tag — [Byte] ordinal into [mechanismLeaves].
 * No String identity field.
 */
@JvmInline
value class MechanismMark(val raw: Byte) {
    companion object {
        val Sensory = MechanismMark(0)
        val Working = MechanismMark(1)
        val Episodic = MechanismMark(2)
        val Semantic = MechanismMark(3)
        val Procedural = MechanismMark(4)

        /** Resolve a singleton marker to its zero-cost ordinal tag. */
        fun from(marker: MemoryMechanism): MechanismMark = when (marker) {
            borg.trikeshed.memory.ontology.Sensory -> Sensory
            borg.trikeshed.memory.ontology.Working -> Working
            borg.trikeshed.memory.ontology.Episodic -> Episodic
            borg.trikeshed.memory.ontology.Semantic -> Semantic
            borg.trikeshed.memory.ontology.Procedural -> Procedural
        }
    }
}

/**
 * Zero-cost subject facet tag — [Byte] ordinal into [subjectLeaves].
 * No String identity field.
 */
@JvmInline
value class SubjectMark(val raw: Byte) {
    companion object {
        val DialogueManagement = SubjectMark(0)
        val UserSimulation = SubjectMark(1)
        val LongTermPersonalization = SubjectMark(2)
        val PrivacyPreservingMemory = SubjectMark(3)
        val LongHorizonTasks = SubjectMark(4)
        val LongTailSolutions = SubjectMark(5)
        val CrossTaskTransfer = SubjectMark(6)
        val StrategySkillLearning = SubjectMark(7)

        /** Resolve a singleton marker to its zero-cost ordinal tag. */
        fun from(marker: MemorySubject): SubjectMark = when (marker) {
            borg.trikeshed.memory.ontology.DialogueManagement -> DialogueManagement
            borg.trikeshed.memory.ontology.UserSimulation -> UserSimulation
            borg.trikeshed.memory.ontology.LongTermPersonalization -> LongTermPersonalization
            borg.trikeshed.memory.ontology.PrivacyPreservingMemory -> PrivacyPreservingMemory
            borg.trikeshed.memory.ontology.LongHorizonTasks -> LongHorizonTasks
            borg.trikeshed.memory.ontology.LongTailSolutions -> LongTailSolutions
            borg.trikeshed.memory.ontology.CrossTaskTransfer -> CrossTaskTransfer
            borg.trikeshed.memory.ontology.StrategySkillLearning -> StrategySkillLearning
        }
    }
}

// ── Classification Join (zero wrapper allocation) ───────────────────

/**
 * A packed three-facet classification: substrateMark `j` mechanismMark `j`
 * subjectMark. All three are [Byte]-backed value classes — the entire
 * classification is 3 bytes on the stack, no heap allocation per artifact.
 */
typealias FacetClassification = Join<Join<SubstrateMark, MechanismMark>, SubjectMark>

/**
 * A classified memory file: [FacetClassification] `j` [MemoryFile].
 * One Join composition — zero wrapper allocation, no `data class`.
 */
typealias ClassifiedMemory = Join<FacetClassification, MemoryFile>

/**
 * Classify a [MemoryFile] artifact with its three-facet classification.
 *
 * Returns `classification j file` — one [Join], zero wrapper allocation.
 * The classification is built from [Byte]-backed value-class tags resolved
 * from the singleton markers via the [j] infix.
 */
fun classify(
    file: MemoryFile,
    substrate: MemorySubstrate,
    mechanism: MemoryMechanism,
    subject: MemorySubject,
): ClassifiedMemory {
    val classification: FacetClassification =
        SubstrateMark.from(substrate) j MechanismMark.from(mechanism) j SubjectMark.from(subject)
    return classification j file
}

// ── Series projections (board/cursor discipline) ────────────────────

/**
 * Project the substrate-classification column from a [Series] of classified
 * memories, lazily via [α]. No index loop, no `.toList()` demotion.
 */
fun Series<ClassifiedMemory>.substrateColumn(): Series<SubstrateMark> =
    this α { it.a.a.a }

/**
 * Project the mechanism-classification column from a [Series] of classified
 * memories, lazily via [α].
 */
fun Series<ClassifiedMemory>.mechanismColumn(): Series<MechanismMark> =
    this α { it.a.a.b }

/**
 * Project the subject-classification column from a [Series] of classified
 * memories, lazily via [α].
 */
fun Series<ClassifiedMemory>.subjectColumn(): Series<SubjectMark> =
    this α { it.a.b }

/**
 * Project the memory-file column from a [Series] of classified memories,
 * lazily via [α].
 */
fun Series<ClassifiedMemory>.fileColumn(): Series<MemoryFile> =
    this α { it.b }

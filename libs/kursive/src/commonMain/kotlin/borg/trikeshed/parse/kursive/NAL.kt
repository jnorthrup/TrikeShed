package borg.trikeshed.parse.kursive

import borg.trikeshed.lib.toSeries

/**
 * NAL (Non-Axiomatic Logic) levels 1-9, each corresponding to a different
 * term/compound type and inference rule in the Narsive VM.
 *
 * NAL1: Inheritance (-->) — basic subject-predicate inheritance relation
 * NAL2: Similarity (<->) — symmetric inheritance (bidirectional)
 * NAL3: Implication (==>) — conditional belief; if S then P
 * NAL4: Predictive Implication (/>) — temporal implication: if S now, then P later
 * NAL5: Concurrent Implication (=|>) — simultaneous implication: if S, then P at same time
 * NAL6: Conjunction (&&) — compound term: both A and B
 * NAL7: Disjunction (||) — compound term: either A or B
 * NAL8: Product (*) — compound term representing ordered pair
 * NAL9: Set operations (&&/, &|) — sequential and parallel composition
 *
 * Each level enables new inference rules for deriving beliefs from existing ones.
 */
enum class NALLevel(
    val label: String,
    val description: String,
    val primaryOperator: NarsiveOperator,
    val additionalOperators: List<NarsiveOperator> = emptyList(),
) {
    /** NAL1: Inheritance -- > Subject inherits from Object */
    NAL1(
        "inheritance",
        "Basic term relationship: subject --> predicate",
        NarsiveOperator.INHERITANCE,
    ),

    /** NAL2: Similarity <-> Symmetric inheritance */
    NAL2(
        "similarity",
        "Equivalence of terms: bird <-> robin",
        NarsiveOperator.SIMILARITY,
    ),

    /** NAL3: Implication == > Conditional belief */
    NAL3(
        "implication",
        "Forward implication: if S then P (==>), or equivalence (<=>)",
        NarsiveOperator.IMPLICATION,
        listOf(NarsiveOperator.EQUIVALENCE),
    ),

    /** NAL4: Predictive Implication /> Temporal forward implication */
    NAL4(
        "predictive-implication",
        "If S now then P in future: / > (with tense)",
        NarsiveOperator.PREDICTIVE_IMPLICATION,
    ),

    /** NAL5: Concurrent Implication =|> Simultaneous implication */
    NAL5(
        "concurrent-implication",
        "If S then P at same time: =|>",
        NarsiveOperator.CONCURRENT_IMPLICATION,
    ),

    /** NAL6: Conjunction && Compound with AND */
    NAL6(
        "conjunction",
        "Compound term: both A and B (&&)",
        NarsiveOperator.INTERSECTION,
    ),

    /** NAL7: Disjunction || Compound with OR */
    NAL7(
        "disjunction",
        "Compound term: either A or B (||)",
        NarsiveOperator.UNION,
    ),

    /** NAL8: Product * Ordered pair/tuple compound */
    NAL8(
        "product",
        "Compound term: ordered pair (A * B)",
        NarsiveOperator.PRODUCT,
    ),

    /** NAL9: Set operations &&/, &| Sequential and parallel composition */
    NAL9(
        "set-operations",
        "Sequential (&&/) and parallel (&&|) compound terms",
        NarsiveOperator.SEQUENTIAL,
        listOf(NarsiveOperator.PARALLEL),
    );

    /** All operators valid for this NAL level */
    val operators: List<NarsiveOperator> = listOf(primaryOperator) + additionalOperators

    /** Check if a given operator belongs to this NAL level */
    infix fun hasOperator(op: NarsiveOperator): Boolean = op in operators

    companion object {
        /** Find NAL level by operator */
        fun fromOperator(op: NarsiveOperator): NALLevel? = entries.firstOrNull { op in it.operators }

        /** Parse a NAL level string like "NAL1" or "nal1" */
        fun fromString(label: String): NALLevel? {
            val normalized = label.uppercase().trim()
            return entries.find { it.name == normalized } ?: entries.find {
                it.label == label.lowercase() || it.name.startsWith(normalized.replace("NAL", "NAL"))
            }
        }
    }
}
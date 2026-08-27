package borg.trikeshed.narsese

import borg.trikeshed.memory.ontology.FacetClassification

/**
 * AngularCodec — the angular coordinate as a FEATURE-CODED 64-bit word, not an
 * identity hash.
 *
 * The existing producers (ContentEpistemicIngest, KgTriplet.angularIdentity)
 * mint angular with FNV-1a; avalanche destroys locality, so hamming distance
 * between two FNV hashes is meaningless and `recallNear` over them is
 * exact-match-or-noise. FNV keeps its job as IDENTITY (dedup); this codec is
 * the COORDINATE the bag keys and the zipper's `near()` walk actually use.
 *
 * Layout (63..0):
 *   [63..61] relation kind        (3b — RelationKind ordinal)
 *   [60..53] ontology facet marks (8b — substrate 3b | mechanism 3b | subject 2b)
 *   [52..37] taxonomy-prefix sig  (16b — top 4 path segments, 4b FNV each)
 *   [36..21] subject term simhash (16b)
 *   [20..5]  object term simhash  (16b)
 *   [4..0]   temporal grade       (5b — TemporalGrade ordinal)
 *
 * Every field is locality-preserving under hamming: same relation/facet/prefix
 * share high bits; similar term surfaces share simhash bits.
 */
object AngularCodec {

    /**
     * Fields — the bit layout as data, not comment lore.
     *
     * Exists so block-structured covariance/shrinkage and cohort masks
     * (pen-verb taxonomy filtering, Hotelling cohorts) can address bit-blocks
     * programmatically — the pristine layer's ground. Each field is one
     * mask/shift pair; [all] lists masks in layout order (high → low).
     * Invariant: masks are pairwise disjoint and partition all 64 bits.
     */
    object Fields {
        const val RELATION_SHIFT = 61
        const val RELATION_MASK = 0x7L shl RELATION_SHIFT
        const val FACET_SHIFT = 53
        const val FACET_MASK = 0xFFL shl FACET_SHIFT
        const val TAXONOMY_SHIFT = 37
        const val TAXONOMY_MASK = 0xFFFFL shl TAXONOMY_SHIFT
        const val SUBJECT_SHIFT = 21
        const val SUBJECT_MASK = 0xFFFFL shl SUBJECT_SHIFT
        const val OBJECT_SHIFT = 5
        const val OBJECT_MASK = 0xFFFFL shl OBJECT_SHIFT
        const val GRADE_SHIFT = 0
        const val GRADE_MASK = 0x1FL shl GRADE_SHIFT

        /** Masks in layout order [63..0]; ORs to -1L, pairwise disjoint. */
        val all: LongArray = longArrayOf(
            RELATION_MASK, FACET_MASK, TAXONOMY_MASK, SUBJECT_MASK, OBJECT_MASK, GRADE_MASK,
        )

        fun extract(coord: Long, mask: Long, shift: Int): Int = ((coord and mask) ushr shift).toInt()

        fun relationOf(coord: Long): RelationKind =
            RelationKind.entries[extract(coord, RELATION_MASK, RELATION_SHIFT)]

        fun gradeOf(coord: Long): TemporalGrade =
            TemporalGrade.entries[extract(coord, GRADE_MASK, GRADE_SHIFT)]

        fun taxonomySigOf(coord: Long): Int = extract(coord, TAXONOMY_MASK, TAXONOMY_SHIFT)
        fun subjectHashOf(coord: Long): Int = extract(coord, SUBJECT_MASK, SUBJECT_SHIFT)
        fun objectHashOf(coord: Long): Int = extract(coord, OBJECT_MASK, OBJECT_SHIFT)
    }

    fun encode(
        relation: RelationKind,
        facet: FacetClassification? = null,
        taxonomyKey: String? = null,
        subjectTerm: String,
        objectTerm: String? = null,
        grade: TemporalGrade = TemporalGrade.NONE,
    ): Long = with(Fields) {
        // (v shl SHIFT) and MASK ≡ legacy (v and max) shl SHIFT — byte-identical.
        ((relation.ordinal.toLong() shl RELATION_SHIFT) and RELATION_MASK) or
            ((facetBits(facet).toLong() shl FACET_SHIFT) and FACET_MASK) or
            ((taxonomySig(taxonomyKey).toLong() shl TAXONOMY_SHIFT) and TAXONOMY_MASK) or
            ((simhash16(subjectTerm).toLong() shl SUBJECT_SHIFT) and SUBJECT_MASK) or
            ((simhash16(objectTerm ?: "").toLong() shl OBJECT_SHIFT) and OBJECT_MASK) or
            ((grade.ordinal.toLong() shl GRADE_SHIFT) and GRADE_MASK)
    }

    /** Public taxonomy signature of a key — cohort selection (Hotelling, pen verbs). */
    fun taxonomySigOfKey(key: String?): Int = taxonomySig(key)

    /**
     * The COORDINATE for a CAS fragment: 16-bit simhash of its text surface.
     * High byte = the 8-bit top zoom ring; full 16 bits = the next ring; the
     * 64-bit [encode] word = the deepest ring. Locality-preserving — near
     * duplicates land within small hamming distance — unlike the FNV identity
     * hash, which keeps its own dedup job.
     */
    fun fragmentCode(text: String): Int = simhash16(text)

    /** The 8-bit top ring of a fragment code: the sortable high byte of [fragmentCode]. */
    fun ring8(code: Int): Int = (code ushr 8) and 0xFF

    /** substrate(3b) | mechanism(3b) | subject(2b) — compressed facet locality. */
    private fun facetBits(facet: FacetClassification?): Int {
        if (facet == null) return 0
        val substrate = facet.a.a.raw.toInt() and 0x7
        val mechanism = facet.a.b.raw.toInt() and 0x7
        val subject = facet.b.raw.toInt() and 0x3
        return (substrate shl 5) or (mechanism shl 2) or subject
    }

    /** Top 4 path segments, 4 bits of FNV each — prefix-similar paths share bits. */
    private fun taxonomySig(key: String?): Int {
        if (key.isNullOrEmpty()) return 0
        var sig = 0
        val segments = key.split('/').filter { it.isNotEmpty() }
        for (i in 0 until minOf(4, segments.size)) {
            sig = sig or ((fnv1a(segments[i]) and 0xF) shl ((3 - i) * 4))
        }
        return sig
    }

    /**
     * 16-bit simhash over character trigrams: per-trigram FNV votes each bit
     * up/down; the sign field is the hash. Similar surfaces → small hamming.
     */
    fun simhash16(term: String): Int {
        if (term.isEmpty()) return 0
        val votes = IntArray(16)
        val s = term.lowercase()
        val n = s.length
        if (n < 3) {
            val h = fnv1a(s)
            for (b in 0 until 16) votes[b] += if ((h shr b) and 1 == 1) 1 else -1
        } else {
            for (i in 0..n - 3) {
                val h = fnv1a(s.substring(i, i + 3))
                for (b in 0 until 16) votes[b] += if ((h shr b) and 1 == 1) 1 else -1
            }
        }
        var out = 0
        for (b in 0 until 16) if (votes[b] > 0) out = out or (1 shl b)
        return out
    }

    private fun fnv1a(s: String): Int {
        var h = -0x7ee3623b // 0x811C9DC5 as signed Int
        for (ch in s) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return h and 0x7FFFFFFF
    }
}

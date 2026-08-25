package borg.trikeshed.narsese

import borg.trikeshed.lib.j
import borg.trikeshed.memory.ontology.MechanismMark
import borg.trikeshed.memory.ontology.SubjectMark
import borg.trikeshed.memory.ontology.SubstrateMark
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AngularCodec.Fields gate: the layout constants ARE the layout (partition of
 * 64 bits), and encode() built on them is byte-identical to the pre-Fields
 * packing arithmetic (coordinates frozen from that arithmetic, 2026-08-25).
 */
class AngularFieldsTest {

    // ── partition: disjoint, exhaustive ──────────────────────────────

    @Test
    fun masksPartitionAll64Bits() {
        val all = AngularCodec.Fields.all
        // pairwise AND == 0
        for (i in all.indices) for (k in i + 1 until all.size)
            assertEquals(0L, all[i] and all[k], "masks $i and $k overlap")
        // popCount sum == 64 and OR covers every bit
        assertEquals(64, all.sumOf { it.countOneBits() })
        assertEquals(-1L, all.fold(0L) { acc, m -> acc or m })
    }

    @Test
    fun masksSitAtTheirShifts() = with(AngularCodec.Fields) {
        // mask == fieldMax shl shift; lowest set bit at the shift position
        assertEquals(RELATION_SHIFT, RELATION_MASK.countTrailingZeroBits())
        assertEquals(FACET_SHIFT, FACET_MASK.countTrailingZeroBits())
        assertEquals(TAXONOMY_SHIFT, TAXONOMY_MASK.countTrailingZeroBits())
        assertEquals(SUBJECT_SHIFT, SUBJECT_MASK.countTrailingZeroBits())
        assertEquals(OBJECT_SHIFT, OBJECT_MASK.countTrailingZeroBits())
        assertEquals(GRADE_SHIFT, GRADE_MASK.countTrailingZeroBits())
    }

    // ── byte-identical to the pre-refactor packing ───────────────────

    @Test
    fun encodeMatchesFrozenPreRefactorCoordinates() {
        assertEquals(
            0x4016F4060A268E22L,
            AngularCodec.encode(
                RelationKind.MATCH, null, "alpha/beta/gamma",
                "subject-term", "object-term", TemporalGrade.YEAR,
            ),
        )
        // defaults: facet=null, taxonomyKey=null, objectTerm=null, grade=NONE(7)
        assertEquals(0xA10E00007L, AngularCodec.encode(RelationKind.ATTRACTION, subjectTerm = "x"))
        assertEquals(
            0xA018A462A033A100uL.toLong(),
            AngularCodec.encode(
                RelationKind.MISSING_EVIDENCE, null, "a/b/c/d/e",
                "cats are cute", "dogs", TemporalGrade.ISO_DATE,
            ),
        )
        // facet substrate=3 mechanism=4 subject=2 → facet byte 0x72
        val facet = (SubstrateMark.VectorStore j MechanismMark.Procedural) j SubjectMark.LongTermPersonalization
        val c4 = AngularCodec.encode(
            RelationKind.CAUSALITY, facet, "graph/spine",
            "hermes", "sleeve", TemporalGrade.QUARTER,
        )
        assertEquals(0x2E4E8010348C99A3L, c4)
        assertEquals(0x72, AngularCodec.Fields.extract(c4, AngularCodec.Fields.FACET_MASK, AngularCodec.Fields.FACET_SHIFT))
    }

    // ── extractors round-trip through encode ─────────────────────────

    @Test
    fun extractorsRoundTripThroughEncode() {
        val subjects = arrayOf("alpha", "subject-term", "cats are cute", "x", "hermes")
        val objects = arrayOf(null, "object-term", "dogs", "sleeve", "graph")
        val taxes = arrayOf(null, "alpha/beta/gamma", "a/b/c/d/e", "graph/spine")
        var n = 0
        for (rel in RelationKind.entries) for (grade in TemporalGrade.entries) {
            val subj = subjects[n % subjects.size]
            val obj = objects[n % objects.size]
            val tax = taxes[n % taxes.size]
            n++
            val coord = AngularCodec.encode(rel, null, tax, subj, obj, grade)
            assertEquals(rel, AngularCodec.Fields.relationOf(coord))
            assertEquals(grade, AngularCodec.Fields.gradeOf(coord))
            assertEquals(AngularCodec.simhash16(subj), AngularCodec.Fields.subjectHashOf(coord))
            assertEquals(AngularCodec.simhash16(obj ?: ""), AngularCodec.Fields.objectHashOf(coord))
        }
        assertEquals(48, n) // 6 relations × 8 grades exercised
    }

    @Test
    fun taxonomySigDependsOnlyOnTaxonomyKey() {
        // same key across differing relation/subject/object/grade → same sig; frozen sigs pin the field
        val a = AngularCodec.encode(RelationKind.MATCH, null, "alpha/beta/gamma", "subject-term", "object-term", TemporalGrade.YEAR)
        val b = AngularCodec.encode(RelationKind.GAP, null, "alpha/beta/gamma", "hermes", null, TemporalGrade.VAGUE)
        assertEquals(0xB7A0, AngularCodec.Fields.taxonomySigOf(a))
        assertEquals(AngularCodec.Fields.taxonomySigOf(a), AngularCodec.Fields.taxonomySigOf(b))
        val none = AngularCodec.encode(RelationKind.MATCH, subjectTerm = "subject-term")
        assertEquals(0, AngularCodec.Fields.taxonomySigOf(none))
    }
}

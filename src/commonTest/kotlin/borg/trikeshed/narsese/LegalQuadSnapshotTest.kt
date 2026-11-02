package borg.trikeshed.narsese

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.rdf.RdfQuad
import borg.trikeshed.rdf.RdfTerm
import kotlin.test.*

class LegalQuadSnapshotTest {
    private fun cid(s: String) = ContentId.of(s.encodeToByteArray())
    private fun row(obj: RdfTerm = RdfTerm.Literal("30 days"), graph: RdfTerm.Iri? = RdfTerm.Iri("urn:legal:graph")) =
        LegalQuadCandidate(RdfQuad(RdfTerm.Iri("urn:legal:notice"), RdfTerm.Iri("urn:legal:duration"), obj, graph),
            cid("original"), cid("text"), cid("receipt"), "30 days", 25, 32)

    @Test fun preservesTermsGraphsAndUnverifiedEvidenceClaims() {
        val candidates = listOf(row(), row(RdfTerm.Iri("urn:legal:party"), null),
            row(RdfTerm.Literal("délai", lang = "fr")))
        val bytes = LegalQuadSnapshot.encode(candidates)
        assertContentEquals(bytes, LegalQuadSnapshot.encode(candidates))
        assertEquals(candidates, LegalQuadSnapshot.decode(bytes, ContentId.of(bytes)))
        val root = CanonicalCbor.decodeMap(bytes)
        assertEquals(setOf("kind", "rows"), root.keys)
        assertFalse(root.containsKey("reviewStatus"))
    }

    @Test fun rejectsWrongDigestAndUntrustedSchema() {
        val bytes = LegalQuadSnapshot.encode(listOf(row()))
        assertFailsWith<IllegalArgumentException> { LegalQuadSnapshot.decode(bytes, cid("different")) }
        val withStatus = CanonicalCbor.encodeMap(mapOf("kind" to "legal-quad-candidates/1", "rows" to emptyList<Any>(), "reviewStatus" to "approved"))
        assertFailsWith<IllegalArgumentException> { LegalQuadSnapshot.decode(withStatus, ContentId.of(withStatus)) }
    }

    @Test fun rejectsUnsupportedTermsAndLimits() {
        assertFails { LegalQuadSnapshot.encode(listOf(row(RdfTerm.BlankNode("b")))) }
        assertFails { LegalQuadSnapshot.encode(listOf(row(RdfTerm.Literal("x", datatype = "urn:t", lang = "en")))) }
        assertFails { LegalQuadSnapshot.encode(listOf(row().copy(quad = row().quad.copy(g = RdfTerm.Iri("urn:bad graph"))))) }
        assertFails { LegalQuadSnapshot.encode(List(513) { row() }) }
        assertFails { LegalQuadSnapshot.decode(ByteArray(4_000_001), cid("unused")) }
    }

    @Test fun rejectsFractionalOrOverflowingOffsets() {
        val root = CanonicalCbor.decodeMap(LegalQuadSnapshot.encode(listOf(row())))
        val source = (root["rows"] as List<*>).single() as Map<*, *>
        fun changed(offset: Any): ByteArray {
            val fields = source.entries.associate { it.key as String to it.value }.toMutableMap()
            fields["begin"] = offset
            return CanonicalCbor.encodeMap(mapOf("kind" to "legal-quad-candidates/1", "rows" to listOf(fields)))
        }
        for (offset in listOf(1.5, Long.MAX_VALUE, -1L)) {
            val bytes = changed(offset)
            assertFails { LegalQuadSnapshot.decode(bytes, ContentId.of(bytes)) }
        }
    }

    @Test fun preservesSupplementaryTextAndRejectsUnpairedSurrogates() {
        val supplementary = "\uD83D\uDE00"
        val candidate = row(RdfTerm.Literal(supplementary)).copy(quote = supplementary, begin = 1, end = 3)
        val bytes = LegalQuadSnapshot.encode(listOf(candidate))
        assertEquals(listOf(candidate), LegalQuadSnapshot.decode(bytes, ContentId.of(bytes)))
        for (bad in listOf("\uD800", "\uDC00", "x\uD800y", "x\uDC00y")) {
            assertFailsWith<IllegalArgumentException> { LegalQuadSnapshot.encode(listOf(row(RdfTerm.Literal(bad)))) }
            assertFailsWith<IllegalArgumentException> { LegalQuadSnapshot.encode(listOf(row().copy(quote = bad))) }
            val invalidIri = row().quad.copy(s = RdfTerm.Iri("urn:legal:$bad"))
            assertFailsWith<IllegalArgumentException> { LegalQuadSnapshot.encode(listOf(row().copy(quad = invalidIri))) }
        }
    }

}

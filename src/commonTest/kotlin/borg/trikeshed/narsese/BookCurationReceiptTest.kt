package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.CorpusOntology
import kotlin.test.*

class BookCurationReceiptTest {
    @Test fun sectionNormalizationKeepsOriginalQuotationOffsets() {
        val source = "  Staff may pro-\nvide\tcare.\nStaff may help."
        val index = BookQuotationIndex(source)
        val first = assertNotNull(index.span("Staff may provide care.", 0))
        assertEquals("Staff may pro-\nvide\tcare.", source.substring(first.a))
        val second = assertNotNull(index.span("Staff may help.", first.b))
        assertEquals("Staff may help.", source.substring(second.a))
        assertNull(index.span("Staff may provide unsupported care.", 0))
    }

    @Test fun oneRetainedSourceProducesIndependentOntologyVersionsAndExactReviewCandidates() {
        val cas = CasStore.inMemory()
        val text = "Staff may provide care if funding arrives."
        val original = cas.put("%PDF-original".encodeToByteArray())
        val source = DocumentSource(original, cas.put(text.encodeToByteArray()), text, "report", "application/pdf", "run",
            provenance = mapOf("requestedUrl" to "https://example.test/report.pdf"))
        val one = CorpusOntology.retain(cas, "clinic", "1", "(subclass Provider Participant)", mapOf("staff" to listOf("Provider")))
        val two = CorpusOntology.retain(cas, "clinic", "2", "(subclass Workforce Participant)", mapOf("staff" to listOf("Workforce")))
        val spans = s_[BookClause(NormClause("staff", "may", true, "provide", "care", null, text, "if funding arrive"), 0, text.length, 0)]
        val first = BookCurationReceipt.retain(cas, "report", source, one, spans, "test-parser")
        val second = BookCurationReceipt.retain(cas, "report", source, two, spans, "test-parser")
        assertNotEquals(first, second)
        for ((cid, ontology) in arrayOf(first to one, second to two)) {
            val receipt = BookCurationReceipt.read(cas, cid)
            assertEquals(ontology.version, receipt["ontologyVersion"])
            val proposal = (receipt["relationships"] as List<*>).single() as Map<*, *>
            val selected = BookCurationReceipt.candidate(cas, cid, listOf(proposal["id"] as String))
            assertEquals(ontology.cid, selected.ontologyCid)
            assertEquals(text, selected.selections[0].quotation.quote)
            assertEquals(original, selected.selections[0].quotation.originalCid)
            assertEquals("hypothetical", (selected.selections[0].value["derivation"] as Map<*, *>)["kind"])
            assertEquals(false, (selected.selections[0].value["derivation"] as Map<*, *>)["taxonomyClosureIsCausalProof"])
            assertFailsWith<IllegalArgumentException> { BookCurationReceipt.candidate(cas, cid, listOf("invented")) }
        }
        assertFailsWith<IllegalArgumentException> { DocumentCurationFacts.reviewed(mapOf("approved" to true)) }
        assertContentEquals("%PDF-original".encodeToByteArray(), cas.get(original))
    }

    @Test fun sourceIdentityAndSpansMustMatchRetainedEvidence() {
        val cas = CasStore.inMemory()
        val cid = cas.put("Staff may help.".encodeToByteArray())
        val source = DocumentSource(cid, cid, "Changed source", "report", "text/plain", "run")
        val ontology = CorpusOntology.retain(cas, "clinic", "1", "(subclass Staff Participant)", emptyMap())
        assertFailsWith<IllegalArgumentException> {
            BookCurationReceipt.retain(cas, "report", source, ontology, emptySeriesOf(), "parser")
        }
    }
}

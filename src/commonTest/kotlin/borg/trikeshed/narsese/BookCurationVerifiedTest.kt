package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.CorpusOntology
import kotlin.test.*

/** Synthetic receipts use the real retained format; no legal source is bundled here. */
class BookCurationVerifiedTest {
    @Test fun emptyCandidateReceiptStillRetainsParserProfileAndConfiguration() {
        val cas = CasStore.inMemory()
        val text = "No modal candidates."
        val cid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(cid, cid, text, "report", "text/plain", "profile-fixture")
        val ontology = CorpusOntology.retain(cas, "fixture", "v1", "(subclass Thing Entity)", emptyMap())
        val provenance = _m["profile" j "no-kbp-v1", "configuration" j _m["annotators" j "tokenize,ssplit,pos,lemma,depparse,ner,natlog,openie"],
            "freshOnly" j true, "retainedParser" j false]
        val receipt = BookCurationReceipt.retain(cas, "report", source, ontology, emptySeriesOf(), "CoreNLP/NormClauses",
            parserProvenance = provenance)
        val decoded = BookCurationReceipt.read(cas, receipt)
        assertEquals(provenance, decoded["parserProvenance"])
        assertTrue((decoded["relationships"] as List<*>).isEmpty())
        val legacy = BookCurationReceipt.read(cas, BookCurationReceipt.retain(cas, "legacy", source, ontology, emptySeriesOf(), "fixture"))
        assertFalse(legacy.containsKey("parserProvenance"))
    }

    private class Store : CasStore(), BoundedCasReader {
        val blobs = mutableMapOf<ContentId, ByteArray>()
        override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also { blobs[it] = bytes.copyOf() }
        override fun get(cid: ContentId): ByteArray? = blobs[cid]?.copyOf()
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
            val bytes = blobs[cid] ?: return null
            require(bytes.size <= maxBytes)
            return bytes.copyOf()
        }
    }

    private class Fixture {
        val cas = Store()
        val text = "Staff may provide care if funding arrives. 😀"
        val original = cas.put("synthetic document".encodeToByteArray())
        val textCid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(original, textCid, text, "report", "text/plain", "fixture",
            anchors = _l[DocumentAnchor(0, text.length, page = 5)])
        val ontology = CorpusOntology.retain(cas, "clinic", "v1", "(subclass Provider Participant)", _m["staff" j _l["Provider"]])
        val clauses = s_[BookClause(NormClause("staff", "may", true, "provide", "care", null,
            text, "if funding arrive"), 0, text.length, 0)]
        val receipt = BookCurationReceipt.retain(cas, "report", source, ontology, clauses, "fixture")
        val bytes = requireNotNull(cas.get(receipt))
        val root = CanonicalCbor.decodeMap(bytes)
        val row = ((root["relationships"] as List<*>).single() as Map<*, *>).entries.associate { it.key as String to it.value }
        val proposalId = row["id"] as String

        fun receiptWith(rows: List<Map<String, Any?>>, change: (MutableMap<String, Any?>) -> Unit = {}): ContentId {
            val copy = root.toMutableMap()
            copy["relationships"] = rows
            change(copy)
            return cas.put(CanonicalCbor.encodeMap(copy))
        }

        fun readdress(value: Map<String, Any?>): Map<String, Any?> {
            val body = value - setOf("id", "proposalCid")
            val id = ContentId.of(CanonicalCbor.encodeMap(body)).value
            return body + _m["id" j id, "proposalCid" j id]
        }
    }

    @Test fun verifiedSelectionUsesOneBoundedReceiptReadAndPreservesExactQuotation() {
        val f = Fixture()
        val good = BookCurationReceipt.candidateVerified(f.cas, f.receipt, _l[f.proposalId])
        assertEquals(f.source.originalCid, good.selections[0].quotation.originalCid)
        assertEquals(f.text, good.selections[0].quotation.quote)
        assertTrue(BookCurationReceipt.verified(f.cas, f.receipt).matches(good))
        val forged = good.copy(selections = s_[good.selections[0].copy(value = good.selections[0].value + _m["subject" j "different"])])
        assertFalse(BookCurationReceipt.verified(f.cas, f.receipt).matches(forged))

        var reads = 0
        var unbounded = 0
        val alternating = object : CasStore(), BoundedCasReader {
            override fun get(cid: ContentId): ByteArray? { unbounded++; return f.bytes.copyOf() }
            override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
                reads++
                return if (reads == 1) f.bytes + _a[0.toByte()] else f.bytes.copyOf()
            }
        }
        assertFails { BookCurationReceipt.candidateVerified(alternating, f.receipt, _l[f.proposalId]) }
        assertEquals(1, reads)
        assertEquals(0, unbounded)
    }

    @Test fun rejectsUnboundedStoresAndConflictingOrUnboundProposalIds() {
        val f = Fixture()
        val legacy = CasStore.inMemory()
        val receipt = legacy.put(f.bytes)
        assertNotNull(BookCurationReceipt.read(legacy, receipt), "Legacy reading remains available")
        assertFails { BookCurationReceipt.candidateVerified(legacy, receipt, _l[f.proposalId]) }

        val identical = f.receiptWith(_l[f.row, f.row])
        assertEquals(1, BookCurationReceipt.candidateVerified(f.cas, identical, _l[f.proposalId]).selections.size)
        val changed = f.row + _m["subject" j "unbound"]
        val conflicting = f.receiptWith(_l[f.row, changed])
        assertFails { BookCurationReceipt.candidateVerified(f.cas, conflicting, _l[f.proposalId]) }
        val missing = f.receiptWith(_l[f.row - "proposalCid"])
        assertFails { BookCurationReceipt.candidateVerified(f.cas, missing, _l[f.proposalId]) }
        val missingCondition = f.readdress(f.row - "condition")
        val withoutCondition = f.receiptWith(_l[missingCondition])
        assertFails { BookCurationReceipt.candidateVerified(f.cas, withoutCondition, _l[missingCondition["id"] as String]) }
        val wrongId = f.receiptWith(_l[f.row + _m["proposalCid" j f.textCid.value]])
        assertFails { BookCurationReceipt.candidateVerified(f.cas, wrongId, _l[f.proposalId]) }
    }

    @Test fun validatesSourceRevisionIdsAndUtf16QuotationBeforeNarrowing() {
        val f = Fixture()
        val source = (f.root["source"] as Map<*, *>).entries.associate { it.key as String to it.value }
        val badRevision = f.receiptWith(_l[f.row]) { it["sourceRevision"] = f.textCid.value }
        assertFails { BookCurationReceipt.candidateVerified(f.cas, badRevision, _l[f.proposalId]) }
        val changedSource = source + _m["text" j "forged text"]
        val wrongText = f.receiptWith(_l[f.row]) {
            it["source"] = changedSource
            it["sourceRevision"] = ContentId.of(CanonicalCbor.encodeMap(changedSource)).value
        }
        assertFails { BookCurationReceipt.candidateVerified(f.cas, wrongText, _l[f.proposalId]) }

        val quotation = (f.row["quotation"] as Map<*, *>).entries.associate { it.key as String to it.value }
        fun invalidQuote(quote: Map<String, Any?>) {
            val row = f.readdress(f.row + _m["quotation" j quote])
            val receipt = f.receiptWith(_l[row])
            assertFails { BookCurationReceipt.candidateVerified(f.cas, receipt, _l[row["id"] as String]) }
        }
        invalidQuote(quotation + _m["begin" j 4294967296L])
        invalidQuote(quotation + _m["begin" j 0.5])
        invalidQuote(quotation + _m["originalCid" j f.textCid.value])
        invalidQuote(quotation + _m["quote" j "different"])
        val emoji = f.text.indexOf("😀")
        invalidQuote(quotation + _m["begin" j emoji, "end" j (emoji + 1), "quote" j f.text.substring(emoji, emoji + 1)])
        invalidQuote(quotation + _m["anchors" j emptyList<Any>()])
    }

    @Test fun proposalAndMappingOntologyMustMatchTheEnclosingReceipt() {
        val f = Fixture()
        fun reject(row: Map<String, Any?>) {
            val addressed = f.readdress(row)
            val receipt = f.receiptWith(_l[addressed])
            assertFails { BookCurationReceipt.candidateVerified(f.cas, receipt, _l[addressed["id"] as String]) }
        }
        reject(f.row + _m["ontologyCid" j f.textCid.value])
        reject(f.row + _m["ontologyVersion" j "other"])
        val subject = (f.row["ontologyMapping"] as Map<*, *>).entries.associate { it.key as String to it.value }
        reject(f.row + _m["ontologyMapping" j (subject + _m["ontologyCid" j f.textCid.value])])
        reject(f.row + _m["ontologyMapping" j (subject + _m["alignment" j _m["ontologyCid" j f.textCid.value]])])
        val obj = (f.row["objectMapping"] as Map<*, *>).entries.associate { it.key as String to it.value }
        reject(f.row + _m["objectMapping" j (obj + _m["version" j "other"])])
        reject(f.row + _m["ontologyMapping" j (subject + _m["corpus" j "other"])])
        reject(f.row + _m["ontologyMapping" j (subject + _m["causalProof" j true])])
        reject(f.row + _m["reviewStatus" j "approved"])
        val derivation = (f.row["derivation"] as Map<*, *>).entries.associate { it.key as String to it.value }
        reject(f.row + _m["derivation" j (derivation + _m["taxonomyClosureIsCausalProof" j true])])
    }
}

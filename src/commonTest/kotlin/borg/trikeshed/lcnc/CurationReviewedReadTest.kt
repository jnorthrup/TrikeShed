package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.ontology.CorpusOntology
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CurationReviewedReadTest {
    private class Store : CasStore(), BoundedCasReader {
        private val blobs = mutableMapOf<ContentId, ByteArray>()
        override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also { blobs[it] = bytes.copyOf() }
        override fun get(cid: ContentId): ByteArray? = blobs[cid]?.copyOf()
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = get(cid)?.also { require(it.size <= maxBytes) }
    }
    private class Log : DurableAppendLog {
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        override fun append(sequence: Long, payload: ByteArray): Long { frames.add(sequence to payload.copyOf()); return sequence }
        override fun flush() = Unit
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for ((sequence, bytes) in frames) onFrame(sequence, bytes.copyOf())
            return frames.lastOrNull()?.first ?: 0
        }
        override fun injectCorruptionAfter(sequence: Long) = error("Not used")
    }
    private class Fixture {
        val cas = Store()
        val log = Log()
        val text = "A subclass B."
        val textCid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "taxonomy", "text/plain", "synthetic-fixture")
        val ontology = CorpusOntology.retain(cas, "fixture", "v1", "(subclass A B)", emptyMap())
        val receipt = BookCurationReceipt.retain(cas, "taxonomy", source, ontology,
            s_[BookClause(NormClause("A", "generic", true, "subclass", "B", null, text, null), 0, text.length, 0)], "manual-fixture")
        val selection = ((BookCurationReceipt.read(cas, receipt)["relationships"] as List<*>).single() as Map<*, *>)["id"] as String
        var current = true
        val review = CurationReview(cas, log, resolve = { kind, cid, ids ->
            require(kind == "book")
            BookCurationReceipt.candidateVerified(cas, cid, ids)
        }, current = { candidate -> current && BookCurationReceipt.verified(cas, receipt).matches(candidate) }, clock = { 123L })
        val actor = ReviewPrincipal("synthetic:reviewer", synthetic = true)
        fun cid(value: Map<String, Any?>, key: String) = ContentId(value.getValue(key) as String)
        fun revision(value: Map<String, Any?>) = (value.getValue("revision") as Number).toLong()
        suspend fun approve(): Map<String, Any?> {
            val snapshot = review.snapshot("book", receipt, listOf(selection))
            val id = cid(snapshot, "snapshotCid")
            return withContext(actor) {
                val draft = review.assess(id, revision(snapshot), listOf(ConditionAssessment(id, selection, ConditionAssessmentState.UNCONDITIONAL)))
                review.decide(id, revision(draft), "approve", expectedAssessmentCid = cid(draft, "assessmentCid"))
            }
        }
    }

    @Test fun realRetainedBookReadDoesNotAppendOrSpendRelease(): Unit = runTest {
        val f = Fixture(); f.review.open(this)
        try {
            val approved = f.approve()
            val snapshot = f.cid(approved, "snapshotCid")
            val approval = f.cid(approved, "decisionCid")
            suspend fun read(state: Map<String, Any?>) = withContext(f.actor) {
                val before = f.log.frames.size
                val first = f.review.reviewedRead(snapshot, f.revision(state))
                val second = f.review.reviewedRead(snapshot, f.revision(state))
                assertEquals(before, f.log.frames.size)
                assertEquals(first.readCid, second.readCid)
                assertEquals(approval, first.approvalCid)
                assertEquals(f.receipt, first.candidate.curationReceiptCid)
                assertEquals(f.text, first.candidate.selections[0].quotation.quote)
                assertEquals("unconditional", first.assessment.assessments.single().state.name.lowercase())
                assertEquals(true, first.synthetic)
                val horn = ReviewedHornPlan.report(f.review, f.cas, snapshot, f.revision(state), "needsMapping")
                assertEquals("NOT_EXECUTED", horn["status"])
                assertEquals("review-record-observations", horn["scope"])
                assertEquals("UNKNOWN", horn["worldObligationApplicability"])
                assertTrue((horn["mlir"] as String).contains("selectionMappingStatus"))
                val input = borg.trikeshed.parse.reifyMap(horn["inputJson"] as String)
                val facts = input["facts"] as List<*>
                assertFalse(facts.any { (it as List<*>).first() == "subclass" }, "Norm content is never asserted as world taxonomy")
                assertTrue(facts.any { (it as List<*>).first() == "selectionModality" })
                assertTrue(facts.any { (it as List<*>).first() == "selectionOntologyMapping" })
                assertEquals(before, f.log.frames.size, "Export must not consume or append review authority")
            }
            read(approved)
            val released = f.review.release(snapshot, approval, f.revision(approved))
            read(released)
            val release = f.cid(released, "releaseCid")
            val consumed = f.review.consume(release)
            val state = f.review.inspect(snapshot)
            read(state)
            assertEquals("consume", state["state"])
            assertNotNull(consumed["admissionCid"])
            assertFailsWith<IllegalArgumentException> { f.review.consume(release) }
        } finally { f.review.drain() }
    }

    @Test fun authenticationRevisionAndCurrentnessCannotBeSuppliedByCaller(): Unit = runTest {
        val f = Fixture(); f.review.open(this)
        try {
            val approved = f.approve()
            val snapshot = f.cid(approved, "snapshotCid")
            val rev = f.revision(approved)
            assertFailsWith<IllegalStateException> { f.review.reviewedRead(snapshot, rev) }
            withContext(f.actor) {
                assertFailsWith<IllegalArgumentException> { f.review.reviewedRead(snapshot, rev - 1) }
                f.current = false
                assertFailsWith<IllegalArgumentException> { f.review.reviewedRead(snapshot, rev) }
                f.current = true
                val rejected = f.review.decide(snapshot, rev, "reject")
                assertFailsWith<IllegalArgumentException> { f.review.reviewedRead(snapshot, f.revision(rejected)) }
                assertFailsWith<IllegalArgumentException> { f.review.reviewedRead(ContentId.of("unknown".encodeToByteArray()), rev) }
            }
        } finally { f.review.drain() }
    }
}

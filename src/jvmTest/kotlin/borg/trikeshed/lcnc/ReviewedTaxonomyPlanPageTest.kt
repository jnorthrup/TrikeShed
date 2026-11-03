package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.ontology.CorpusOntology
import kotlinx.coroutines.*
import kotlin.test.*

class ReviewedTaxonomyPlanPageTest {
    class Store : CasStore(), BoundedCasReader {
        val blobs = mutableMapOf<ContentId, ByteArray>()
        override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also { blobs[it] = bytes.copyOf() }
        override fun get(cid: ContentId): ByteArray? = blobs[cid]?.copyOf()
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = get(cid)?.also { require(it.size <= maxBytes) }
    }

    class Log : DurableAppendLog {
        val frames = mutableListOf<Join<Long, ByteArray>>()
        override fun append(sequence: Long, payload: ByteArray): Long { frames.add(sequence j payload.copyOf()); return sequence }
        override fun flush() = Unit
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for ((sequence, bytes) in frames) onFrame(sequence, bytes.copyOf())
            return frames.lastOrNull()?.a ?: 0
        }
        override fun injectCorruptionAfter(sequence: Long) = error("Not used")
    }

    @Test fun approvedSelectionCanBeInspectedInRevisionBoundPages(): Unit = runBlocking {
        val cas = Store()
        val log = Log()
        val text = "A subclass B."
        val textCid = cas.put(text.encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "taxonomy", "text/plain", "synthetic-fixture")
        val ontology = CorpusOntology.retain(cas, "fixture", "v1", "(subclass A B)", emptyMap())
        val clauses = (0 until 65).toSeries() α { section ->
            BookClause(NormClause("A", "generic", true, "subclass", "B", null, text, null), 0, text.length, section)
        }
        val receipt = BookCurationReceipt.retain(cas, "taxonomy", source, ontology, clauses, "manual-fixture")
        val ids = (BookCurationReceipt.read(cas, receipt)["relationships"] as List<*>).map {
            (it as Map<*, *>)["id"] as String
        }
        assertEquals(65, ids.distinct().size)
        var checks = 0
        var failAt = Int.MAX_VALUE
        val review = CurationReview(cas, log,
            resolve = { kind, cid, selection ->
                require(kind == "book")
                BookCurationReceipt.candidateVerified(cas, cid, selection)
            },
            current = { candidate -> ++checks < failAt && BookCurationReceipt.verified(cas, receipt).matches(candidate) },
            clock = { 123L })
        review.open(this)
        try {
            val snapshot = review.snapshot("book", receipt, ids)
            val snapshotCid = ContentId(snapshot["snapshotCid"] as String)
            fun revision(value: Map<String, Any?>) = (value["revision"] as Number).toLong()
            val reviewer = ReviewPrincipal("synthetic:analyst", synthetic = true)
            assertFailsWith<IllegalStateException> {
                ReviewedTaxonomyPlan.report(review, cas, snapshotCid, revision(snapshot), "subclass", "A", "B")
            }
            withContext(reviewer) {
                val draft = review.assess(snapshotCid, revision(snapshot), ids.map {
                    ConditionAssessment(snapshotCid, it, ConditionAssessmentState.UNCONDITIONAL)
                })
                val approved = review.decide(snapshotCid, revision(draft), "approve",
                    expectedAssessmentCid = ContentId(draft["assessmentCid"] as String))
                val revision = revision(approved)
                val before = log.frames.size
                suspend fun page(offset: Int, limit: Int = 64) =
                    ReviewedTaxonomyPlan.report(review, cas, snapshotCid, revision, "subclass", "A", "B", offset, limit)
                val first = page(0)
                val second = page(64)
                val repeat = page(0)
                fun entries(report: Map<String, Any?>) = (report["selected"] as List<*>).map { (it as Map<*, *>)["selectionId"] as String }
                assertEquals(ids, entries(first) + entries(second))
                assertEquals(64, ((first["page"] as Map<*, *>)["nextOffset"] as Number).toInt())
                assertNull((second["page"] as Map<*, *>)["nextOffset"])
                assertEquals(65, ((first["page"] as Map<*, *>)["total"] as Number).toInt())
                assertEquals(64, (first["assessments"] as List<*>).size)
                assertEquals(1, (second["assessments"] as List<*>).size)
                assertEquals(first["readFrameCid"], repeat["readFrameCid"])
                assertNotEquals(first["readFrameCid"], second["readFrameCid"])
                assertEquals("UNKNOWN", first["status"])
                assertEquals(false, first["positivePathProven"])
                assertEquals(before, log.frames.size)
                assertFailsWith<IllegalArgumentException> { page(66) }
                assertFailsWith<IllegalArgumentException> { page(0, 65) }
                failAt = checks + 2
                assertFailsWith<IllegalArgumentException> { page(64) }
            }
        } finally { review.drain() }
    }
}

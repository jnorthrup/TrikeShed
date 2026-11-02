package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.BoundedCasReader
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.ConditionReviewPolicy
import borg.trikeshed.lcnc.CurationReview
import borg.trikeshed.lcnc.CurationReviewCandidate
import borg.trikeshed.lcnc.CurationReviewQuotation
import borg.trikeshed.lcnc.CurationReviewSelection
import borg.trikeshed.lcnc.ReviewPrincipal
import borg.trikeshed.lib.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceScopedQueryTest {
    private class Store : CasStore(), BoundedCasReader {
        val blobs = mutableMapOf<ContentId, ByteArray>()
        override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also { blobs[it] = bytes.copyOf() }
        override fun get(cid: ContentId): ByteArray? = blobs[cid]?.copyOf()?.also { check(ContentId.of(it) == cid) }
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = blobs[cid]?.let {
            require(it.size <= maxBytes)
            it.copyOf().also { bytes -> check(ContentId.of(bytes) == cid) }
        }
    }

    private class Log : DurableAppendLog {
        private val frames = mutableListOf<Pair<Long, ByteArray>>()
        private var flushed = 0
        override fun append(sequence: Long, payload: ByteArray): Long {
            frames.add(sequence to payload.copyOf()); return sequence
        }
        override fun flush() { flushed = frames.size }
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for ((sequence, payload) in frames.take(flushed)) onFrame(sequence, payload.copyOf())
            return frames.take(flushed).lastOrNull()?.first ?: 0
        }
        override fun injectCorruptionAfter(sequence: Long) = error("Not used")
    }

    private class Fixture(private val withGraph: Boolean, conflictingQualifier: Boolean,
        sameProposition: Boolean, missingPolarity: Boolean) {
        val cas = Store()
        val log = Log()
        val firstQuote = "Alice owns the orchard."
        val secondQuote = if (sameProposition) "Alice also owns the orchard." else "Alice leases the orchard."
        val text = "$firstQuote\n$secondQuote"
        val sourceCid = cas.put(text.encodeToByteArray())
        val receipt = cas.put("synthetic curation".encodeToByteArray())
        val ontology = cas.put("synthetic ontology".encodeToByteArray())
        val graph = if (withGraph) "urn:fixture:graph" else "urn:trikeshed:source-text:${sourceCid.value}"
        var current = true
        private fun selection(id: String, predicate: String, quote: String, modality: String = "may",
            polarity: Boolean? = true): CurationReviewSelection {
            val begin = text.indexOf(quote)
            val value: MutableMap<String, Any?> = _m["subject" j ("Alice" as Any?), "predicate" j predicate,
                "object" j "the orchard", "modality" j modality, "polarity" j polarity].toMutableMap()
            if (polarity == null) value.remove("polarity")
            if (withGraph) value["graph"] = graph
            return CurationReviewSelection(id, "relationship", value,
                CurationReviewQuotation(sourceCid, sourceCid, begin, begin + quote.length, quote))
        }
        val selected = _l[selection("p:0", "owns", firstQuote),
            selection("p:1", if (sameProposition) "owns" else "leases", secondQuote)] +
            if (conflictingQualifier || missingPolarity)
                _l[selection("p:2", "owns", firstQuote, "must", if (missingPolarity) null else false)] else emptyList()
        val candidate = CurationReviewCandidate("book", "synthetic:source-query", receipt, ontology,
            "synthetic:source-revision", selected.toSeries())
        val principal = ReviewPrincipal("synthetic:reviewer", synthetic = true)
        var tick = 0L
        val review = CurationReview(cas, log, { kind, cid, ids ->
            require(kind == "book" && cid == receipt && ids == selected.map { it.id })
            candidate
        }, { current && it.sourceRevision == candidate.sourceRevision && it.ontologyCid == candidate.ontologyCid },
            { ++tick }, ConditionReviewPolicy.STRICT_BOOK)

        fun request() = SourceQuery(sourceCid, sourceCid, graph, "Alice", "owns", "the orchard")
        private fun cid(value: Map<String, Any?>, key: String) = ContentId(value.getValue(key) as String)
        private fun revision(value: Map<String, Any?>) = (value.getValue("revision") as Number).toLong()
        suspend fun release(): ContentId {
            val snapshot = review.snapshot("book", receipt, selected.map { it.id })
            val id = cid(snapshot, "snapshotCid")
            val draft = withContext(principal) {
                review.assess(id, revision(snapshot), selected.map {
                    ConditionAssessment(id, it.id, ConditionAssessmentState.UNCONDITIONAL)
                })
            }
            val approval = withContext(principal) {
                review.decide(id, revision(draft), "approve", expectedAssessmentCid = cid(draft, "assessmentCid"))
            }
            return cid(review.release(id, cid(approval, "decisionCid"), revision(approval)), "releaseCid")
        }
    }

    private suspend fun withFixture(withGraph: Boolean = true, conflictingQualifier: Boolean = false,
        sameProposition: Boolean = false, missingPolarity: Boolean = false,
        block: suspend (Fixture, ContentId) -> Unit) = coroutineScope {
        val fixture = Fixture(withGraph, conflictingQualifier, sameProposition, missingPolarity)
        fixture.review.open(this)
        try { block(fixture, fixture.release()) } finally { fixture.review.drain() }
    }

    @Test fun reviewedExactQuadReturnsTheRetainedQuote() = runBlocking {
        withTimeout(10_000) { withFixture { f, release ->
            val result = SourceScopedQuery.query(f.review, f.cas, release, f.request())
            assertEquals(SourceQueryStatus.MATCH, result.status)
            val match = result.matches.single()
            assertEquals("Alice", match.subject)
            assertEquals("owns", match.predicate)
            assertEquals("the orchard", match.obj)
            assertEquals(f.graph, match.graph)
            assertEquals("reviewed-selection", match.graphOrigin)
            assertEquals(f.sourceCid, match.originalCid)
            assertEquals(f.sourceCid, match.extractedTextCid)
            assertEquals("p:0", match.selectionId)
            assertEquals(f.firstQuote, match.quote)
            assertEquals(f.firstQuote, f.text.substring(match.begin, match.end))
            assertEquals("unconditional", match.conditionState)
            assertEquals("may", match.modality)
            assertEquals(true, match.polarity)
            assertTrue(match.propositionCid.value.startsWith("sha256:"))
            assertTrue(match.occurrenceCid.value.startsWith("sha256:"))
            assertEquals(match.occurrenceCid, ContentId.of(CanonicalCbor.encodeMap(_m[
                "kind" j "source-scoped-occurrence/1", "snapshotCid" j match.snapshotCid.value,
                "selectionId" j match.selectionId, "propositionCid" j match.propositionCid.value,
                "originalCid" j match.originalCid.value, "textCid" j match.extractedTextCid.value,
                "begin" j match.begin, "end" j match.end, "quote" j match.quote])))
            assertFailsWith<IllegalArgumentException> { SourceScopedQuery.query(f.review, f.cas, release, f.request()) }
        } }
    }

    @Test fun sourceGraphAndPredicateMismatchStayUnknown() = runBlocking {
        withTimeout(10_000) {
            for (kind in _l["original", "extracted", "graph", "predicate", "object"]) withFixture { f, release ->
                val q = f.request()
                val other = ContentId.of("different source".encodeToByteArray())
                val request = when (kind) {
                    "original" -> q.copy(originalCid = other)
                    "extracted" -> q.copy(extractedTextCid = other)
                    "graph" -> q.copy(graph = "urn:other")
                    "predicate" -> q.copy(predicate = "rents")
                    else -> q.copy(obj = "another orchard")
                }
                val result = SourceScopedQuery.query(f.review, f.cas, release, request)
                assertEquals(SourceQueryStatus.UNKNOWN, result.status, kind)
                assertTrue(result.matches.isEmpty(), kind)
                assertFailsWith<IllegalArgumentException> { SourceScopedQuery.query(f.review, f.cas, release, f.request()) }
            }
        }
    }

    @Test fun coordinateCollisionQuarantinesTheMatch() = runBlocking {
        withTimeout(10_000) { withFixture { f, release ->
            val result = SourceScopedQuery.query(f.review, f.cas, release, f.request(), coordinate = { 0L })
            assertEquals(SourceQueryStatus.COLLISION, result.status)
            assertTrue(result.matches.isEmpty())
            assertFailsWith<IllegalArgumentException> { SourceScopedQuery.query(f.review, f.cas, release, f.request()) }
        } }
    }

    @Test fun opposingQualifiersRequireAnExplicitSelection() = runBlocking {
        withTimeout(10_000) {
            withFixture(conflictingQualifier = true) { f, release ->
                val result = SourceScopedQuery.query(f.review, f.cas, release, f.request())
                assertEquals(SourceQueryStatus.UNKNOWN, result.status)
                assertTrue(result.matches.isEmpty())
                assertFailsWith<IllegalArgumentException> { SourceScopedQuery.query(f.review, f.cas, release, f.request()) }
            }
            withFixture(conflictingQualifier = true) { f, release ->
                val result = SourceScopedQuery.query(f.review, f.cas, release,
                    f.request().copy(modality = "may", polarity = true))
                assertEquals(SourceQueryStatus.MATCH, result.status)
                val match = result.matches.single()
                assertEquals("may", match.modality)
                assertEquals(true, match.polarity)
                assertEquals("p:0", match.selectionId)
                assertEquals(f.firstQuote, match.quote)
            }
        }
    }

    @Test fun sourceBecomingStaleDuringEvaluationCannotYieldAMatch() = runBlocking {
        withTimeout(10_000) { withFixture { f, release ->
            val result = SourceScopedQuery.query(f.review, f.cas, release, f.request(), coordinate = {
                f.current = false
                0L
            })
            assertEquals(SourceQueryStatus.UNKNOWN, result.status)
            assertTrue(result.matches.isEmpty())
        } }
    }

    @Test fun missingPolarityCannotHideAnOpposingReviewedSelection() = runBlocking {
        withTimeout(10_000) { withFixture(missingPolarity = true) { f, release ->
            val result = SourceScopedQuery.query(f.review, f.cas, release,
                f.request().copy(modality = "may", polarity = true))
            assertEquals(SourceQueryStatus.UNKNOWN, result.status)
            assertTrue(result.matches.isEmpty())
            assertFailsWith<IllegalArgumentException> { SourceScopedQuery.query(f.review, f.cas, release, f.request()) }
        } }
    }

    @Test fun defaultGraphIsBoundToTheExtractedSource() = runBlocking {
        withTimeout(10_000) { withFixture(withGraph = false) { f, release ->
            val result = SourceScopedQuery.query(f.review, f.cas, release, f.request())
            assertEquals(SourceQueryStatus.MATCH, result.status)
            assertEquals(f.graph, result.matches.single().graph)
            assertEquals("derived-extracted-text", result.matches.single().graphOrigin)
        } }
    }

    @Test fun samePropositionRetainsDistinctSourceOccurrences() = runBlocking {
        withTimeout(10_000) { withFixture(sameProposition = true) { f, release ->
            val result = SourceScopedQuery.query(f.review, f.cas, release, f.request())
            assertEquals(SourceQueryStatus.MATCH, result.status)
            assertEquals(2, result.matches.size)
            assertEquals(1, result.matches.map { it.propositionCid }.toSet().size)
            assertEquals(2, result.matches.map { it.occurrenceCid }.toSet().size)
            assertEquals(_s[f.firstQuote, f.secondQuote], result.matches.map { it.quote }.toSet())
            for (match in result.matches) assertEquals(match.quote, f.text.substring(match.begin, match.end))
        } }
    }

    @Test fun forgedAndStaleReleaseCannotSupplyResults() = runBlocking {
        withTimeout(10_000) {
            withFixture { f, _ ->
                val fabricated = f.cas.put("plausible fabricated release".encodeToByteArray())
                assertFailsWith<IllegalStateException> { SourceScopedQuery.query(f.review, f.cas, fabricated, f.request()) }
            }
            withFixture { f, release ->
                f.current = false
                assertFailsWith<IllegalArgumentException> { SourceScopedQuery.query(f.review, f.cas, release, f.request()) }
            }
        }
    }
}

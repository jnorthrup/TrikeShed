package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Synthetic records exercise the real gate and WAL replay; no analyst identity is inferred from input. */
class CurationReviewTest {
    private class Log : DurableAppendLog {
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        var flushed = 0
        var failFlush = false
        override fun append(sequence: Long, payload: ByteArray): Long {
            frames.add(sequence to payload.copyOf()); return sequence
        }
        override fun flush() { check(!failFlush) { "Synthetic flush failure" }; flushed = frames.size }
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for ((sequence, payload) in frames.take(flushed)) onFrame(sequence, payload.copyOf())
            return frames.take(flushed).lastOrNull()?.first ?: 0
        }
        override fun injectCorruptionAfter(sequence: Long) { error("Not used") }
    }

    private class Fixture {
        val cas = CasStore.inMemory()
        val log = Log()
        val source = "Synthetic clinic provides family planning."
        val textCid = cas.put(source.encodeToByteArray())
        val ontology = cas.put("synthetic ontology version 1".encodeToByteArray())
        val receipt = cas.put("synthetic retained curation 1".encodeToByteArray())
        val terms = mutableMapOf<String, Any?>("subject" to "Synthetic clinic", "predicate" to "provides", "object" to "family planning")
        var candidate = CurationReviewCandidate("book", "synthetic:clinic", receipt, ontology, "source:1",
            s_[CurationReviewSelection("p:0", "relationship", terms,
                CurationReviewQuotation(textCid, textCid, 0, source.length, source))])
        var current = true
        var tick = 0L
        val principal = ReviewPrincipal("synthetic:lcnc-validation", synthetic = true)
        fun service() = CurationReview(cas, log, { kind, cid, ids ->
            require(kind == "book" && cid == candidate.curationReceiptCid && ids == listOf("p:0"))
            candidate
        }, { current && it.sourceRevision == candidate.sourceRevision && it.ontologyCid == candidate.ontologyCid }, { ++tick })
        suspend fun snapshot(service: CurationReview) = service.snapshot("book", candidate.curationReceiptCid, listOf("p:0"))
        suspend fun approve(service: CurationReview, view: Map<String, Any?>) = withContext(principal) {
            service.decide(cid(view, "snapshotCid"), revision(view), "approve", "Synthetic validation only")
        }
    }

    @Test fun unauthenticatedDraftRejectedAndFabricatedDecisionsCannotRelease() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(); service.open(this)
            try {
                var view = f.snapshot(service)
                val snapshot = cid(view, "snapshotCid")
                assertFailsWith<IllegalStateException> { service.decide(snapshot, revision(view), "approve") }
                assertFailsWith<IllegalArgumentException> { service.release(snapshot, cid(view, "decisionCid"), revision(view)) }
                view = withContext(f.principal) { service.decide(snapshot, revision(view), "draft") }
                assertFailsWith<IllegalArgumentException> { service.release(snapshot, cid(view, "decisionCid"), revision(view)) }
                view = withContext(f.principal) { service.decide(snapshot, revision(view), "reject") }
                assertFailsWith<IllegalArgumentException> { service.release(snapshot, cid(view, "decisionCid"), revision(view)) }
                view = f.approve(service, view)
                val fabricated = f.cas.put(CanonicalCbor.encodeMap(mapOf("action" to "approve", "snapshotCid" to snapshot.value,
                    "reviewer" to "fabricated", "revision" to revision(view))))
                assertFailsWith<IllegalArgumentException> { service.release(snapshot, fabricated, revision(view)) }
                val fakeSnapshot = f.cas.put(CanonicalCbor.encodeMap(mapOf("kind" to "book", "family" to "synthetic:clinic")))
                assertFailsWith<IllegalArgumentException> { service.inspect(fakeSnapshot) }
                assertFailsWith<IllegalStateException> { service.consume(fabricated) }
            } finally { service.drain() }
        }
    }

    @Test fun releaseCarriesFrozenEvidenceAndConsumesExactlyOnceAcrossReplay() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val first = f.service(); first.open(this)
            val snapshot = f.snapshot(first)
            f.terms["object"] = "EDIT AFTER SNAPSHOT"
            val approved = f.approve(first, snapshot)
            val release = first.release(cid(approved, "snapshotCid"), cid(approved, "decisionCid"), revision(approved))
            assertEquals("synthetic:lcnc-validation", release["reviewer"])
            assertEquals(true, release["synthetic"])
            val relationship = (release["relationships"] as List<*>).single() as Map<*, *>
            assertEquals("family planning", (relationship["value"] as Map<*, *>)["object"])
            assertEquals(approved["decisionCid"], relationship["approvalCid"])
            assertEquals(f.source, (relationship["quotation"] as Map<*, *>)["quote"])
            first.drain()
            val resumed = f.service(); resumed.open(this)
            try {
                val consumed = resumed.consume(cid(release, "releaseCid"))
                assertNotNull(consumed["admissionCid"])
                assertFailsWith<IllegalArgumentException> { resumed.consume(cid(release, "releaseCid")) }
            } finally { resumed.drain() }
            val replay = f.service(); replay.open(this)
            try { assertFailsWith<IllegalArgumentException> { replay.consume(cid(release, "releaseCid")) } }
            finally { replay.drain() }
        }
    }

    @Test fun concurrentReleaseAndStaleRevisionCannotReuseAnApproval() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(); service.open(this)
            try {
                val view = f.snapshot(service)
                val approved = f.approve(service, view)
                assertFailsWith<IllegalArgumentException> {
                    withContext(f.principal) { service.decide(cid(view, "snapshotCid"), revision(view), "reject") }
                }
                val results = List(2) { async { runCatching {
                    service.release(cid(approved, "snapshotCid"), cid(approved, "decisionCid"), revision(approved))
                } } }.awaitAll()
                assertEquals(1, results.count { it.isSuccess })
                assertEquals(1, results.count { it.isFailure })
            } finally { service.drain() }
        }
    }

    @Test fun correctionsWithdrawPendingPermitsAndPreservePriorSnapshot() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(); service.open(this)
            try {
                val before = f.approve(service, f.snapshot(service))
                val release = service.release(cid(before, "snapshotCid"), cid(before, "decisionCid"), revision(before))
                service.invalidate(f.receipt)
                assertFailsWith<IllegalArgumentException> { service.consume(cid(release, "releaseCid")) }
                val original = service.inspect(cid(before, "snapshotCid"))
                assertEquals("invalidate", original["state"])
                assertEquals(false, original["current"])
                assertFailsWith<IllegalArgumentException> { f.approve(service, original) }
                assertFailsWith<IllegalArgumentException> { f.snapshot(service) }
                f.candidate = f.candidate.copy(curationReceiptCid = f.cas.put("synthetic corrected curation".encodeToByteArray()), sourceRevision = "source:2")
                val replacement = f.snapshot(service)
                assertNotEquals(before["snapshotCid"], replacement["snapshotCid"])
                assertEquals(false, service.inspect(cid(before, "snapshotCid"))["current"])
                val history = replacement["history"] as List<*>
                assertEquals(before["snapshotCid"], (history.last() as Map<*, *>)["previousSnapshotCid"])
                assertFailsWith<IllegalArgumentException> {
                    service.release(cid(replacement, "snapshotCid"), cid(before, "decisionCid"), revision(replacement))
                }
            } finally { service.drain() }
        }
    }

    @Test fun changedSourceOntologyOrMissingQuoteFailsClosed() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(); service.open(this)
            try {
                val approved = f.approve(service, f.snapshot(service))
                f.current = false
                assertEquals(false, service.inspect(cid(approved, "snapshotCid"))["sourceCurrent"])
                assertFailsWith<IllegalArgumentException> {
                    service.release(cid(approved, "snapshotCid"), cid(approved, "decisionCid"), revision(approved))
                }
                f.current = true
                f.candidate = f.candidate.copy(ontologyCid = f.cas.put("synthetic ontology version 2".encodeToByteArray()))
                assertFailsWith<IllegalArgumentException> {
                    service.release(cid(approved, "snapshotCid"), cid(approved, "decisionCid"), revision(approved))
                }
                val selection = f.candidate.selections[0]
                f.candidate = f.candidate.copy(selections = s_[selection.copy(quotation = selection.quotation.copy(quote = "fabricated quote"))])
                assertFailsWith<IllegalArgumentException> { f.snapshot(service) }
            } finally { service.drain() }
        }
    }

    @Test fun uncertainPersistencePreventsFurtherPermission() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(); service.open(this)
            try {
                val snapshot = f.snapshot(service)
                f.log.failFlush = true
                assertFailsWith<IllegalStateException> { f.approve(service, snapshot) }
                f.log.failFlush = false
                assertFailsWith<IllegalStateException> { f.approve(service, snapshot) }
                assertFailsWith<IllegalStateException> { service.inspect(cid(snapshot, "snapshotCid")) }
            } finally { service.drain() }
        }
    }

    companion object {
        private fun cid(view: Map<String, Any?>, key: String) = ContentId(view[key] as String)
        private fun revision(view: Map<String, Any?>) = (view["revision"] as Number).toLong()
    }
}

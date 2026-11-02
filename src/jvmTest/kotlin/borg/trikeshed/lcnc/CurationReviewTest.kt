package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.ConditionAssessment
import borg.trikeshed.narsese.ConditionAssessmentCodec
import borg.trikeshed.narsese.ConditionAssessmentState
import borg.trikeshed.narsese.ConditionSpan
import borg.trikeshed.narsese.DocumentCurationFacts
import kotlinx.coroutines.*
import kotlin.test.*

/** Synthetic records exercise the real gate and WAL replay; no analyst identity is inferred from input. */
class CurationReviewTest {
    /** Test store enforces the limit before copying a blob to the caller. */
    private class Store : CasStore(), BoundedCasReader {
        val blobs = mutableMapOf<ContentId, ByteArray>()
        override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also { blobs[it] = bytes.copyOf() }
        override fun get(cid: ContentId): ByteArray? = blobs[cid]?.copyOf()?.also { check(ContentId.of(it) == cid) }
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
            val bytes = blobs[cid] ?: return null
            require(bytes.size <= maxBytes)
            return bytes.copyOf().also { check(ContentId.of(it) == cid) }
        }
    }

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
        val cas = Store()
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
        var currentGate: CompletableDeferred<Unit>? = null
        var currentEntered: CompletableDeferred<Unit>? = null
        var tick = 0L
        val principal = ReviewPrincipal("synthetic:lcnc-validation", synthetic = true)
        fun service(policy: ConditionReviewPolicy = ConditionReviewPolicy.LEGACY) = CurationReview(cas, log, { kind, cid, ids ->
            require(kind == "book" && cid == candidate.curationReceiptCid && ids == candidate.selections.view.map { it.id })
            candidate
        }, {
            currentEntered?.complete(Unit)
            currentGate?.await()
            current && it.sourceRevision == candidate.sourceRevision && it.ontologyCid == candidate.ontologyCid
        }, { ++tick }, policy)
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

    @Test fun authenticatedConditionDraftFreezesSourceSpansAndSurvivesReplayWithoutApproval() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val first = f.service(); first.open(this)
            val snapshot = f.snapshot(first)
            val snapshotCid = cid(snapshot, "snapshotCid")
            val quote = "family planning"
            val begin = f.source.indexOf(quote)
            val spans = mutableListOf(ConditionSpan(f.textCid, begin, begin + quote.length, quote))
            val assessments = mutableListOf(ConditionAssessment(snapshotCid, "p:0", ConditionAssessmentState.CONDITIONAL, spans))
            assertFailsWith<IllegalStateException> { first.assess(snapshotCid, revision(snapshot), assessments) }

            // Pause after enqueue: mutating caller-owned objects must not alter the draft.
            f.currentEntered = CompletableDeferred()
            f.currentGate = CompletableDeferred()
            val pending = async(f.principal) { first.assess(snapshotCid, revision(snapshot), assessments) }
            f.currentEntered!!.await()
            spans.clear()
            assessments.clear()
            f.currentGate!!.complete(Unit)
            val draft = pending.await()
            f.currentGate = null
            f.currentEntered = null
            assertEquals("draft", draft["state"])
            assertEquals("complete", draft["assessmentRecordedStatus"])
            assertEquals("available", draft["assessmentAvailability"])
            assertEquals(f.principal.id, draft["reviewer"])
            val draftCid = cid(draft, "assessmentCid")
            val retained = ConditionAssessmentCodec.decode(requireNotNull(f.cas.get(draftCid)))
            assertEquals(snapshotCid, retained.snapshotCid)
            assertEquals(quote, retained.assessments.single().spans.single().quote)
            assertFailsWith<IllegalArgumentException> { first.release(snapshotCid, cid(draft, "decisionCid"), revision(draft)) }
            first.drain()

            val resumed = f.service(); resumed.open(this)
            try {
                val replayed = resumed.inspect(snapshotCid)
                assertEquals(draft["assessmentCid"], replayed["assessmentCid"])
                assertEquals("complete", replayed["assessmentRecordedStatus"])
                assertEquals("available", replayed["assessmentAvailability"])
                assertEquals(f.principal.id, replayed["reviewer"])
                // Existing approval remains a separate operation; a draft never grants it.
                assertFailsWith<IllegalArgumentException> { resumed.release(snapshotCid, cid(replayed, "decisionCid"), revision(replayed)) }
            } finally { resumed.drain() }
        }
    }

    @Test fun conditionDraftRejectsForeignAndStaleClaimsAndPreservesUnresolved() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(); service.open(this)
            try {
                val snapshot = f.snapshot(service)
                val id = cid(snapshot, "snapshotCid")
                val initialRevision = revision(snapshot)
                val sourceSpan = ConditionSpan(f.textCid, 0, f.source.length, f.source)
                val good = ConditionAssessment(id, "p:0", ConditionAssessmentState.CONDITIONAL, _l[sourceSpan])
                suspend fun submit(values: List<ConditionAssessment>) = withContext(f.principal) { service.assess(id, initialRevision, values) }
                assertFailsWith<IllegalArgumentException> { submit(_l[good.copy(snapshotCid = f.textCid)]) }
                assertFailsWith<IllegalArgumentException> { submit(_l[good.copy(selectionId = "foreign")]) }
                assertFailsWith<IllegalArgumentException> { submit(_l[good, good]) }
                assertFailsWith<IllegalArgumentException> { submit(_l[good.copy(spans = _l[sourceSpan.copy(quote = "changed")])]) }
                assertEquals(initialRevision, revision(service.inspect(id)))
                val draft = submit(emptyList())
                assertEquals("unresolved", draft["assessmentRecordedStatus"])
                val event = (draft["history"] as List<*>).last() as Map<*, *>
                assertEquals(_l["p:0"], event["unresolved"])
                assertFailsWith<IllegalArgumentException> { submit(_l[good]) }
                assertFailsWith<IllegalArgumentException> { service.release(id, cid(draft, "decisionCid"), revision(draft)) }
            } finally { service.drain() }
        }
    }

    @Test fun conditionDraftFlushFailurePoisonsFurtherReviewWork() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(); service.open(this)
            try {
                val snapshot = f.snapshot(service)
                f.log.failFlush = true
                assertFailsWith<IllegalStateException> {
                    withContext(f.principal) { service.assess(cid(snapshot, "snapshotCid"), revision(snapshot), emptyList()) }
                }
                f.log.failFlush = false
                assertFailsWith<IllegalStateException> { service.inspect(cid(snapshot, "snapshotCid")) }
            } finally { service.drain() }
        }
    }

    @Test fun conditionDraftCannotInvalidateAnApprovalReleaseOrConsumption() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(); service.open(this)
            try {
                val snapshot = f.snapshot(service)
                val snapshotCid = cid(snapshot, "snapshotCid")
                val approved = f.approve(service, snapshot)
                suspend fun attempt(view: Map<String, Any?>) = withContext(f.principal) {
                    service.assess(snapshotCid, revision(view), emptyList())
                }
                assertFailsWith<IllegalArgumentException> { attempt(approved) }
                assertEquals(approved["decisionCid"], service.inspect(snapshotCid)["decisionCid"])
                val release = service.release(snapshotCid, cid(approved, "decisionCid"), revision(approved))
                val released = service.inspect(snapshotCid)
                assertFailsWith<IllegalArgumentException> { attempt(released) }
                assertEquals("release", service.inspect(snapshotCid)["state"])
                assertNotNull(service.consume(cid(release, "releaseCid"))["admissionCid"])
                val consumed = service.inspect(snapshotCid)
                assertEquals("consume", consumed["state"])
                assertFailsWith<IllegalArgumentException> { attempt(consumed) }
                assertEquals(consumed["decisionCid"], service.inspect(snapshotCid)["decisionCid"])
            } finally { service.drain() }
        }
    }

    @Test fun recordedConditionStatusIsSeparateFromPresentCasAvailabilityAcrossReplay() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(); service.open(this)
            val snapshot = f.snapshot(service)
            val id = cid(snapshot, "snapshotCid")
            val assessment = ConditionAssessment(id, "p:0", ConditionAssessmentState.UNCONDITIONAL)
            val draft = withContext(f.principal) { service.assess(id, revision(snapshot), _l[assessment]) }
            val assessmentCid = cid(draft, "assessmentCid")
            val retained = requireNotNull(f.cas.blobs.remove(assessmentCid))
            val missing = service.inspect(id)
            assertEquals("complete", missing["assessmentRecordedStatus"])
            assertEquals("missing", missing["assessmentAvailability"])
            assertEquals(false, missing["current"])
            assertEquals(true, missing["sourceCurrent"])
            service.drain()

            val replay = f.service(); replay.open(this)
            try {
                val stillMissing = replay.inspect(id)
                assertEquals("complete", stillMissing["assessmentRecordedStatus"])
                assertEquals("missing", stillMissing["assessmentAvailability"])
                assertEquals(false, stillMissing["current"])
                f.cas.blobs[assessmentCid] = "altered".encodeToByteArray()
                val corrupt = replay.inspect(id)
                assertEquals("unavailable", corrupt["assessmentAvailability"])
                assertEquals(false, corrupt["current"])
                f.cas.blobs[assessmentCid] = retained
                val restored = replay.inspect(id)
                assertEquals("available", restored["assessmentAvailability"])
                assertEquals(true, restored["current"])
            } finally { replay.drain() }
        }
    }

    @Test fun oldSnapshotDoesNotDisplayAnotherSnapshotsAssessment() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(); service.open(this)
            try {
                val old = f.snapshot(service)
                val oldCid = cid(old, "snapshotCid")
                val oldDraft = withContext(f.principal) { service.assess(oldCid, revision(old), emptyList()) }
                assertEquals("unresolved", oldDraft["assessmentRecordedStatus"])
                f.candidate = f.candidate.copy(curationReceiptCid = f.cas.put("new curation".encodeToByteArray()), sourceRevision = "source:2")
                val next = f.snapshot(service)
                val newCid = cid(next, "snapshotCid")
                val draft = withContext(f.principal) {
                    service.assess(newCid, revision(next), _l[ConditionAssessment(newCid, "p:0", ConditionAssessmentState.UNCONDITIONAL)])
                }
                assertEquals("complete", draft["assessmentRecordedStatus"])
                val historical = service.inspect(oldCid)
                assertEquals(false, historical["current"])
                assertNull(historical["assessmentCid"])
                assertNull(historical["assessmentRecordedStatus"])
                assertNull(historical["assessmentAvailability"])
                assertNotNull(service.inspect(newCid)["assessmentCid"])
            } finally { service.drain() }
        }
    }

    @Test fun reviewerIdentityMustRoundTripUtf8AndSurvivesDraftReplay() = runBlocking {
        withTimeout(10_000) {
            assertFailsWith<IllegalArgumentException> { ReviewPrincipal("reviewer \uD800") }
            assertFailsWith<IllegalArgumentException> { ReviewPrincipal("reviewer \uDC00") }
            val reviewer = ReviewPrincipal("reviewer 😀")
            val f = Fixture(); val service = f.service(); service.open(this)
            val snapshot = f.snapshot(service)
            val id = cid(snapshot, "snapshotCid")
            val draft = withContext(reviewer) { service.assess(id, revision(snapshot), emptyList()) }
            assertEquals(reviewer.id, draft["reviewer"])
            service.drain()
            val replay = f.service(); replay.open(this)
            try { assertEquals(reviewer.id, replay.inspect(id)["reviewer"]) }
            finally { replay.drain() }
        }
    }

    @Test fun strictBookRequiresExactAssessmentBeforeApprovalAndPropagatesItOnAdmission() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(ConditionReviewPolicy.STRICT_BOOK); service.open(this)
            try {
                val snapshot = f.snapshot(service)
                val id = cid(snapshot, "snapshotCid")
                assertFailsWith<IllegalArgumentException> {
                    withContext(f.principal) { service.decide(id, revision(snapshot), "approve") }
                }
                val unresolved = withContext(f.principal) { service.assess(id, revision(snapshot), emptyList()) }
                val unresolvedCid = cid(unresolved, "assessmentCid")
                assertFailsWith<IllegalArgumentException> {
                    withContext(f.principal) { service.decide(id, revision(unresolved), "approve", expectedAssessmentCid = unresolvedCid) }
                }
                val assessment = ConditionAssessment(id, "p:0", ConditionAssessmentState.UNCONDITIONAL)
                val draft = withContext(f.principal) { service.assess(id, revision(unresolved), _l[assessment]) }
                val assessmentCid = cid(draft, "assessmentCid")
                assertFailsWith<IllegalStateException> { service.assessment(id, revision(draft)) }
                val viewed = withContext(f.principal) { service.assessment(id, revision(draft)) }
                assertEquals(assessmentCid.value, viewed["assessmentCid"])
                assertFailsWith<IllegalArgumentException> {
                    withContext(f.principal) { service.decide(id, revision(draft), "approve") }
                }
                assertFailsWith<IllegalArgumentException> {
                    withContext(f.principal) { service.decide(id, revision(draft), "approve", expectedAssessmentCid = unresolvedCid) }
                }
                val approved = withContext(f.principal) {
                    service.decide(id, revision(draft), "approve", expectedAssessmentCid = assessmentCid)
                }
                val release = service.release(id, cid(approved, "decisionCid"), revision(approved))
                val admitted = service.consume(cid(release, "releaseCid"))
                val selected = (admitted["relationships"] as List<*>).single() as Map<*, *>
                val review = selected["conditionReview"] as Map<*, *>
                assertEquals("unconditional", review["state"])
                assertEquals(assessmentCid.value, review["assessmentCid"])
                assertEquals(draft["decisionCid"], review["draftCid"])
                val projected = DocumentCurationFacts.reviewed(admitted)
                assertEquals(review, projected[0]["conditionReview"])
                assertFailsWith<IllegalArgumentException> { service.consume(cid(release, "releaseCid")) }
            } finally { service.drain() }
        }
    }

    @Test fun strictConditionalReviewCannotBeReleasedAsAnUnconditionalRelationship() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(ConditionReviewPolicy.STRICT_BOOK); service.open(this)
            try {
                val snapshot = f.snapshot(service); val id = cid(snapshot, "snapshotCid")
                val span = ConditionSpan(f.textCid, 0, f.source.length, f.source)
                val assessment = ConditionAssessment(id, "p:0", ConditionAssessmentState.CONDITIONAL, _l[span])
                val draft = withContext(f.principal) { service.assess(id, revision(snapshot), _l[assessment]) }
                val approved = withContext(f.principal) {
                    service.decide(id, revision(draft), "approve", expectedAssessmentCid = cid(draft, "assessmentCid"))
                }
                val reviewed = withContext(f.principal) { service.assessment(id, revision(approved)) }
                assertEquals(draft["decisionCid"], reviewed["draftCid"])
                assertFailsWith<IllegalArgumentException> {
                    service.release(id, cid(approved, "decisionCid"), revision(approved))
                }
                val stillApproved = service.inspect(id)
                assertEquals("approve", stillApproved["state"])
                assertEquals("conditional-nonexecutable", stillApproved["conditionReviewState"])
                assertEquals(approved["decisionCid"], stillApproved["decisionCid"])
            } finally { service.drain() }
        }
    }

    @Test fun strictMixedSelectionCannotReleaseItsConditionalRelationship() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture()
            val first = f.candidate.selections[0]
            f.candidate = f.candidate.copy(selections = s_[first, first.copy(id = "p:1")])
            val service = f.service(ConditionReviewPolicy.STRICT_BOOK); service.open(this)
            try {
                val snapshot = service.snapshot("book", f.receipt, _l["p:0", "p:1"]); val id = cid(snapshot, "snapshotCid")
                val span = ConditionSpan(f.textCid, 0, f.source.length, f.source)
                val rows = _l[ConditionAssessment(id, "p:0", ConditionAssessmentState.UNCONDITIONAL),
                    ConditionAssessment(id, "p:1", ConditionAssessmentState.CONDITIONAL, _l[span])]
                val draft = withContext(f.principal) { service.assess(id, revision(snapshot), rows) }
                val approved = withContext(f.principal) {
                    service.decide(id, revision(draft), "approve", expectedAssessmentCid = cid(draft, "assessmentCid"))
                }
                assertFailsWith<IllegalArgumentException> { service.release(id, cid(approved, "decisionCid"), revision(approved)) }
                assertEquals("approve", service.inspect(id)["state"])
            } finally { service.drain() }
        }
    }

    @Test fun strictAdmissionRechecksAssessmentAndDoesNotConsumeOnMissingOrStaleEvidence() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val service = f.service(ConditionReviewPolicy.STRICT_BOOK); service.open(this)
            try {
                val snapshot = f.snapshot(service); val id = cid(snapshot, "snapshotCid")
                val assessment = ConditionAssessment(id, "p:0", ConditionAssessmentState.UNCONDITIONAL)
                val draft = withContext(f.principal) { service.assess(id, revision(snapshot), _l[assessment]) }
                val assessmentCid = cid(draft, "assessmentCid")
                val approved = withContext(f.principal) {
                    service.decide(id, revision(draft), "approve", expectedAssessmentCid = assessmentCid)
                }
                val retained = requireNotNull(f.cas.blobs.remove(assessmentCid))
                assertFails { service.release(id, cid(approved, "decisionCid"), revision(approved)) }
                assertEquals("approve", service.inspect(id)["state"])
                f.cas.blobs[assessmentCid] = retained
                val release = service.release(id, cid(approved, "decisionCid"), revision(approved))
                f.current = false
                assertFailsWith<IllegalArgumentException> { service.consume(cid(release, "releaseCid")) }
                assertEquals("release", service.inspect(id)["state"])
                f.current = true
                f.cas.blobs[assessmentCid] = "corrupt".encodeToByteArray()
                assertFails { service.consume(cid(release, "releaseCid")) }
                assertEquals("release", service.inspect(id)["state"])
                f.cas.blobs[assessmentCid] = retained
                assertNotNull(service.consume(cid(release, "releaseCid"))["admissionCid"])
            } finally { service.drain() }
        }
    }

    @Test fun strictPolicyKeepsLegacyApprovalsHistoricalUntilExplicitNewReview() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val legacy = f.service(ConditionReviewPolicy.LEGACY); legacy.open(this)
            val snapshot = f.snapshot(legacy); val id = cid(snapshot, "snapshotCid")
            val oldApproval = f.approve(legacy, snapshot)
            val oldRelease = legacy.release(id, cid(oldApproval, "decisionCid"), revision(oldApproval))
            legacy.drain()
            val strict = f.service(ConditionReviewPolicy.STRICT_BOOK); strict.open(this)
            try {
                val historical = strict.inspect(id)
                assertEquals("release", historical["state"])
                assertEquals(false, historical["current"])
                assertEquals(true, historical["sourceCurrent"])
                assertEquals("legacy-unassessed", historical["conditionReviewState"])
                assertFails { strict.consume(cid(oldRelease, "releaseCid")) }
                assertFailsWith<IllegalArgumentException> {
                    withContext(f.principal) { strict.assess(id, revision(historical), emptyList()) }
                }
                // Re-review explicitly withdraws the old pending permit.
                val withdrawn = withContext(f.principal) { strict.decide(id, revision(historical), "draft", "Re-review") }
                val draft = withContext(f.principal) {
                    strict.assess(id, revision(withdrawn), _l[ConditionAssessment(id, "p:0", ConditionAssessmentState.UNCONDITIONAL)])
                }
                val approved = withContext(f.principal) {
                    strict.decide(id, revision(draft), "approve", expectedAssessmentCid = cid(draft, "assessmentCid"))
                }
                val release = strict.release(id, cid(approved, "decisionCid"), revision(approved))
                assertNotNull(strict.consume(cid(release, "releaseCid"))["admissionCid"])
            } finally { strict.drain() }
        }
    }

    @Test fun strictPolicyDoesNotReleaseAnUnassessedLegacyApproval() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val legacy = f.service(ConditionReviewPolicy.LEGACY); legacy.open(this)
            val snapshot = f.snapshot(legacy); val id = cid(snapshot, "snapshotCid")
            val oldApproval = f.approve(legacy, snapshot)
            legacy.drain()
            val strict = f.service(ConditionReviewPolicy.STRICT_BOOK); strict.open(this)
            try {
                val historical = strict.inspect(id)
                assertEquals("approve", historical["state"])
                assertEquals(false, historical["current"])
                assertEquals("legacy-unassessed", historical["conditionReviewState"])
                assertFails { strict.release(id, cid(oldApproval, "decisionCid"), revision(oldApproval)) }
                assertEquals(oldApproval["decisionCid"], strict.inspect(id)["decisionCid"])
            } finally { strict.drain() }
        }
    }

    @Test fun strictAssessmentProofSurvivesApprovalAndReleaseReplay() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); val first = f.service(ConditionReviewPolicy.STRICT_BOOK); first.open(this)
            val snapshot = f.snapshot(first); val id = cid(snapshot, "snapshotCid")
            val draft = withContext(f.principal) {
                first.assess(id, revision(snapshot), _l[ConditionAssessment(id, "p:0", ConditionAssessmentState.UNCONDITIONAL)])
            }
            val approved = withContext(f.principal) {
                first.decide(id, revision(draft), "approve", expectedAssessmentCid = cid(draft, "assessmentCid"))
            }
            first.drain()
            val second = f.service(ConditionReviewPolicy.STRICT_BOOK); second.open(this)
            val release = second.release(id, cid(approved, "decisionCid"), revision(approved))
            second.drain()
            val third = f.service(ConditionReviewPolicy.STRICT_BOOK); third.open(this)
            try {
                val admitted = third.consume(cid(release, "releaseCid"))
                val review = ((admitted["relationships"] as List<*>).single() as Map<*, *>)["conditionReview"] as Map<*, *>
                assertEquals(draft["assessmentCid"], review["assessmentCid"])
                assertEquals(draft["decisionCid"], review["draftCid"])
            } finally { third.drain() }
        }
    }

    @Test fun strictUnconditionalAssessmentCannotDiscardAParsedCondition() = runBlocking {
        withTimeout(10_000) {
            val f = Fixture(); f.terms["condition"] = "if specified"
            val service = f.service(ConditionReviewPolicy.STRICT_BOOK); service.open(this)
            try {
                val snapshot = f.snapshot(service); val id = cid(snapshot, "snapshotCid")
                val draft = withContext(f.principal) {
                    service.assess(id, revision(snapshot), _l[ConditionAssessment(id, "p:0", ConditionAssessmentState.UNCONDITIONAL)])
                }
                assertFailsWith<IllegalArgumentException> {
                    withContext(f.principal) { service.decide(id, revision(draft), "approve", expectedAssessmentCid = cid(draft, "assessmentCid")) }
                }
                assertEquals("draft", service.inspect(id)["state"])
            } finally { service.drain() }
        }
    }

    companion object {
        private fun cid(view: Map<String, Any?>, key: String) = ContentId(view[key] as String)
        private fun revision(view: Map<String, Any?>) = (view["revision"] as Number).toLong()
    }
}

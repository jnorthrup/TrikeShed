package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.reifyMap
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class EnglishTaxonomyExportGuardTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    class Store : CasStore(), BoundedCasReader {
        val blobs = mutableMapOf<ContentId, ByteArray>()
        var boundedReads = 0
        override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also { blobs[it] = bytes.copyOf() }
        override fun get(cid: ContentId): ByteArray? = blobs[cid]?.copyOf()
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
            boundedReads++
            return get(cid)?.also { require(it.size <= maxBytes) }
        }
    }

    /** Synthetic host-owned markers test export policy, not English translation provenance. */
    class Fixture(val directory: Path) {
        val cas = Store()
        val candidates = mutableMapOf<ContentId, CurationReviewCandidate>()
        val actor = ReviewPrincipal("synthetic:export-policy-test", synthetic = true)
        val wal = directory.resolve("review.wal")
        var current = true

        fun candidate(name: String, text: String, marker: String? = null): CurationReviewCandidate {
            val textCid = cas.put(text.encodeToByteArray())
            val source = DocumentSource(textCid, textCid, text, "$name.kif", "text/plain", "synthetic-policy-test",
                metadata = _m["evidenceId" j _l[name]])
            val record = DocumentCurationRecord(source, null, null, "synthetic-policy-test", emptySeriesOf(), emptySeriesOf())
            val receipt = cas.put(DocumentCuratorCodec.encode(record))
            val ids = DocumentTaxonomyCandidate.selections(source).map { it.id }
            val manual = DocumentTaxonomyCandidate.candidate(cas, receipt, ids)
            val candidate = if (marker == null) manual else manual.copy(selections = manual.selections α { selection ->
                if (selection.id != ids.last()) selection else selection.copy(
                    value = selection.value + _m[marker j "synthetic-policy-test"])
            })
            candidates[receipt] = candidate
            return candidate
        }

        suspend fun <T> ledger(scope: CoroutineScope, action: suspend (CurationReview) -> T): T {
            val log = DocumentAppendLog.open(wal.toString(), directory.toString())
            val review = CurationReview(cas, log, resolve = { kind, receipt, ids ->
                candidates.getValue(receipt).also {
                    require(it.kind == kind && it.selections.view.map { selection -> selection.id } == ids)
                }
            }, current = { candidate ->
                val retained = candidates[candidate.curationReceiptCid]
                current && retained != null && candidate.kind == retained.kind && candidate.family == retained.family &&
                    candidate.ontologyCid == retained.ontologyCid && candidate.sourceRevision == retained.sourceRevision &&
                    candidate.selections.size == retained.selections.size &&
                    candidate.selections.view.all { it in retained.selections.view }
            }, clock = { 123L })
            review.open(scope)
            try { return action(review) } finally {
                try { review.drain() } finally { log.close() }
            }
        }

        suspend fun snapshot(review: CurationReview, candidate: CurationReviewCandidate): CurationTaxonomyRef {
            val snapshot = review.snapshot(candidate.kind, candidate.curationReceiptCid, candidate.selections.view.map { it.id })
            return CurationTaxonomyRef(ContentId(snapshot.getValue("snapshotCid") as String),
                (snapshot.getValue("revision") as Number).toLong())
        }

        suspend fun approve(review: CurationReview, candidate: CurationReviewCandidate): CurationTaxonomyRef {
            val snapshot = snapshot(review, candidate)
            val approved = withContext(actor) { review.decide(snapshot.snapshotCid, snapshot.revision, "approve") }
            return snapshot.copy(revision = (approved.getValue("revision") as Number).toLong())
        }
    }

    @Test fun persistedCarrierReadDoesNotSupplyMissingTranslationProvenance(): Unit = runBlocking {
        val f = Fixture(directory)
        val candidate = f.candidate("english", "(subclass A B)\n(subclass B C)", "englishTaxonomyCarrier")
        val approved = f.ledger(this) { review ->
            val snapshot = f.snapshot(review, candidate)
            assertFailsWith<IllegalStateException> { review.taxonomyRead(snapshot.snapshotCid, snapshot.revision) }
            withContext(f.actor) {
                val denied = assertFailsWith<IllegalArgumentException> {
                    review.taxonomyRead(snapshot.snapshotCid, snapshot.revision)
                }
                assertContains(denied.message.orEmpty(), "current taxonomy approval")
                assertFailsWith<IllegalArgumentException> {
                    ReviewedTaxonomyPlan.report(review, f.cas, snapshot.snapshotCid, snapshot.revision, "subclass", "A", "C")
                }
                val decision = review.decide(snapshot.snapshotCid, snapshot.revision, "approve")
                snapshot.copy(revision = (decision.getValue("revision") as Number).toLong())
            }
        }
        // Reopen the production WAL before using its approval as a read capability.
        f.ledger(this) { review ->
            val before = Files.size(f.wal)
            withContext(f.actor) {
                val read = review.taxonomyRead(approved.snapshotCid, approved.revision)
                assertEquals(2, read.candidate.selections.size)
                assertEquals(approved.snapshotCid, read.snapshotCid)
                assertEquals(f.actor.id, read.reviewer)
                assertTrue(read.synthetic)
                // A host-owned marker exercises admission policy but cannot stand in for D/I/K/R evidence.
                assertFailsWith<IllegalArgumentException> {
                    ReviewedTaxonomyPlan.report(review, f.cas, approved.snapshotCid, approved.revision,
                        "subclass", "A", "C")
                }
                f.current = false
                val stale = assertFailsWith<IllegalArgumentException> {
                    review.taxonomyRead(approved.snapshotCid, approved.revision)
                }
                assertContains(stale.message.orEmpty(), "no longer current")
            }
            assertEquals(before, Files.size(f.wal), "Reads and refusals cannot append review authority")
        }
    }

    @Test fun eitherCarrierMarkerRefusesSingleReferenceNativeAndUnionExports(): Unit = runBlocking {
        val f = Fixture(directory)
        val profile = NativeHornProfile(directory.resolve("unavailable-mlnix"), "0".repeat(64),
            directory.resolve("unavailable-adapter"), directory.resolve("unavailable-datalog"))
        val native = NativeHornQuery(profile)
        f.ledger(this) { review ->
            for (marker in s_["englishTaxonomyCarrier", "translationDecisionCid"].view) {
                val candidate = f.candidate(marker, "(subclass A B)\n(subclass B C)", marker)
                val ref = f.approve(review, candidate)
                val before = Files.size(f.wal)
                f.cas.boundedReads = 0
                withContext(f.actor) {
                    val horn = assertFailsWith<IllegalArgumentException> {
                        ReviewedKifHornPlan.report(review, f.cas, ref.snapshotCid, ref.revision, "subclass", "A", "C")
                    }
                    assertContains(horn.message.orEmpty(), "English taxonomy carriers")
                    for (search in s_[false, true].view) {
                        val union = assertFailsWith<IllegalArgumentException> {
                            ReviewedKifUnion.report(review, f.cas, _l[ref], "subclass", "A", "C", search)
                        }
                        assertContains(union.message.orEmpty(), "English taxonomy carriers")
                    }
                    val direct = assertFailsWith<IllegalArgumentException> {
                        native.taxonomy(review, f.cas, ref.snapshotCid, ref.revision, "subclass", "A", "C")
                    }
                    assertContains(direct.message.orEmpty(), "English taxonomy carriers")
                    val union = assertFailsWith<IllegalArgumentException> {
                        native.taxonomyUnion(review, f.cas, _l[ref], "subclass", "A", "C")
                    }
                    assertContains(union.message.orEmpty(), "English taxonomy carriers")
                    val chain = assertFailsWith<IllegalArgumentException> {
                        native.taxonomyChain(review, f.cas, _l[ref], "subclass", "A", "C")
                    }
                    assertContains(chain.message.orEmpty(), "English taxonomy carriers")
                }
                assertEquals(0, f.cas.boundedReads, "Refuse before export preparation or native invocation")
                assertEquals(before, Files.size(f.wal))
            }
        }
    }

    @Test fun anyCarrierRefusesMultipleReviewedHeadsInEitherOrder(): Unit = runBlocking {
        val f = Fixture(directory)
        val carrier = f.candidate("english", "(subclass A B)\n(subclass B C)", "englishTaxonomyCarrier")
        val translation = f.candidate("translation", "(subclass D E)", "translationDecisionCid")
        val manual = f.candidate("manual", "(subclass F G)")
        f.ledger(this) { review ->
            val englishRef = f.approve(review, carrier)
            val translationRef = f.approve(review, translation)
            val manualRef = f.approve(review, manual)
            val before = Files.size(f.wal)
            withContext(f.actor) {
                for (references in s_[_l[englishRef, manualRef], _l[manualRef, englishRef],
                    _l[translationRef, manualRef], _l[englishRef, translationRef]].view) {
                    val denied = assertFailsWith<IllegalArgumentException> { review.taxonomyReads(references) }
                    assertContains(denied.message.orEmpty(), "single reviewed snapshot")
                }
                assertEquals(englishRef.snapshotCid, review.taxonomyReads(_l[englishRef]).single().snapshotCid)
            }
            assertEquals(before, Files.size(f.wal))
        }
    }

    @Test fun manualKifRetainsSingleAndMultipleSnapshotExports(): Unit = runBlocking {
        val f = Fixture(directory)
        val first = f.candidate("first", "(subclass A B)")
        val second = f.candidate("second", "(subclass B C)")
        f.ledger(this) { review ->
            val a = f.approve(review, first)
            val b = f.approve(review, second)
            val before = Files.size(f.wal)
            withContext(f.actor) {
                assertEquals(2, review.taxonomyReads(_l[a, b]).size)
                val horn = ReviewedKifHornPlan.report(review, f.cas, a.snapshotCid, a.revision, "subclass", "A", "B")
                assertEquals(1, horn["selectionCount"])
                val single = ReviewedKifUnion.report(review, f.cas, _l[a], "subclass", "A", "B")
                assertEquals(1, single["selectionCount"])
                val union = ReviewedKifUnion.report(review, f.cas, _l[a, b], "subclass", "A", "C")
                assertEquals(2, union["selectionCount"])
                assertEquals(2, (union["sourceEvidence"] as Map<*, *>).size)
                assertEquals(2, (reifyMap(union["inputJson"] as String)["facts"] as List<*>).size)
            }
            assertEquals(before, Files.size(f.wal))
        }
    }
}

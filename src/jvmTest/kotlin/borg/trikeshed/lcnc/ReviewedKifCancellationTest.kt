package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlin.test.*

class ReviewedKifCancellationTest {
    private class Store : CasStore(), BoundedCasReader {
        val blobs = mutableMapOf<ContentId, ByteArray>()
        var afterRead: ((ContentId) -> Unit)? = null
        override fun put(bytes: ByteArray): ContentId = ContentId.of(bytes).also { blobs[it] = bytes.copyOf() }
        override fun get(cid: ContentId): ByteArray? = blobs[cid]?.copyOf()
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = get(cid)?.also {
            require(it.size <= maxBytes)
            afterRead?.invoke(cid)
        }
    }

    private class Log : DurableAppendLog {
        private val frames = mutableListOf<Join<Long, ByteArray>>()
        override fun append(sequence: Long, payload: ByteArray): Long { frames.add(sequence j payload.copyOf()); return sequence }
        override fun flush() = Unit
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
            for ((sequence, payload) in frames) onFrame(sequence, payload.copyOf())
            return frames.lastOrNull()?.a ?: 0
        }
        override fun injectCorruptionAfter(sequence: Long) = error("Not used")
    }

    @Test fun canceledReadStopsBeforeFetchingTheNextReviewedSource(): Unit = runBlocking {
        val cas = Store()
        val texts = _l["(subclass A B)", "(subclass B C)"]
        val cids = texts.map { cas.put(it.encodeToByteArray()) }
        val receipt = cas.put("receipt".encodeToByteArray())
        val ontology = cas.put("policy".encodeToByteArray())
        val selections = texts.mapIndexed { index, text ->
            CurationReviewSelection("selection-$index", "relationship", _m["format" j DocumentTaxonomyCandidate.POLICY],
                CurationReviewQuotation(cids[index], cids[index], 0, text.length, text))
        }
        val candidate = CurationReviewCandidate(DocumentTaxonomyCandidate.KIND, "taxonomy/fixture", receipt,
            ontology, "revision", selections.toSeries())
        val review = CurationReview(cas, Log(), resolve = { _, _, _ -> candidate }, current = { true }, clock = { 123L })
        review.open(this)
        try {
            val snapshot = review.snapshot(candidate.kind, receipt, selections.map { it.id })
            val snapshotCid = ContentId(snapshot["snapshotCid"] as String)
            val principal = ReviewPrincipal("synthetic:analyst", synthetic = true)
            val approved = withContext(principal) {
                review.decide(snapshotCid, (snapshot["revision"] as Number).toLong(), "approve")
            }
            val ref = CurationTaxonomyRef(snapshotCid, (approved["revision"] as Number).toLong())
            var reads = 0
            val query = async(start = CoroutineStart.LAZY) {
                withContext(principal) { ReviewedKifUnion.report(review, cas, _l[ref], "subclass", "A", "C", search = true) }
            }
            cas.afterRead = { if (it in cids) { reads++; if (reads == 1) query.cancel() } }
            query.start()
            assertFailsWith<CancellationException> { query.await() }
            assertEquals(1, reads, "Canceled work must not read another reviewed source")
        } finally { review.drain() }
    }
}

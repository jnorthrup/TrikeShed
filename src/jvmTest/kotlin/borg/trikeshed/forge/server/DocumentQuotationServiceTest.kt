package borg.trikeshed.forge.server

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.ReviewPrincipal
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.userspace.nio.file.spi.BoundedReadLimitException
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DocumentQuotationServiceTest {
    private data class Fixture(val claim: DocumentQuotationClaim, val entries: Map<ContentId, ByteArray>)

    private fun fixture(): Fixture {
        val text = "A😀B"
        val textBytes = text.encodeToByteArray()
        val source = DocumentSource(ContentId.of("original".encodeToByteArray()), ContentId.of(textBytes),
            text, "fixture", "text/plain", "test")
        val seed = DocumentProposal("{}", "A", "states", "B", quote = "😀", polarity = true,
            modality = "asserted", quotationBegin = 1, quotationEnd = 3)
        val quotation = DocumentCuratorGrounding.quotationReceipt(source, seed)
        val qcid = ContentId.of(quotation)
        val proposal = seed.copy(quotationReceiptCid = qcid)
        val record = DocumentCurationRecord(source, null, null, "fixture", s_[proposal], emptySeriesOf())
        val recordBytes = DocumentCuratorCodec.encode(record)
        val rcid = ContentId.of(recordBytes)
        return Fixture(DocumentQuotationClaim(rcid, source.originalCid, source.extractedTextCid, qcid,
            null, "A", "states", "B", true, "asserted", "😀", 1, 3),
            _m[rcid j recordBytes, source.extractedTextCid j textBytes, qcid j quotation])
    }

    @Test fun checksAuthorizedSpanWithoutReadingOriginalDocument() = runBlocking {
        val f = fixture()
        val calls = mutableListOf<ContentId>()
        val reader = object : BoundedCasReader {
            override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
                calls.add(cid)
                return f.entries[cid]?.copyOf()?.also { if (it.size > maxBytes) throw BoundedReadLimitException(maxBytes) }
            }
        }
        val policy = DocumentQuotationPolicy { principal, iri, claim ->
            principal.id == "analyst" && iri == "urn:fact:1" && claim == f.claim
        }
        val service = DocumentQuotationService(reader, policy)
        val result = service.verify(ReviewPrincipal("analyst"), "urn:fact:1", f.claim)
        assertEquals(DocumentQuotationServiceOutcome.CHECKED, result.outcome)
        assertEquals(DocumentQuotationStatus.EXACT_EXTRACTED_SPAN, result.consistency)
        assertEquals(_l[f.claim.curationCid, f.claim.extractedTextCid, f.claim.quotationReceiptCid], calls)
        assertFalse(f.claim.originalCid in calls)
    }

    @Test fun unauthorizedOrPolicyFailurePerformsNoReads() = runBlocking {
        val f = fixture()
        var reads = 0
        val reader = object : BoundedCasReader {
            override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? { reads++; return null }
        }
        val denied = DocumentQuotationService(reader, DocumentQuotationPolicy { _, _, _ -> false })
        assertEquals(DocumentQuotationServiceOutcome.DENIED,
            denied.verify(ReviewPrincipal("analyst"), "urn:fact:1", f.claim).outcome)
        val broken = DocumentQuotationService(reader, DocumentQuotationPolicy { _, _, _ -> error("policy unavailable") })
        assertEquals(DocumentQuotationServiceOutcome.UNAVAILABLE,
            broken.verify(ReviewPrincipal("analyst"), "urn:fact:1", f.claim).outcome)
        assertEquals(0, reads)
    }

    @Test fun boundedReadFailuresAndDigestMismatchFailClosed() = runBlocking {
        val f = fixture()
        val allow = DocumentQuotationPolicy { _, _, _ -> true }
        fun service(read: (ContentId, Int) -> ByteArray?) = DocumentQuotationService(object : BoundedCasReader {
            override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = read(cid, maxBytes)
        }, allow)
        val principal = ReviewPrincipal("analyst")
        assertEquals(DocumentQuotationServiceOutcome.UNAVAILABLE,
            service { _, _ -> null }.verify(principal, "urn:fact:1", f.claim).outcome)
        assertEquals(DocumentQuotationServiceOutcome.LIMIT,
            service { _, max -> throw BoundedReadLimitException(max) }.verify(principal, "urn:fact:1", f.claim).outcome)
        assertEquals(DocumentQuotationServiceOutcome.UNAVAILABLE,
            service { _, _ -> throw UnsupportedOperationException() }.verify(principal, "urn:fact:1", f.claim).outcome)
        assertEquals(DocumentQuotationServiceOutcome.UNAVAILABLE,
            service { _, _ -> "bad bytes".encodeToByteArray() }.verify(principal, "urn:fact:1", f.claim).outcome)
        // A broken reader must not return an oversized success; the wrapper refuses it.
        assertEquals(DocumentQuotationServiceOutcome.LIMIT,
            service { cid, _ -> if (cid == f.claim.quotationReceiptCid) ByteArray(65_537) else f.entries[cid] }
                .verify(principal, "urn:fact:1", f.claim).outcome)
    }

    @Test fun capsConcurrentVerificationAndPreservesCancellation() = runBlocking {
        val f = fixture()
        val release = CompletableDeferred<Unit>()
        val reader = object : BoundedCasReader {
            override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = f.entries[cid]?.copyOf()
        }
        val service = DocumentQuotationService(reader, DocumentQuotationPolicy { _, _, _ -> release.await(); true })
        val principal = ReviewPrincipal("analyst")
        val one = async(start = CoroutineStart.UNDISPATCHED) { service.verify(principal, "urn:fact:1", f.claim) }
        val two = async(start = CoroutineStart.UNDISPATCHED) { service.verify(principal, "urn:fact:2", f.claim) }
        assertEquals(DocumentQuotationServiceOutcome.BUSY, service.verify(principal, "urn:fact:3", f.claim).outcome)
        one.cancelAndJoin()
        val three = async(start = CoroutineStart.UNDISPATCHED) { service.verify(principal, "urn:fact:3", f.claim) }
        release.complete(Unit)
        assertEquals(DocumentQuotationServiceOutcome.CHECKED, two.await().outcome)
        assertEquals(DocumentQuotationServiceOutcome.CHECKED, three.await().outcome)
    }

    @Test fun cancellationDoesNotReleaseSlotBeforeBlockingReadReturns() = runBlocking {
        val f = fixture()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val reader = object : BoundedCasReader {
            override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
                if (cid == f.claim.curationCid) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Timed out waiting for test release" }
                }
                return f.entries[cid]?.copyOf()
            }
        }
        val service = DocumentQuotationService(reader, DocumentQuotationPolicy { _, _, _ -> true })
        val principal = ReviewPrincipal("analyst")
        val one = async(Dispatchers.Default) { service.verify(principal, "urn:fact:1", f.claim) }
        val two = async(Dispatchers.Default) { service.verify(principal, "urn:fact:2", f.claim) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            one.cancel()
            // The cancelled call is still physically reading: its permit must remain occupied.
            assertEquals(DocumentQuotationServiceOutcome.BUSY, service.verify(principal, "urn:fact:3", f.claim).outcome)
        } finally { release.countDown() }
        assertFailsWith<CancellationException> { one.await() }
        assertEquals(DocumentQuotationServiceOutcome.CHECKED, two.await().outcome)
        assertEquals(DocumentQuotationServiceOutcome.CHECKED, service.verify(principal, "urn:fact:3", f.claim).outcome)
    }
}

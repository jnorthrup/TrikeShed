package borg.trikeshed.forge.server

import borg.trikeshed.job.BoundedCasReader
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.ReviewPrincipal
import borg.trikeshed.narsese.DocumentQuotationClaim
import borg.trikeshed.narsese.DocumentQuotationCheck
import borg.trikeshed.narsese.DocumentQuotationStatus
import borg.trikeshed.userspace.nio.file.spi.BoundedReadLimitException
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * Host-owned policy. The host must establish the principal from authentication, and validate
 * factIri and every CID/field in claim against the same server-captured live proposal and the
 * principal's document permissions. A caller-provided IRI or CID is not authorization.
 */
internal fun interface DocumentQuotationPolicy {
    suspend fun allows(principal: ReviewPrincipal, factIri: String, claim: DocumentQuotationClaim): Boolean
}

internal enum class DocumentQuotationServiceOutcome { CHECKED, DENIED, BUSY, UNAVAILABLE, LIMIT }
internal data class DocumentQuotationServiceResult(
    val outcome: DocumentQuotationServiceOutcome,
    val consistency: DocumentQuotationStatus? = null,
)

/**
 * Host-only, opt-in and read-only. There is intentionally no endpoint and no default policy.
 * The supplied BoundedCasReader must actually enforce bounded allocation on every backing
 * store; no fallback to CasStore.get is allowed. The host must share one service instance so
 * its per-instance concurrency bound applies across requests. Blocking filesystem calls can
 * outlive cancellation or timeouts; this bounds concurrency and bytes, not I/O time.
 *
 * EXACT_EXTRACTED_SPAN means only that supplied retained text has an exact receipt span.
 * Original availability/authenticity, OCR, legal meaning, query witnesses, source currentness,
 * and review/approval remain unknown. Nothing in this service checks or changes them.
 */
internal class DocumentQuotationService(
    private val reader: BoundedCasReader,
    private val policy: DocumentQuotationPolicy,
) {
    private val slots = Semaphore(2)

    suspend fun verify(principal: ReviewPrincipal, factIri: String,
        claim: DocumentQuotationClaim): DocumentQuotationServiceResult {
        if (factIri.isBlank() || factIri.length > 4096 || claim.quote.length > 32_768 ||
            claim.subject.length > 32_768 || claim.predicate.length > 32_768 || claim.obj.length > 32_768 ||
            (claim.modality?.length ?: 0) > 32_768) return DocumentQuotationServiceResult(DocumentQuotationServiceOutcome.LIMIT)
        // Refuse immediately rather than creating an unbounded queue of pending verifier jobs.
        if (!slots.tryAcquire()) return DocumentQuotationServiceResult(DocumentQuotationServiceOutcome.BUSY)
        try {
            val allowed = try { policy.allows(principal, factIri, claim) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return DocumentQuotationServiceResult(DocumentQuotationServiceOutcome.UNAVAILABLE) }
            if (!allowed) return DocumentQuotationServiceResult(DocumentQuotationServiceOutcome.DENIED)
            return try {
                withContext(fileIoContext) {
                    fun read(cid: ContentId, limit: Int): ByteArray? {
                        val bytes = reader.getBounded(cid, limit) ?: return null
                        if (bytes.size > limit) throw BoundedReadLimitException(limit)
                        // Snapshot before the next read, and rehash even though the capability promises verification.
                        return bytes.copyOf().also { check(ContentId.of(it) == cid) { "Bounded CAS identity differs" } }
                    }
                    val record = read(claim.curationCid, 16 * 1024 * 1024)
                        ?: return@withContext DocumentQuotationServiceResult(DocumentQuotationServiceOutcome.UNAVAILABLE)
                    val text = read(claim.extractedTextCid, 16 * 1024 * 1024)
                        ?: return@withContext DocumentQuotationServiceResult(DocumentQuotationServiceOutcome.UNAVAILABLE)
                    val quotation = read(claim.quotationReceiptCid, 64 * 1024)
                        ?: return@withContext DocumentQuotationServiceResult(DocumentQuotationServiceOutcome.UNAVAILABLE)
                    val consistency = DocumentQuotationCheck.verify(claim, record, text, quotation)
                    DocumentQuotationServiceResult(DocumentQuotationServiceOutcome.CHECKED, consistency)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: BoundedReadLimitException) { DocumentQuotationServiceResult(DocumentQuotationServiceOutcome.LIMIT) }
            catch (_: Exception) { DocumentQuotationServiceResult(DocumentQuotationServiceOutcome.UNAVAILABLE) }
        } finally { slots.release() }
    }
}

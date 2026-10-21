package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Installed by the authenticated request boundary; never reconstructed from graph inputs. */
class ReviewPrincipal(val id: String, val synthetic: Boolean = false) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReviewPrincipal>
    init { require(id.isNotBlank() && id.length <= 256) { "An authenticated reviewer identity is required" } }
}

data class CurationReviewQuotation(val originalCid: ContentId, val extractedTextCid: ContentId,
    val begin: Int, val end: Int, val quote: String)

data class CurationReviewSelection(val id: String, val kind: String, val value: Map<String, Any?>,
    val quotation: CurationReviewQuotation)

/** Constructed only by a resolver reading authoritative retained curation, never from submitted JSON. */
data class CurationReviewCandidate(val kind: String, val family: String, val curationReceiptCid: ContentId,
    val ontologyCid: ContentId, val sourceRevision: String, val selections: Series<CurationReviewSelection>,
    val runReceiptCid: ContentId? = null)

enum class CurationReviewAction(val wire: String) { DRAFT("draft"), APPROVE("approve"), REJECT("reject"), CORRECT("correct") }

private data class CurationReviewCall(val execute: suspend () -> Unit, val reject: (Throwable) -> Unit)

/** The private WAL, not an editable board card or a supplied CID, establishes review authority. */
class CurationReview(
    private val cas: CasStore,
    private val log: DurableAppendLog,
    private val resolve: suspend (String, ContentId, List<String>) -> CurationReviewCandidate,
    private val current: suspend (CurationReviewCandidate) -> Boolean,
    private val clock: () -> Long,
) {
    private val commands = Channel<CurationReviewCall>(16)
    private val events = linkedMapOf<ContentId, Map<String, Any?>>()
    private val heads = mutableMapOf<String, ContentId>()
    private val snapshots = mutableSetOf<ContentId>()
    private val correctedReceipts = mutableSetOf<ContentId>()
    private var sequence = 0L
    private var owner: Job? = null
    private var worker: Job? = null
    private var failure: Throwable? = null

    suspend fun open(scope: CoroutineScope) {
        check(owner == null) { "Review ledger is already open" }
        log.replay { seq, bytes ->
            require(seq == sequence + 1) { "Review ledger sequence differs" }
            val cid = ContentId(bytes.decodeToString(throwOnInvalidSequence = true))
            apply(cid, read(cid))
            sequence = seq
        }
        val job = SupervisorJob(scope.coroutineContext[Job])
        owner = job
        worker = CoroutineScope(scope.coroutineContext.minusKey(ReviewPrincipal) + job + CoroutineName("curation-review")).launch {
            for (command in commands) command.execute()
        }.also { running ->
            running.invokeOnCompletion { cause ->
                commands.close(cause)
                val failure = cause ?: CancellationException("Review ledger drained")
                while (true) (commands.tryReceive().getOrNull() ?: break).reject(failure)
            }
        }
    }

    suspend fun drain() {
        commands.close()
        worker?.join()
        owner?.let { (it as CompletableJob).complete(); it.join() }
    }

    private suspend fun <T> submit(block: suspend () -> T): T {
        check(owner?.isActive == true) { "Review ledger is unavailable" }
        val result = CompletableDeferred<T>()
        commands.send(CurationReviewCall({
            try {
                check(failure == null) { "Review ledger persistence is uncertain; reopen before proceeding" }
                result.complete(block())
            }
            catch (failure: Throwable) { result.completeExceptionally(failure) }
        }, { result.completeExceptionally(it) }))
        return result.await()
    }

    suspend fun snapshot(kind: String, receiptCid: ContentId, selection: List<String>): Map<String, Any?> = submit {
        require(receiptCid !in correctedReceipts) { "A corrected source requires a new curation receipt" }
        require(kind.isNotBlank() && selection.isNotEmpty() && selection.size <= 4096 && selection.distinct().size == selection.size) {
            "Choose distinct retained curation selections"
        }
        val candidate = resolve(kind, receiptCid, selection)
        require(candidate.kind == kind && candidate.curationReceiptCid == receiptCid &&
            candidate.selections.view.map { it.id } == selection) { "Resolved curation selection differs" }
        validate(candidate)
        require(current(candidate)) { "Curation source or ontology is no longer current" }
        val bytes = CanonicalCbor.encodeMap(candidate(candidate))
        require(bytes.size <= 1_048_576) { "Review selection exceeds the retained snapshot budget; select a smaller batch" }
        val cid = cas.put(bytes)
        val previous = heads[candidate.family]?.let { events.getValue(it) }
        if (previous?.get("snapshotCid") == cid.value) return@submit inspection(cid)
        require(cid !in snapshots) { "A historical review snapshot cannot replace the current revision" }
        val event = base(candidate.family, cid) + mapOf("action" to "snapshot",
            "previousSnapshotCid" to previous?.get("snapshotCid"))
        append(event)
        inspection(cid)
    }

    suspend fun inspect(snapshotCid: ContentId): Map<String, Any?> = submit { inspection(snapshotCid) }

    /** A source correction withdraws old permission; it never supplies a replacement approval. */
    suspend fun invalidate(receiptCid: ContentId, reason: String = "Source correction"): Unit = submit {
        for (family in heads.keys.toList()) {
            val event = events.getValue(heads.getValue(family))
            val snapshot = ContentId(string(event, "snapshotCid"))
            if (read(snapshot)["curationReceiptCid"] == receiptCid.value && event["action"] != "invalidate")
                append(base(family, snapshot) + mapOf("action" to "invalidate", "reason" to reason))
        }
    }

    suspend fun decide(snapshotCid: ContentId, expectedRevision: Long, action: String, note: String = ""): Map<String, Any?> {
        val principal = currentCoroutineContext()[ReviewPrincipal] ?: error("Authenticated reviewer required")
        val decision = CurationReviewAction.entries.singleOrNull { it.wire == action }
            ?: error("Review action must be draft, approve, reject or correct")
        require(note.length <= 4096) { "Review note exceeds 4096 characters" }
        return submit {
            val (candidate, head) = requireHead(snapshotCid, expectedRevision)
            require(head["action"] !in setOf("correct", "invalidate")) {
                "A corrected source requires a new retained curation snapshot"
            }
            validate(candidate)
            require(current(candidate)) { "Curation source or ontology is no longer current" }
            append(base(candidate.family, snapshotCid) + mapOf("action" to decision.wire,
                "reviewer" to principal.id, "synthetic" to principal.synthetic, "note" to note))
            inspection(snapshotCid)
        }
    }

    /** Mint a single permit from the exact current approval. The sink must consume this permit. */
    suspend fun release(snapshotCid: ContentId, approvalCid: ContentId, expectedRevision: Long): Map<String, Any?> = submit {
        val (candidate, head) = requireHead(snapshotCid, expectedRevision)
        require(head["action"] == "approve" && heads[candidate.family] == approvalCid) { "The exact current approval is required" }
        validate(candidate)
        require(current(candidate)) { "Curation source or ontology is no longer current" }
        val release = append(base(candidate.family, snapshotCid) + mapOf("action" to "release", "approvalCid" to approvalCid.value))
        material(candidate, snapshotCid, approvalCid, release) + ("revision" to events.getValue(release)["revision"])
    }

    /** One sink admission; fabricated, stale, corrected and already consumed permits fail closed. */
    suspend fun consume(releaseCid: ContentId): Map<String, Any?> = submit {
        val event = events[releaseCid] ?: error("Unknown review release")
        require(event["action"] == "release") { "A review release is required" }
        val snapshotCid = ContentId(string(event, "snapshotCid"))
        val (candidate, _) = requireHead(snapshotCid, long(event, "revision"))
        require(heads[candidate.family] == releaseCid) { "Review release is stale or already consumed" }
        validate(candidate)
        require(current(candidate)) { "Curation source or ontology is no longer current" }
        val approval = ContentId(string(event, "approvalCid"))
        val consumed = append(base(candidate.family, snapshotCid) + mapOf("action" to "consume", "releaseCid" to releaseCid.value,
            "approvalCid" to approval.value))
        material(candidate, snapshotCid, approval, releaseCid) + ("admissionCid" to consumed.value)
    }

    private fun material(candidate: CurationReviewCandidate, snapshot: ContentId, approval: ContentId, release: ContentId): Map<String, Any?> {
        val decision = events[approval] ?: error("Approval was not issued by this ledger")
        require(decision["action"] == "approve" && decision["snapshotCid"] == snapshot.value) { "Approval identity differs" }
        fun linked(item: CurationReviewSelection) = selection(item) + mapOf("snapshotCid" to snapshot.value,
            "approvalCid" to approval.value, "releaseCid" to release.value)
        return candidate(candidate) + mapOf("snapshotCid" to snapshot.value, "approvalCid" to approval.value,
            "releaseCid" to release.value, "reviewer" to decision["reviewer"], "synthetic" to decision["synthetic"],
            "relationships" to candidate.selections.view.filter { it.kind == "relationship" }.map(::linked),
            "ruleCandidates" to candidate.selections.view.filter { it.kind == "rule" }.map(::linked))
    }

    private fun requireHead(cid: ContentId, revision: Long): Pair<CurationReviewCandidate, Map<String, Any?>> {
        require(cid in snapshots) { "Unknown review snapshot" }
        val candidate = candidate(read(cid))
        val head = events.getValue(heads.getValue(candidate.family))
        require(head["snapshotCid"] == cid.value && long(head, "revision") == revision) { "Review snapshot or revision changed" }
        return candidate to head
    }

    private suspend fun inspection(cid: ContentId): Map<String, Any?> {
        require(cid in snapshots) { "Unknown review snapshot" }
        val snapshot = read(cid)
        val family = string(snapshot, "family")
        val headCid = heads.getValue(family)
        val head = events.getValue(headCid)
        val sourceCurrent = try { candidate(snapshot).let { validate(it); current(it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        return mapOf("snapshotCid" to cid.value, "snapshot" to snapshot, "revision" to head["revision"],
            "state" to head["action"], "decisionCid" to headCid.value, "current" to (head["snapshotCid"] == cid.value && sourceCurrent &&
                head["action"] !in setOf("correct", "invalidate")),
            "sourceCurrent" to sourceCurrent,
            "reviewer" to head["reviewer"], "synthetic" to head["synthetic"],
            "history" to events.filterValues { it["family"] == family }.map { (key, value) -> value + ("cid" to key.value) })
    }

    private fun base(family: String, snapshot: ContentId): Map<String, Any?> {
        val prior = heads[family]
        return mapOf("kind" to "curation-review-event", "version" to 1, "family" to family,
            "snapshotCid" to snapshot.value, "revision" to ((prior?.let { long(events.getValue(it), "revision") } ?: 0L) + 1),
            "previousCid" to prior?.value, "atMs" to clock())
    }

    private fun append(event: Map<String, Any?>): ContentId {
        try {
            val cid = cas.put(CanonicalCbor.encodeMap(event))
            cas.sync()
            val next = sequence + 1
            check(log.append(next, cid.value.encodeToByteArray()) == next) { "Unexpected review ledger sequence" }
            log.flush()
            apply(cid, event)
            sequence = next
            return cid
        } catch (error: Throwable) {
            failure = error
            throw error
        }
    }

    private fun apply(cid: ContentId, event: Map<String, Any?>) {
        require(event["kind"] == "curation-review-event" && long(event, "version") == 1L) { "Invalid review ledger event" }
        val family = string(event, "family")
        val prior = heads[family]
        require(event["previousCid"] == prior?.value && long(event, "revision") ==
            (prior?.let { long(events.getValue(it), "revision") } ?: 0L) + 1) { "Review ledger lineage differs" }
        val snapshot = ContentId(string(event, "snapshotCid"))
        val candidate = read(snapshot)
        require(string(candidate, "family") == family) { "Review snapshot family differs" }
        if (event["action"] != "snapshot") require(snapshot in snapshots) { "Review snapshot was not retained" }
        events[cid] = event
        heads[family] = cid
        snapshots.add(snapshot)
        if (event["action"] in setOf("correct", "invalidate"))
            correctedReceipts.add(ContentId(string(candidate, "curationReceiptCid")))
    }

    private fun verified(cid: ContentId): ByteArray = (cas.get(cid) ?: error("Retained content unavailable: ${cid.value}"))
        .also { require(ContentId.of(it) == cid) { "Retained content CID mismatch" } }

    private fun read(cid: ContentId): Map<String, Any?> = CanonicalCbor.decodeMap(verified(cid))

    private fun validate(candidate: CurationReviewCandidate) {
        require(candidate.family.isNotBlank() && candidate.sourceRevision.isNotBlank() && candidate.selections.size > 0) { "Curation identity is incomplete" }
        verified(candidate.curationReceiptCid); verified(candidate.ontologyCid)
        candidate.runReceiptCid?.let(::verified)
        require(candidate.selections.view.map { it.id }.distinct().size == candidate.selections.size) { "Duplicate curation selection" }
        val originals = mutableSetOf<ContentId>()
        val extracted = mutableMapOf<ContentId, String>()
        for (item in candidate.selections.view) {
            require(item.id.isNotBlank() && item.kind in setOf("relationship", "rule") && item.value.isNotEmpty()) { "Invalid curation selection" }
            val q = item.quotation
            if (originals.add(q.originalCid)) verified(q.originalCid)
            val text = extracted.getOrPut(q.extractedTextCid) { verified(q.extractedTextCid).decodeToString(throwOnInvalidSequence = true) }
            require(q.begin >= 0 && q.end > q.begin && q.end <= text.length && q.quote.isNotBlank() &&
                text.substring(q.begin, q.end) == q.quote) { "Selected material has no exact source quotation" }
        }
    }

    private fun quotation(q: CurationReviewQuotation): Map<String, Any?> = mapOf("originalCid" to q.originalCid.value,
        "extractedTextCid" to q.extractedTextCid.value, "begin" to q.begin, "end" to q.end, "quote" to q.quote)
    private fun selection(s: CurationReviewSelection): Map<String, Any?> = mapOf("id" to s.id, "kind" to s.kind,
        "value" to s.value, "quotation" to quotation(s.quotation))
    private fun candidate(c: CurationReviewCandidate): Map<String, Any?> = mapOf("kind" to c.kind, "family" to c.family,
        "curationReceiptCid" to c.curationReceiptCid.value, "ontologyCid" to c.ontologyCid.value,
        "sourceRevision" to c.sourceRevision, "runReceiptCid" to c.runReceiptCid?.value,
        "selections" to c.selections.view.map(::selection))
    private fun candidate(c: Map<String, Any?>): CurationReviewCandidate = CurationReviewCandidate(string(c, "kind"), string(c, "family"),
        ContentId(string(c, "curationReceiptCid")), ContentId(string(c, "ontologyCid")), string(c, "sourceRevision"),
        (c["selections"] as? List<*> ?: error("Review selections absent")).map { raw ->
            val s = obj(raw); val q = obj(s["quotation"])
            CurationReviewSelection(string(s, "id"), string(s, "kind"), obj(s["value"]), CurationReviewQuotation(
                ContentId(string(q, "originalCid")), ContentId(string(q, "extractedTextCid")), long(q, "begin").toInt(),
                long(q, "end").toInt(), string(q, "quote")))
        }.toSeries(), (c["runReceiptCid"] as? String)?.let(::ContentId))
    private fun obj(value: Any?): Map<String, Any?> = (value as? Map<*, *>)?.entries?.associate {
        (it.key as? String ?: error("Review field names must be strings")) to it.value
    } ?: error("Review object absent")
    private fun string(value: Map<String, Any?>, key: String): String = value[key] as? String ?: error("Review $key absent")
    private fun long(value: Map<String, Any?>, key: String): Long = (value[key] as? Number)?.toLong() ?: error("Review $key absent")
}

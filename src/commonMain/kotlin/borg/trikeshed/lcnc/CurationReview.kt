package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.ConditionAssessment
import borg.trikeshed.narsese.ConditionAssessmentBatch
import borg.trikeshed.narsese.ConditionAssessmentCheck
import borg.trikeshed.narsese.ConditionAssessmentCodec
import borg.trikeshed.narsese.ConditionAssessmentState
import borg.trikeshed.narsese.ConditionAssessmentStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private const val STRICT_BOOK_POLICY = "strict-book/1"

/** Installed by the authenticated request boundary; never reconstructed from graph inputs. */
class ReviewPrincipal(val id: String, val synthetic: Boolean = false) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReviewPrincipal>
    init {
        require(id.isNotBlank() && id.length <= 256 &&
            id.encodeToByteArray().decodeToString(throwOnInvalidSequence = true) == id) {
            "An authenticated reviewer identity with valid Unicode is required"
        }
    }
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

/** A host decision; graph inputs and review requests never select this policy. */
enum class ConditionReviewPolicy { STRICT_BOOK, LEGACY }

/** Host-issued read snapshot only: never a sink permit, release or admission. */
class CurationReviewedRead internal constructor(
    val candidate: CurationReviewCandidate,
    val snapshotCid: ContentId,
    val approvalCid: ContentId,
    val decisionCid: ContentId,
    val revision: Long,
    val reviewer: String,
    val synthetic: Boolean,
    val assessmentCid: ContentId,
    val assessment: ConditionAssessmentBatch,
    val readCid: ContentId,
    val asOf: Long,
)

private data class CurationReviewCall(val execute: suspend () -> Unit, val reject: (Throwable) -> Unit)

/** The private WAL, not an editable board card or a supplied CID, establishes review authority. */
class CurationReview(
    private val cas: CasStore,
    private val log: DurableAppendLog,
    private val resolve: suspend (String, ContentId, List<String>) -> CurationReviewCandidate,
    private val current: suspend (CurationReviewCandidate) -> Boolean,
    private val clock: () -> Long,
    private val conditionPolicy: ConditionReviewPolicy = ConditionReviewPolicy.STRICT_BOOK,
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

    /**
     * Authenticated non-consuming STRICT_BOOK read. Re-resolves source/ontology and approval
     * lineage under the actor; appends nothing and cannot authorize an executable sink.
     */
    suspend fun reviewedRead(snapshotCid: ContentId, expectedRevision: Long): CurationReviewedRead {
        currentCoroutineContext()[ReviewPrincipal] ?: error("Authenticated reviewer required")
        return submit {
            val (candidate, head) = requireHead(snapshotCid, expectedRevision)
            require(strict(candidate)) { "Reviewed taxonomy reads require STRICT_BOOK material" }
            val decision = heads.getValue(candidate.family)
            val action = string(head, "action")
            require(action in setOf("approve", "release", "consume")) { "A current active approval lineage is required" }
            val approval = if (action == "approve") decision else ContentId(string(head, "approvalCid"))
            if (action == "release") require(head["previousCid"] == approval.value) { "Release approval lineage differs" }
            if (action == "consume") {
                val release = ContentId(string(head, "releaseCid"))
                val event = events[release] ?: error("Release lineage is unavailable")
                require(head["previousCid"] == release.value && event["action"] == "release" &&
                    event["snapshotCid"] == snapshotCid.value && event["approvalCid"] == approval.value &&
                    event["previousCid"] == approval.value) { "Consumed approval lineage differs" }
            }
            validate(candidate)
            require(current(candidate)) { "Curation source or ontology is no longer current" }
            val proof = strictAssessment(candidate, snapshotCid, approval, executable = true)
            val issued = events.getValue(approval)
            val readCid = ContentId.of(CanonicalCbor.encodeMap(mapOf(
                "kind" to "curation-reviewed-read/1", "snapshotCid" to snapshotCid.value,
                "approvalCid" to approval.value, "decisionCid" to decision.value,
                "revision" to expectedRevision, "sourceRevision" to candidate.sourceRevision,
                "curationReceiptCid" to candidate.curationReceiptCid.value,
                "ontologyCid" to candidate.ontologyCid.value, "assessmentCid" to proof.cid.value)))
            CurationReviewedRead(candidate, snapshotCid, approval, decision, expectedRevision,
                string(issued, "reviewer"), issued["synthetic"] == true, proof.cid, proof.checked.batch, readCid, clock())
        }
    }

    /** A source correction withdraws old permission; it never supplies a replacement approval. */
    suspend fun invalidate(receiptCid: ContentId, reason: String = "Source correction"): Unit = submit {
        for (family in heads.keys.toList()) {
            val event = events.getValue(heads.getValue(family))
            val snapshot = ContentId(string(event, "snapshotCid"))
            if (read(snapshot)["curationReceiptCid"] == receiptCid.value && event["action"] != "invalidate")
                append(base(family, snapshot) + mapOf("action" to "invalidate", "reason" to reason))
        }
    }

    suspend fun decide(snapshotCid: ContentId, expectedRevision: Long, action: String, note: String = "",
        expectedAssessmentCid: ContentId? = null): Map<String, Any?> {
        val principal = currentCoroutineContext()[ReviewPrincipal] ?: error("Authenticated reviewer required")
        val decision = CurationReviewAction.entries.singleOrNull { it.wire == action }
            ?: error("Review action must be draft, approve, reject or correct")
        require(note.length <= 4096 && note.encodeToByteArray().decodeToString(throwOnInvalidSequence = true) == note) {
            "Review note is invalid or exceeds 4096 characters"
        }
        return submit {
            val (candidate, head) = requireHead(snapshotCid, expectedRevision)
            require(head["action"] !in setOf("correct", "invalidate")) {
                "A corrected source requires a new retained curation snapshot"
            }
            validate(candidate)
            require(current(candidate)) { "Curation source or ontology is no longer current" }
            val conditionFields = if (strict(candidate) && decision == CurationReviewAction.APPROVE) {
                require(head["action"] == "draft") { "A condition assessment draft is required" }
                val assessmentCid = ContentId(string(head, "assessmentCid"))
                require(expectedAssessmentCid == assessmentCid) { "The exact condition assessment must be selected" }
                val checked = loadAssessment(candidate, snapshotCid, assessmentCid)
                requireComplete(candidate, checked, executable = false)
                val draftCid = heads.getValue(candidate.family)
                _m["conditionPolicy" j STRICT_BOOK_POLICY, "assessmentCid" j assessmentCid.value,
                    "assessmentDraftCid" j draftCid.value, "assessmentStatus" j checked.status.name.lowercase()]
            } else {
                require(expectedAssessmentCid == null) { "Condition assessment is only used for strict book approval" }
                emptyMap()
            }
            append(base(candidate.family, snapshotCid) + mapOf("action" to decision.wire,
                "reviewer" to principal.id, "synthetic" to principal.synthetic, "note" to note) + conditionFields)
            inspection(snapshotCid)
        }
    }

    /**
     * Retain a reviewer's source-linked condition assessments as a draft only. The method itself
     * does not approve a relationship or create a rule. Under strict-book policy the exact draft
     * is required by approval, and release also requires unconditional assessments. The snapshot
     * and frozen assessment bytes are named by the authenticated WAL event.
     */
    suspend fun assess(snapshotCid: ContentId, expectedRevision: Long,
        assessments: Iterable<ConditionAssessment>): Map<String, Any?> {
        val principal = currentCoroutineContext()[ReviewPrincipal] ?: error("Authenticated reviewer required")
        // Caller-owned collections cannot change the bytes subsequently validated or retained.
        val bytes = ConditionAssessmentCodec.encode(snapshotCid, assessments)
        return submit {
            val (candidate, head) = requireHead(snapshotCid, expectedRevision)
            require(head["action"] in setOf("snapshot", "draft", "reject")) {
                "Condition draft cannot replace an approval, release, consumption, or invalidated review"
            }
            require(candidate.kind == "book") { "Condition assessments require book relationships" }
            val reader = cas as? BoundedCasReader ?: error("Bounded CAS reads are required for assessments")
            validate(candidate)
            require(current(candidate)) { "Curation source or ontology is no longer current" }
            val checked = checkAssessment(candidate, snapshotCid, bytes, reader)
            val cid = cas.put(bytes)
            require(cid == ContentId.of(bytes)) { "Assessment identity differs" }
            val stored = requireNotNull(reader.getBounded(cid, ConditionAssessmentCodec.MAX_BYTES)) { "Assessment was not retained" }
            require(stored.contentEquals(bytes) && ContentId.of(stored) == cid) { "Assessment retention differs" }
            append(base(candidate.family, snapshotCid) + _m["action" j "draft", "reviewer" j principal.id,
                "synthetic" j principal.synthetic, "assessmentCid" j cid.value,
                "assessmentStatus" j checked.status.name.lowercase(), "unresolved" j checked.unresolved])
            inspection(snapshotCid)
        }
    }

    private data class CheckedAssessment(val batch: ConditionAssessmentBatch,
        val status: ConditionAssessmentStatus, val unresolved: List<String>)

    /** Checks retained text and offsets, not the reviewer's interpretation of the source. */
    private fun checkAssessment(candidate: CurationReviewCandidate, snapshotCid: ContentId, bytes: ByteArray,
        reader: BoundedCasReader): CheckedAssessment {
        require(candidate.kind == "book") { "Condition assessments require book relationships" }
        val batch = ConditionAssessmentCodec.decode(bytes)
        require(batch.snapshotCid == snapshotCid) { "Assessment snapshot differs" }
        val selections = candidate.selections.view.toList()
        require(selections.size <= 256) { "Assessment selection limit" }
        val extracted = LinkedHashMap<ContentId, ByteArray>()
        var remaining = 32 * 1024 * 1024
        for (item in selections) {
            val cid = item.quotation.extractedTextCid
            if (cid !in extracted) {
                val limit = minOf(16 * 1024 * 1024, remaining)
                val text = requireNotNull(reader.getBounded(cid, limit)) { "Assessment source text is unavailable" }
                require(text.size <= limit && ContentId.of(text) == cid) { "Assessment source text differs" }
                remaining -= text.size
                extracted[cid] = text
            }
        }
        val result = ConditionAssessmentCheck.validate(snapshotCid, selections, batch.assessments, extracted)
        require(result.status == ConditionAssessmentStatus.COMPLETE || result.status == ConditionAssessmentStatus.UNRESOLVED) {
            "Assessment invalid: ${result.status}"
        }
        return CheckedAssessment(batch, result.status, result.unresolved)
    }

    private fun loadAssessment(candidate: CurationReviewCandidate, snapshotCid: ContentId, cid: ContentId): CheckedAssessment {
        val reader = cas as? BoundedCasReader ?: error("Bounded CAS reads are required for assessments")
        val bytes = requireNotNull(reader.getBounded(cid, ConditionAssessmentCodec.MAX_BYTES)) { "Assessment is unavailable" }
        require(bytes.size <= ConditionAssessmentCodec.MAX_BYTES && ContentId.of(bytes) == cid) { "Assessment identity differs" }
        return checkAssessment(candidate, snapshotCid, bytes, reader)
    }

    /** Read the exact current assessment for an authenticated reviewer. */
    suspend fun assessment(snapshotCid: ContentId, expectedRevision: Long): Map<String, Any?> {
        currentCoroutineContext()[ReviewPrincipal] ?: error("Authenticated reviewer required")
        return submit {
            val (candidate, head) = requireHead(snapshotCid, expectedRevision)
            require(head["action"] in setOf("draft", "approve", "release", "consume")) { "Current condition assessment is required" }
            val cid = ContentId(string(head, "assessmentCid"))
            validate(candidate)
            require(current(candidate)) { "Curation source or ontology is no longer current" }
            val proof = if (head["action"] == "draft") null else {
                val approval = if (head["action"] == "approve") heads.getValue(candidate.family)
                    else ContentId(string(head, "approvalCid"))
                strictAssessment(candidate, snapshotCid, approval, executable = false).also {
                    require(it.cid == cid) { "Current assessment identity differs" }
                }
            }
            val checked = proof?.checked ?: loadAssessment(candidate, snapshotCid, cid)
            val draftCid = proof?.draftCid ?: heads.getValue(candidate.family)
            _m["snapshotCid" j snapshotCid.value, "assessmentCid" j cid.value,
                "draftCid" j draftCid.value, "revision" j expectedRevision,
                "reviewer" j events.getValue(draftCid)["reviewer"], "status" j checked.status.name.lowercase(),
                "unresolved" j checked.unresolved,
                "assessments" j checked.batch.assessments.map { assessment ->
                    _m["selectionId" j assessment.selectionId, "state" j assessment.state.name.lowercase(),
                        "spans" j assessment.spans.map { span ->
                            _m["textCid" j span.textCid.value, "begin" j span.begin, "end" j span.end, "quote" j span.quote]
                        }]
                }]
        }
    }

    private fun strict(candidate: CurationReviewCandidate): Boolean =
        conditionPolicy == ConditionReviewPolicy.STRICT_BOOK && candidate.kind == "book"

    private fun requireComplete(candidate: CurationReviewCandidate, checked: CheckedAssessment, executable: Boolean) {
        require(checked.status == ConditionAssessmentStatus.COMPLETE) { "Every relationship requires a condition assessment" }
        val states = checked.batch.assessments.associateBy { it.selectionId }
        for (item in candidate.selections.view) {
            val state = requireNotNull(states[item.id]) { "Condition assessment is missing" }.state
            require(state != ConditionAssessmentState.UNRESOLVED) { "Condition assessment is unresolved" }
            if (state == ConditionAssessmentState.UNCONDITIONAL)
                require(item.value["condition"] == null) { "Unconditional assessment conflicts with parsed condition" }
            if (executable) require(state == ConditionAssessmentState.UNCONDITIONAL) {
                "Conditional relationship requires reviewed executable condition semantics"
            }
        }
    }

    private data class StrictAssessment(val cid: ContentId, val draftCid: ContentId, val checked: CheckedAssessment)

    private fun strictEvent(proof: StrictAssessment?): Map<String, Any?> = if (proof == null) emptyMap()
        else _m["conditionPolicy" j STRICT_BOOK_POLICY, "assessmentCid" j proof.cid.value,
            "assessmentDraftCid" j proof.draftCid.value, "assessmentStatus" j proof.checked.status.name.lowercase()]

    private fun strictAssessment(candidate: CurationReviewCandidate, snapshotCid: ContentId,
        approvalCid: ContentId, executable: Boolean): StrictAssessment {
        val approval = events[approvalCid] ?: error("Approval was not issued by this ledger")
        require(approval["action"] == "approve" && approval["snapshotCid"] == snapshotCid.value &&
            approval["conditionPolicy"] == STRICT_BOOK_POLICY) { "Strict condition approval is required" }
        val cid = ContentId(string(approval, "assessmentCid"))
        val draftCid = ContentId(string(approval, "assessmentDraftCid"))
        val draft = events[draftCid] ?: error("Condition assessment draft is unavailable")
        require(approval["previousCid"] == draftCid.value && draft["action"] == "draft" &&
            draft["snapshotCid"] == snapshotCid.value && draft["assessmentCid"] == cid.value) {
            "Condition assessment draft lineage differs"
        }
        val checked = loadAssessment(candidate, snapshotCid, cid)
        requireComplete(candidate, checked, executable)
        return StrictAssessment(cid, draftCid, checked)
    }

    /** Mint a single permit from the exact current approval. The sink must consume this permit. */
    suspend fun release(snapshotCid: ContentId, approvalCid: ContentId, expectedRevision: Long): Map<String, Any?> = submit {
        val (candidate, head) = requireHead(snapshotCid, expectedRevision)
        require(head["action"] == "approve" && heads[candidate.family] == approvalCid) { "The exact current approval is required" }
        validate(candidate)
        require(current(candidate)) { "Curation source or ontology is no longer current" }
        val proof = if (strict(candidate)) strictAssessment(candidate, snapshotCid, approvalCid, executable = true) else null
        val event = base(candidate.family, snapshotCid) + mapOf("action" to "release", "approvalCid" to approvalCid.value) + strictEvent(proof)
        val release = ContentId.of(CanonicalCbor.encodeMap(event))
        val output = material(candidate, snapshotCid, approvalCid, release, proof)
        append(event)
        output + ("revision" to events.getValue(release)["revision"])
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
        if (strict(candidate)) require(event["previousCid"] == approval.value) { "Release approval lineage differs" }
        val proof = if (strict(candidate)) strictAssessment(candidate, snapshotCid, approval, executable = true) else null
        val output = material(candidate, snapshotCid, approval, releaseCid, proof)
        val consumed = append(base(candidate.family, snapshotCid) + mapOf("action" to "consume", "releaseCid" to releaseCid.value,
            "approvalCid" to approval.value) + strictEvent(proof))
        output + ("admissionCid" to consumed.value)
    }

    private fun material(candidate: CurationReviewCandidate, snapshot: ContentId, approval: ContentId,
        release: ContentId, proof: StrictAssessment?): Map<String, Any?> {
        val decision = events[approval] ?: error("Approval was not issued by this ledger")
        require(decision["action"] == "approve" && decision["snapshotCid"] == snapshot.value) { "Approval identity differs" }
        val states = proof?.checked?.batch?.assessments?.associateBy { it.selectionId }
        fun linked(item: CurationReviewSelection): Map<String, Any?> {
            val condition = if (proof == null) emptyMap() else {
                val assessment = requireNotNull(states?.get(item.id)) { "Condition assessment is missing" }
                _m["conditionReview" j _m["state" j assessment.state.name.lowercase(),
                    "assessmentCid" j proof.cid.value, "draftCid" j proof.draftCid.value, "policy" j STRICT_BOOK_POLICY]]
            }
            return selection(item) + mapOf("snapshotCid" to snapshot.value,
                "approvalCid" to approval.value, "releaseCid" to release.value) + condition
        }
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
        // Assessment status in the event records what was checked at draft time. Its present
        // availability is a separate bounded CAS observation, never proof of current legal validity.
        val assessment = if (head["snapshotCid"] == cid.value) head["assessmentCid"] else null
        val assessmentInspection = inspectAssessment(assessment, cid)
        val availability = assessmentInspection?.availability
        val legacyUnassessed = head["snapshotCid"] == cid.value && snapshot["kind"] == "book" &&
            conditionPolicy == ConditionReviewPolicy.STRICT_BOOK && head["action"] in setOf("approve", "release", "consume") &&
            head["conditionPolicy"] != STRICT_BOOK_POLICY
        return mapOf("snapshotCid" to cid.value, "snapshot" to snapshot, "revision" to head["revision"],
            "state" to head["action"], "decisionCid" to headCid.value, "current" to (head["snapshotCid"] == cid.value && sourceCurrent &&
                head["action"] !in setOf("correct", "invalidate") && !legacyUnassessed &&
                (assessment == null || availability == "available")),
            "sourceCurrent" to sourceCurrent,
            "reviewer" to head["reviewer"], "synthetic" to head["synthetic"],
            "assessmentCid" to assessment,
            "assessmentRecordedStatus" to if (assessment != null) head["assessmentStatus"] else null,
            "assessmentAvailability" to availability,
            "conditionReviewState" to when {
                legacyUnassessed -> "legacy-unassessed"
                conditionPolicy == ConditionReviewPolicy.STRICT_BOOK && assessmentInspection?.conditional == true -> "conditional-nonexecutable"
                else -> null
            },
            "history" to events.filterValues { it["family"] == family }.map { (key, value) -> value + ("cid" to key.value) })
    }

    /** Present integrity/availability, separate from the status retained in the WAL event. */
    private data class AssessmentInspection(val availability: String, val conditional: Boolean = false)

    private fun inspectAssessment(value: Any?, snapshot: ContentId): AssessmentInspection? {
        if (value == null) return null
        val cid = try { ContentId(value as? String ?: return AssessmentInspection("invalid")) }
            catch (_: IllegalArgumentException) { return AssessmentInspection("invalid") }
        val reader = cas as? BoundedCasReader ?: return AssessmentInspection("unavailable")
        val bytes = try { reader.getBounded(cid, ConditionAssessmentCodec.MAX_BYTES) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return AssessmentInspection("unavailable") }
            ?: return AssessmentInspection("missing")
        if (bytes.size > ConditionAssessmentCodec.MAX_BYTES || ContentId.of(bytes) != cid) return AssessmentInspection("invalid")
        val batch = try { ConditionAssessmentCodec.decode(bytes) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return AssessmentInspection("invalid") }
        if (batch.snapshotCid != snapshot) return AssessmentInspection("invalid")
        return AssessmentInspection("available", batch.assessments.any { it.state == ConditionAssessmentState.CONDITIONAL })
    }

    private fun base(family: String, snapshot: ContentId): Map<String, Any?> {
        val prior = heads[family]
        return mapOf("kind" to "curation-review-event", "version" to 1, "family" to family,
            "snapshotCid" to snapshot.value, "revision" to ((prior?.let { long(events.getValue(it), "revision") } ?: 0L) + 1),
            "previousCid" to prior?.value, "atMs" to clock())
    }

    private fun append(event: Map<String, Any?>): ContentId {
        try {
            val bytes = CanonicalCbor.encodeMap(event)
            val cid = cas.put(bytes)
            check(cid == ContentId.of(bytes)) { "Review event identity differs" }
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

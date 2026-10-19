package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reify

enum class LcncCasePhase(val wire: String) { IDLE("idle"), ADMITTED("admitted"), COMPLETED("completed"), PREPARED("terminal-prepared") }

data class LcncCaseRun(val runId: String, val workgroup: String, val claimId: Long, val queueCid: ContentId) {
    fun fields(): Map<String, Any?> = mapOf("runId" to runId, "workgroup" to workgroup,
        "claimId" to claimId, "queueCid" to queueCid.value)
}

/** The nexus owns revision. This head retains only the authored card projection and its evidence. */
data class LcncCaseState(
    val caseId: String,
    val programCid: ContentId,
    val initializeRequestCid: ContentId,
    val lane: String,
    val state: String,
    val io: Map<String, Any?>,
    val effects: Series<Map<String, Any?>>,
    val phase: LcncCasePhase,
    val requestCid: ContentId,
    val run: LcncCaseRun,
    val priorStateCid: ContentId? = null,
    val admissionCid: ContentId? = null,
    val evidenceCid: ContentId? = null,
) {
    fun card(revision: Long): KanbanCardState = KanbanCardState(caseId, "lcnc-case", lane, state, revision, io, effects)

    fun fields(): Map<String, Any?> = mapOf(
        "kind" to "lcnc-case-state", "version" to 1L, "caseId" to caseId,
        "programCid" to programCid.value, "initializeRequestCid" to initializeRequestCid.value,
        "lane" to lane, "state" to state, "io" to io, "effects" to effects.view.toList(),
        "phase" to phase.wire, "requestCid" to requestCid.value, "run" to run.fields(),
        "priorStateCid" to priorStateCid?.value, "admissionCid" to admissionCid?.value, "evidenceCid" to evidenceCid?.value,
    )

    fun retain(cas: CasStore): ContentId = retainLcncCase(cas, fields())

    companion object {
        fun load(cas: CasStore, cid: ContentId): LcncCaseState {
            cas.verifyState(cid.value)
            val bytes = checkNotNull(cas.get(cid))
            check(ContentId.of(bytes) == cid) { "Case head digest mismatch: $cid" }
            val fields = CanonicalCbor.decodeMap(bytes)
            fun text(name: String): String = (fields[name] as? String)?.takeIf { it.isNotBlank() }
                ?: error("Invalid case head $name: $cid")
            fun optional(name: String): ContentId? = fields[name]?.let { ContentId(it as? String ?: error("Invalid case head $name")) }
            check(fields["kind"] == "lcnc-case-state" && fields["version"] == 1L) { "Invalid case head format: $cid" }
            val phase = LcncCasePhase.entries.singleOrNull { it.wire == text("phase") }
                ?: error("Invalid case head phase: $cid")
            val run = stringMap(fields["run"])
            val owner = LcncCaseRun(run["runId"] as? String ?: error("Missing case run"),
                run["workgroup"] as? String ?: error("Missing case workgroup"),
                (run["claimId"] as? Long)?.takeIf { it > 0L } ?: error("Invalid case claim"),
                ContentId(run["queueCid"] as? String ?: error("Missing case queued receipt")))
            check(owner.runId.isNotBlank() && owner.workgroup.isNotBlank())
            val head = LcncCaseState(text("caseId"), ContentId(text("programCid")), ContentId(text("initializeRequestCid")),
                text("lane"), text("state"), stringMap(fields["io"]),
                (fields["effects"] as? List<*>)?.map(::stringMap)?.toSeries() ?: error("Invalid case effects"),
                phase, ContentId(text("requestCid")), owner, optional("priorStateCid"), optional("admissionCid"), optional("evidenceCid"))
            check(encodeLcncCase(head.fields()).contentEquals(bytes)) { "Case head must retain canonical typed fields: $cid" }
            when (phase) {
                LcncCasePhase.IDLE -> check(head.requestCid == head.initializeRequestCid && head.priorStateCid == null &&
                    head.admissionCid == null && head.evidenceCid == null && head.lane == LcncCaseProgram.initialLane)
                LcncCasePhase.ADMITTED -> check(head.priorStateCid != null && head.admissionCid == null && head.evidenceCid == null)
                LcncCasePhase.COMPLETED -> check(head.priorStateCid != null && head.admissionCid == head.priorStateCid && head.evidenceCid != null)
                LcncCasePhase.PREPARED -> check(head.priorStateCid != null && head.admissionCid == null && head.evidenceCid != null)
            }
            for (reference in listOfNotNull(head.programCid, head.priorStateCid, head.admissionCid, head.evidenceCid).distinct())
                cas.verifyState(reference.value)
            val initial = LcncCaseFrame.load(cas, head.initializeRequestCid)
            check(initial.request is LcncCaseRequest.Initialize && initial.request.caseId == head.caseId && initial.programCid == head.programCid)
            val request = LcncCaseFrame.load(cas, head.requestCid)
            check(request.request.caseId == head.caseId && request.programCid == head.programCid)
            check(phase == LcncCasePhase.IDLE || request.request is LcncCaseRequest.Transition)
            val queueCid = head.run.queueCid
            val queuedBytes = checkNotNull(cas.get(queueCid)) { "Missing case queue bytes: $queueCid" }
            check(ContentId.of(queuedBytes) == queueCid) { "Case queue digest mismatch: $queueCid" }
            val command = stringMap(reify(queuedBytes))
            val queued = stringMap(command["lcncRun"])
            val selected = stringMap(stringMap(queued["queueRequest"])["case"])
            val program = queued["program"] as? String ?: error("Invalid case queued program: $queueCid")
            check(command["type"] == "submit" && command["owner"] == "lcnc-runner" &&
                command["jobId"] == "lcnc/run/${head.run.runId}" && queued["status"] == "queued" &&
                queued["runId"] == head.run.runId && queued["programCid"] == head.programCid.value &&
                selected.keys == setOf("intent", "requestCid") && selected["intent"] == request.request.intent &&
                selected["requestCid"] == head.requestCid.value &&
                head.run.workgroup == "${LcncBlackboard.programKey(program)}@${head.programCid.value}") {
                "Case queue provenance mismatch: $queueCid"
            }
            if (head.phase == LcncCasePhase.PREPARED) LcncCaseTerminal.load(cas, head)
            else if (head.io.containsKey(LcncCaseRound.IO)) LcncCaseRound.from(head.io).verify(cas, head, cid)
            return head
        }
    }
}

internal fun stringMap(value: Any?): Map<String, Any?> {
    val fields = value as? Map<*, *> ?: error("Expected case object")
    require(fields.keys.all { it is String }) { "Invalid case field name" }
    @Suppress("UNCHECKED_CAST")
    return (fields as Map<String, Any?>).toMap()
}

internal fun retainLcncCase(cas: CasStore, fields: Map<String, Any?>): ContentId {
    val bytes = encodeLcncCase(fields)
    val cid = ContentId.of(bytes)
    check(cas.put(bytes) == cid) { "Case CAS acknowledgement mismatch: $cid" }
    cas.verifyState(cid.value, synchronize = true)
    check(cas.get(cid)?.contentEquals(bytes) == true) { "Case CAS bytes changed: $cid" }
    return cid
}

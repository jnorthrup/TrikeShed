package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Actual joined walk output and its existing invocation ledger, supplied by the one host executor. */
data class LcncCaseResult(val output: Map<String, Any?>, val invocations: Map<String, Any?>) {
    companion object {
        fun load(cas: CasStore, head: LcncCaseState): LcncCaseResult {
            val resultCid = checkNotNull(head.evidenceCid)
            cas.verifyState(resultCid.value)
            val bytes = checkNotNull(cas.get(resultCid))
            check(ContentId.of(bytes) == resultCid) { "Case result digest mismatch: $resultCid" }
            val fields = CanonicalCbor.decodeMap(bytes)
            check(encodeLcncCase(fields).contentEquals(bytes) && fields["kind"] == "lcnc-case-result" && fields["version"] == 1L &&
                fields["caseId"] == head.caseId && fields["requestCid"] == head.requestCid.value &&
                fields["programCid"] == head.programCid.value && fields["admissionCid"] == head.admissionCid?.value &&
                fields["run"] == head.run.fields()) { "Invalid completed case result" }
            return LcncCaseResult(stringMap(fields["output"]), stringMap(fields["invocations"]))
        }
    }
}

internal const val LCNC_CASE_RULING = "lcnc.case.ruling"

/** This projection names the actual retained result; raw verdict bytes remain in that result. */
internal fun lcncCaseRuling(evidence: ContentId, result: LcncCaseResult): Map<String, Any?> {
    val ruling = CouncilNodes.ruling(stringMap(result.output["returns"])["content"] as? String ?: error("Missing judge content"))
    return mapOf("evidenceCid" to evidence.value, "needsClarification" to ruling["needsClarification"],
        "clarificationQuestion" to ruling["clarificationQuestion"], "mistrial" to ruling["mistrial"])
}
data class LcncCaseOutcome(val metadata: Map<String, Any?>, val output: Map<String, Any?> = emptyMap())

internal fun caseConflict(reason: String): Nothing = throw LcncCaseException(LcncCaseFailure.CONFLICT, reason)
internal fun caseKey(request: ContentId, operation: String) = "lcnc-case/${request.hex}/$operation"

internal suspend fun CouncilCaseRegistry.Case.snapshotAt(cas: CasStore, cid: ContentId): JobSnapshot {
    cas.verifyState(cid.value)
    val bytes = checkNotNull(cas.get(cid))
    check(ContentId.of(bytes) == cid) { "Case snapshot digest mismatch: $cid" }
    val snapshot = CanonicalCbor.decodeJobSnapshot(bytes)
    check(snapshot.jobId == jobId && CanonicalCbor.encode(snapshot).contentEquals(bytes)) { "Invalid case snapshot receipt" }
    snapshot.stateCid?.let { cas.verifyState(it) }
    return snapshot
}

private fun metadata(request: LcncCaseRequest, cid: ContentId, snapshot: JobSnapshot, head: LcncCaseState?): Map<String, Any?> =
    mapOf("intent" to request.intent, "caseId" to request.caseId, "snapshotCid" to cid.value,
        "revision" to snapshot.revision, "stateCid" to snapshot.stateCid, "lifecycle" to snapshot.lifecycle,
        "programCid" to head?.programCid?.value, "lane" to head?.lane,
        "phase" to if (head?.phase == LcncCasePhase.ADMITTED) "interrupted" else head?.phase?.wire ?: "uninitialized",
        "requestCid" to head?.requestCid?.value, "initializeRequestCid" to head?.initializeRequestCid?.value,
        "admissionCid" to head?.admissionCid?.value, "evidenceCid" to head?.evidenceCid?.value, "run" to head?.run?.fields())

internal suspend fun CouncilCaseRegistry.Case.caseMetadata(
    cas: CasStore, request: LcncCaseRequest, cid: ContentId, snapshot: JobSnapshot, head: LcncCaseState?,
): Map<String, Any?> {
    val base = metadata(request, cid, snapshot, head)
    return if (head?.phase == LcncCasePhase.PREPARED) terminalCaseMetadata(cas, cid, snapshot, head, base) else base
}

private fun loadHead(cas: CasStore, caseId: String, snapshot: JobSnapshot): LcncCaseState? = snapshot.stateCid?.let {
    LcncCaseState.load(cas, ContentId(it)).also { head -> check(head.caseId == caseId) { "Case head names another case" } }
}

/** No walker is entered for either initialization or its exact accepted retry. */
suspend fun CouncilCaseRegistry.Case.initializeLcncCase(
    cas: CasStore, frame: LcncCaseFrame, program: LcncProgram, run: LcncCaseRun,
): LcncCaseOutcome {
    val request = frame.request as? LcncCaseRequest.Initialize ?: error("Expected initialize intent")
    require(request.caseId == caseId)
    LcncCaseProgram.validate(program)
    cas.verifyState(frame.programCid.value, synchronize = true)
    val current = snapshot()
    if (current?.stateCid != null) {
        val head = checkNotNull(loadHead(cas, caseId, current))
        if (head.initializeRequestCid != frame.cid) caseConflict("case_initialization_conflict")
        val original = receipt(caseKey(frame.cid, "initialize")) ?: error("Initialized case has no original receipt")
        val snapshot = snapshotAt(cas, original)
        val initial = checkNotNull(loadHead(cas, caseId, snapshot))
        check(initial.phase == LcncCasePhase.IDLE && initial.requestCid == frame.cid && initial.programCid == frame.programCid)
        return LcncCaseOutcome(caseMetadata(cas, request, original, snapshot, initial))
    }
    val seedKey = caseKey(frame.cid, "seed")
    if (current != null) {
        val originalSeed = receipt(seedKey) ?: caseConflict("case_initialization_conflict")
        val seed = snapshotAt(cas, originalSeed)
        check(seed.lifecycle == "submitted" && seed.stateCid == null && seed.revision == 1L) { "Invalid partial initialization receipt" }
        if (current.lifecycle !in setOf("submitted", "active")) caseConflict("case_initialization_conflict")
    }
    // A retained seed may already have reached active before its initializing call failed.
    if (current?.lifecycle != "active") seed(seedKey, caseKey(frame.cid, "start"))
    val active = checkNotNull(snapshot())
    check(active.lifecycle == "active" && active.stateCid == null) { "Case seed did not establish an active empty head" }
    val initial = LcncCaseState(caseId, frame.programCid, frame.cid, LcncCaseProgram.initialLane,
        LcncCaseProgram.initialLane, emptyMap(), emptySeriesOf(), LcncCasePhase.IDLE, frame.cid, run)
    val head = initial.retain(cas)
    val accepted = advance(JobCommand.State(jobId, caseKey(frame.cid, "initialize"), active.revision, null, head.value))
    check(accepted.snapshot.stateCid == head.value)
    val retained = LcncCaseState.load(cas, head)
    check(retained.phase == LcncCasePhase.IDLE && retained.requestCid == frame.cid && retained.run == run)
    return LcncCaseOutcome(caseMetadata(cas, request, accepted.cid, accepted.snapshot, retained))
}

/** Current metadata authenticates the actual retained nexus snapshot and its referenced head. */
suspend fun CouncilCaseRegistry.Case.lcncCaseMetadata(cas: CasStore, request: LcncCaseRequest): Map<String, Any?> {
    require(request.caseId == caseId)
    val cid = snapshotCid() ?: throw LcncCaseException(LcncCaseFailure.NOT_FOUND, "case_not_found")
    val snapshot = snapshotAt(cas, cid)
    return caseMetadata(cas, request, cid, snapshot, loadHead(cas, caseId, snapshot))
}

suspend fun CouncilCaseRegistry.Case.readLcncCase(cas: CasStore, frame: LcncCaseFrame): LcncCaseOutcome {
    require(frame.request is LcncCaseRequest.Read)
    return LcncCaseOutcome(lcncCaseMetadata(cas, frame.request))
}

internal data class LcncCaseHead(val cid: ContentId, val value: LcncCaseState)
internal data class LcncCaseCompletion(
    val source: LcncCaseRound.Source, val head: LcncCaseState, val result: LcncCaseResult,
)

/** The two authored bounds admit twelve HA/HC pairs; terminal callers allow one preparation. */
internal fun caseHistory(cas: CasStore, cid: ContentId, head: LcncCaseState, limit: Int): Series<LcncCaseHead> {
    val history = ArrayList<LcncCaseHead>()
    var point = LcncCaseHead(cid, head)
    while (history.size < limit) {
        check(history.none { it.cid == point.cid }) { "Cyclic case history" }
        check(point.value.caseId == head.caseId && point.value.programCid == head.programCid &&
            point.value.initializeRequestCid == head.initializeRequestCid) { "Foreign case history" }
        history.add(point)
        val prior = point.value.priorStateCid ?: return history.toSeries().also {
            check(point.value.phase == LcncCasePhase.IDLE && point.value.lane == LcncCaseProgram.initialLane) { "Missing case round anchor" }
        }
        point = LcncCaseHead(prior, LcncCaseState.load(cas, prior))
    }
    error("Case history exceeds its authored finite bounds")
}

private fun caseYields(program: LcncProgram, lane: String, result: LcncCaseResult, repeatArgue: Boolean = false): Map<String, Any?> {
    val scope = LcncCaseProgram.scope(program, lane, repeatArgue)
    val node = scope.children.view.single { it.type == "mux.chat" }
    val entries = (result.invocations["entries"] as? List<*>)?.map(::stringMap) ?: error("Missing case invocation evidence")
    check(result.invocations["truncated"] == false) { "Case invocation evidence was truncated" }
    val seat = entries.single { it["type"] == "mux.chat" }
    check(seat["status"] == "completed" && seat["nodeId"] == node.id && seat["path"] == listOf(scope.id)) {
        "Case seat did not complete its authored scope"
    }
    val yields = stringMap(result.output["returns"])
    for (port in s_["prompt", "content", "model"].view)
        check((yields[port] as? String)?.isNotBlank() == true) { "Missing actual case seat $port yield" }
    (seat["outputs"] as? Map<*, *>)?.let { preview ->
        for (port in s_["prompt", "content", "model"].view)
            check(preview[port] == yields[port]) { "Case seat preview differs from its actual yield" }
    }
    return yields
}

internal suspend fun CouncilCaseRegistry.Case.caseCompletion(
    cas: CasStore, point: LcncCaseHead, program: LcncProgram,
): LcncCaseCompletion {
    val head = point.value
    check(head.phase == LcncCasePhase.COMPLETED) { "Counsel source is not completed" }
    val request = LcncCaseFrame.load(cas, head.requestCid).request as? LcncCaseRequest.Transition
        ?: error("Completed case stage has no transition request")
    check(request.requestedTarget == head.lane)
    val accepted = receipt(caseKey(head.requestCid, "complete")) ?: error("Completed case stage has no receipt")
    val snapshot = snapshotAt(cas, accepted)
    check(snapshot.lifecycle == "active" && snapshot.stateCid == point.cid.value) { "Case completion receipt names another head" }
    val admissionCid = checkNotNull(head.admissionCid)
    val admission = LcncCaseState.load(cas, admissionCid)
    check(head.priorStateCid == admissionCid && admission.phase == LcncCasePhase.ADMITTED &&
        admission.caseId == head.caseId && admission.programCid == head.programCid &&
        admission.initializeRequestCid == head.initializeRequestCid && admission.requestCid == head.requestCid &&
        admission.run == head.run && admission.lane == head.lane && admission.priorStateCid == request.priorStateCid) {
        "Case completion does not follow its admission"
    }
    val admissionReceipt = receipt(caseKey(head.requestCid, "admit")) ?: error("Completed case stage has no admission receipt")
    val admitted = snapshotAt(cas, admissionReceipt)
    check(admitted.stateCid == admissionCid.value && admitted.lifecycle == "active" && admitted.revision + 1L == snapshot.revision &&
        request.expectedRevision + 1L == admitted.revision) { "Case completion revisions do not follow the accepted admission" }
    val result = LcncCaseResult.load(cas, head)
    val effect = admission.effects[admission.effects.size - 1]
    val edge = checkNotNull(program.kanban).edges.view.single { it.id == effect["edge"] }
    check(edge.to == head.lane && effect["from"] == edge.from && effect["to"] == edge.to && effect["mode"] == edge.mode.name) {
        "Case admission differs from its authored edge"
    }
    caseYields(program, head.lane, result, edge.mode == KanbanEdgeMode.LOOP && head.lane == "argue")
    return LcncCaseCompletion(LcncCaseRound.Source(head.lane, point.cid, accepted), head, result)
}

private suspend fun CouncilCaseRegistry.Case.caseRoundAnchor(cas: CasStore, history: Series<LcncCaseHead>): ContentId {
    val anchor = history[history.size - 1]
    val accepted = receipt(caseKey(anchor.value.initializeRequestCid, "initialize")) ?: error("Case has no initialized round receipt")
    check(snapshotAt(cas, accepted).stateCid == anchor.cid.value) { "Case round anchor is not the accepted initialization" }
    return anchor.cid
}

internal fun caseTranscript(brief: String, sources: Series<LcncCaseCompletion>): String = buildString {
    append("Original brief:\n").append(brief)
    for (source in sources.view) {
        val output = stringMap(source.result.output["returns"])
        append("\n\n").append(source.source.lane).append(" prompt:\n").append(output.getValue("prompt"))
        append("\n\n").append(source.source.lane).append(" response:\n").append(output.getValue("content"))
        append("\n\n").append(source.source.lane).append(" model:\n").append(output.getValue("model"))
    }
}

private const val CASE_COUNSEL_LOOP = "rebut-argue"
private const val CASE_CLARIFICATION_LOOP = "deliberate-clarify"
private const val CASE_RECORD = "Initial record: actual brief, argue, rebut, deliberate"
private const val CASE_QUESTION = "Clarification requested by the judge"
private const val CASE_ANSWER = "Clarification record and counsel's answer"
private const val CASE_FINAL = "Final clarification round: rule from the full record, or explicitly retain the unresolved request. No further round is available."

private fun caseLoopCounters(head: LcncCaseState) {
    for ((edge, maximum) in s_[CASE_COUNSEL_LOOP j 3, CASE_CLARIFICATION_LOOP j 1].view) {
        val key = "loop.iterations.$edge"
        val value = if (head.io.containsKey(key)) head.io[key] else 0
        val count = (value as? Number)?.toDouble()
        if (count == null || !count.isFinite() || count < 0 || count > maximum || count % 1.0 != 0.0 ||
            head.effects.view.count { it["edge"] == edge && it["mode"] == KanbanEdgeMode.LOOP.name } != count.toInt())
            caseConflict("case_loop_counter_invalid")
    }
}

private fun caseRound(head: LcncCaseState): LcncCaseRound? = head.io[LcncCaseRound.IO]?.let { LcncCaseRound.from(head.io) }

private suspend fun CouncilCaseRegistry.Case.caseSource(
    cas: CasStore, history: Series<LcncCaseHead>, source: LcncCaseRound.Source, program: LcncProgram,
): LcncCaseCompletion {
    val point = history.view.singleOrNull { it.cid == source.stateCid } ?: caseConflict("case_join_round_conflict")
    val completed = caseCompletion(cas, point, program)
    if (completed.source != source) caseConflict("case_join_round_conflict")
    return completed
}

/** Both branches name accepted HC receipts in this very argument round. */
private suspend fun CouncilCaseRegistry.Case.caseCounsel(
    cas: CasStore, history: Series<LcncCaseHead>, rebut: LcncCaseCompletion, program: LcncProgram, origin: ContentId,
): Series<LcncCaseCompletion> {
    val round = caseRound(rebut.head) ?: caseConflict("case_join_round_conflict")
    if (round.initialStateCid != origin || round.sources.size != 1 || round.sources[0].lane != "argue")
        caseConflict("case_join_round_conflict")
    val argue = caseSource(cas, history, round.sources[0], program)
    val argueAdmission = LcncCaseState.load(cas, checkNotNull(argue.head.admissionCid))
    val rebutAdmission = LcncCaseState.load(cas, checkNotNull(rebut.head.admissionCid))
    val argueRound = caseRound(argue.head)
    if (argue.source.lane != "argue" || rebut.source.lane != "rebut" ||
        rebutAdmission.priorStateCid != argue.source.stateCid ||
        (round.version == 1L && argueAdmission.priorStateCid != origin) ||
        (round.version == 2L && (round.argueAdmissionCid != argue.head.admissionCid ||
            round.clarificationSource != argueRound?.clarificationSource)) ||
        argue.source.snapshotCid == rebut.source.snapshotCid || argue.head.run.runId == rebut.head.run.runId ||
        argue.head.run.queueCid == rebut.head.run.queueCid ||
        (argue.head.run.workgroup == rebut.head.run.workgroup && argue.head.run.claimId == rebut.head.run.claimId))
        caseConflict("case_join_round_conflict")
    return s_[argue, rebut]
}

private suspend fun CouncilCaseRegistry.Case.caseJudgeCounsel(
    cas: CasStore, history: Series<LcncCaseHead>, judge: LcncCaseCompletion, program: LcncProgram, origin: ContentId,
): Series<LcncCaseCompletion> {
    val round = caseRound(judge.head) ?: caseConflict("case_join_round_conflict")
    if (round.initialStateCid != origin || round.sources.size != 2 || round.sources[0].lane != "argue" ||
        round.sources[1].lane != "rebut") caseConflict("case_join_round_conflict")
    val rebut = caseSource(cas, history, round.sources[1], program)
    val sources = caseCounsel(cas, history, rebut, program, origin)
    val admission = LcncCaseState.load(cas, checkNotNull(judge.head.admissionCid))
    if (sources[0].source != round.sources[0] || admission.priorStateCid != rebut.source.stateCid ||
        round.clarificationSource != caseRound(rebut.head)?.clarificationSource ||
        (round.version == 2L && round.argueAdmissionCid != sources[0].head.admissionCid))
        caseConflict("case_join_round_conflict")
    return sources
}

internal fun caseOutput(source: LcncCaseCompletion, port: String): String =
    stringMap(source.result.output["returns"])[port] as? String ?: error("Missing actual case seat $port")

/** The graph admits the existing whole-program closure; no selected-node executor is introduced. */
suspend fun CouncilCaseRegistry.Case.transitionLcncCase(
    cas: CasStore, frame: LcncCaseFrame, program: LcncProgram, run: LcncCaseRun,
    execute: suspend (Map<String, Any?>, (suspend (String) -> String)?) -> LcncCaseResult,
): LcncCaseOutcome {
    val request = frame.request as? LcncCaseRequest.Transition ?: error("Expected transition intent")
    require(request.caseId == caseId)
    LcncCaseProgram.validate(program)
    val loops = program.nodes.view.any { it.id == LcncCaseScope.ARGUE_REPEAT.id }
    val currentCid = snapshotCid() ?: throw LcncCaseException(LcncCaseFailure.NOT_FOUND, "case_not_initialized")
    val current = snapshotAt(cas, currentCid)
    val head = loadHead(cas, caseId, current) ?: caseConflict("case_not_initialized")
    if (head.programCid != frame.programCid) caseConflict("case_program_drift")
    val initial = LcncCaseFrame.load(cas, head.initializeRequestCid)
    if ((initial.request as LcncCaseRequest.Initialize).idempotencyKey == request.idempotencyKey)
        caseConflict("case_request_identity_conflict")
    val history = caseHistory(cas, ContentId(checkNotNull(current.stateCid)), head, if (loops && head.phase == LcncCasePhase.PREPARED) 26 else if (loops) 25 else 7)
    for (point in history.view) {
        val previous = LcncCaseFrame.load(cas, point.value.requestCid).request
        if (previous is LcncCaseRequest.Transition && previous.idempotencyKey == request.idempotencyKey) {
            if (point.value.requestCid != frame.cid) caseConflict("case_request_identity_conflict")
            if (point.value.phase == LcncCasePhase.PREPARED)
                return resumeLcncCaseTerminal(cas, frame, program, point, execute)
            if (point.value.phase != LcncCasePhase.COMPLETED) caseConflict("case_interrupted")
            val completed = caseCompletion(cas, point, program)
            val snapshot = snapshotAt(cas, completed.source.snapshotCid)
            return LcncCaseOutcome(caseMetadata(cas, request, completed.source.snapshotCid, snapshot, completed.head), completed.result.output)
        }
    }
    if (head.phase == LcncCasePhase.PREPARED) caseConflict(if (current.lifecycle == "active") "case_terminal_pending" else "case_terminal")
    if (current.lifecycle != "active" || current.revision != request.expectedRevision || current.stateCid != request.priorStateCid.value)
        caseConflict("case_revision_or_head_conflict")
    if (head.phase == LcncCasePhase.ADMITTED && !(loops && request.requestedTarget == "mistrial")) caseConflict("case_interrupted")
    if (loops) caseLoopCounters(head)
    val origin = caseRoundAnchor(cas, history)
    val graph = checkNotNull(program.kanban)
    val currentJudge = if (loops && head.lane == "deliberate" && head.phase == LcncCasePhase.COMPLETED)
        caseCompletion(cas, history[0], program) else null
    val freshRuling = currentJudge?.let { lcncCaseRuling(checkNotNull(it.head.evidenceCid), it.result) }
    // The predicate is freshly derived from the current accepted judge, never caller input or an old flag.
    val currentCard = head.card(current.revision).let { card -> if (freshRuling == null) card else
        card.copy(io = card.io + ("clarification" to freshRuling["needsClarification"]) + (LCNC_CASE_RULING to freshRuling)) }
    val proposed = when (val decision = KanbanGraphEngine.transition(graph.copy(cards = s_[currentCard]),
        KanbanTransitionRequest(caseId, request.expectedRevision, request.requestedTarget), TribunalPredicates.registry())) {
        is KanbanTransitionResult.Rejected -> caseConflict("case_transition_refused: ${decision.reason}")
        is KanbanTransitionResult.Committed -> decision
    }
    val card = proposed.graph.cards.view.single { it.id == caseId }
    val edge = graph.edges.view.single { it.id == proposed.edgeIds.single() }
    if (loops && (edge.mode == KanbanEdgeMode.ABORT || card.lane == "rule"))
        return prepareLcncCaseTerminal(cas, frame, program, run, currentCid, current, history, card, edge, execute)
    val repeatArgue = loops && edge.mode == KanbanEdgeMode.LOOP && card.lane == "argue"
    var stageIo = if (loops) (card.io - LCNC_CASE_RULING) + ("clarification" to false) else card.io
    var stageSources = emptySeriesOf<LcncCaseCompletion>()
    var nextRound: LcncCaseRound? = null
    val originalBrief = initial.inputs["brief"] as String
    val stageInputs = when {
        head.phase == LcncCasePhase.IDLE && head.lane == LcncCaseProgram.initialLane && card.lane == "argue" &&
            edge.mode == KanbanEdgeMode.DIRECT -> {
            if (loops) nextRound = LcncCaseRound(origin, emptySeriesOf(), 2L)
            initial.inputs
        }
        head.phase == LcncCasePhase.COMPLETED && head.lane == "argue" && card.lane == "rebut" &&
            edge.mode == KanbanEdgeMode.DIRECT -> {
            val argue = caseCompletion(cas, history[0], program)
            val previousRound = caseRound(argue.head)
            if (previousRound == null && LcncCaseState.load(cas, checkNotNull(argue.head.admissionCid)).priorStateCid != origin)
                caseConflict("case_join_round_conflict")
            val context = previousRound?.clarificationSource?.let { caseSource(cas, history, it, program) }
            stageSources = if (context == null) s_[argue] else s_[argue, context]
            nextRound = if (loops) LcncCaseRound(origin, s_[argue.source], 2L, argue.head.admissionCid, context?.source)
                else LcncCaseRound(origin, s_[argue.source])
            initial.inputs + ("record" to if (context == null) caseTranscript(originalBrief, s_[argue])
                else CouncilNodes.fold(CASE_ANSWER, caseOutput(argue, "prompt"), caseOutput(argue, "content")))
        }
        card.lane == "deliberate" && edge.mode == KanbanEdgeMode.JOIN -> {
            if (head.phase != LcncCasePhase.COMPLETED || head.lane != "rebut") caseConflict("case_join_requires_counsel")
            val branches = graph.edgesInGroup(edge.group).filter { it.mode == KanbanEdgeMode.JOIN }
            if (branches.size != 2 || branches.map { it.from }.toSet() != setOf("argue", "rebut") ||
                branches.any { it.to != "deliberate" || it.requiredBranches != 2 }) caseConflict("case_join_requires_counsel")
            val rebut = caseCompletion(cas, history[0], program)
            val sources = caseCounsel(cas, history, rebut, program, origin)
            val previousRound = checkNotNull(caseRound(rebut.head))
            val context = previousRound.clarificationSource?.let { caseSource(cas, history, it, program) }
            stageSources = if (context == null) sources else s_[sources[0], sources[1], context]
            nextRound = if (loops) LcncCaseRound(origin, sources α { it.source }, 2L, sources[0].head.admissionCid, context?.source)
                else LcncCaseRound(origin, sources α { it.source })
            initial.inputs + ("record" to if (context == null) caseTranscript(originalBrief, sources)
                else CouncilNodes.fold(CASE_FINAL, caseOutput(rebut, "prompt"), caseOutput(rebut, "content")))
        }
        loops && head.phase == LcncCasePhase.COMPLETED && head.lane == "rebut" && card.lane == "argue" &&
            edge.mode == KanbanEdgeMode.LOOP && edge.id == CASE_COUNSEL_LOOP -> {
            val rebut = caseCompletion(cas, history[0], program)
            val sources = caseCounsel(cas, history, rebut, program, origin)
            val context = checkNotNull(caseRound(rebut.head)).clarificationSource?.let { caseSource(cas, history, it, program) }
            stageSources = if (context == null) sources else s_[sources[0], sources[1], context]
            nextRound = LcncCaseRound(origin, emptySeriesOf(), 2L, clarificationSource = context?.source)
            val record = caseTranscript(originalBrief, sources)
            initial.inputs + ("record" to if (context == null) record else CouncilNodes.fold(CASE_QUESTION, record,
                lcncCaseRuling(checkNotNull(context.head.evidenceCid), context.result)["clarificationQuestion"]))
        }
        loops && currentJudge != null && card.lane == "argue" && edge.mode == KanbanEdgeMode.LOOP &&
            edge.id == CASE_CLARIFICATION_LOOP -> {
            val ruling = checkNotNull(freshRuling)
            check(ruling["needsClarification"] == true && ruling["mistrial"] == false)
            val sources = caseJudgeCounsel(cas, history, currentJudge, program, origin)
            stageSources = s_[sources[0], sources[1], currentJudge]
            nextRound = LcncCaseRound(origin, emptySeriesOf(), 2L, clarificationSource = currentJudge.source)
            val record = CouncilNodes.fold(CASE_RECORD, caseOutput(sources[0], "prompt"),
                s_[caseOutput(sources[0], "content"), caseOutput(sources[1], "content"), caseOutput(currentJudge, "content")].view.toList())
            initial.inputs + ("record" to CouncilNodes.fold(CASE_QUESTION, record, ruling["clarificationQuestion"]))
        }
        else -> throw LcncCaseException(LcncCaseFailure.UNSUPPORTED, "case_transition_not_implemented")
    }
    // Scope validation occurs before HA or any provider/legal effects.
    LcncCaseProgram.scope(program, card.lane, repeatArgue)
    nextRound?.let { stageIo = stageIo + (LcncCaseRound.IO to it.fields()) }
    currentCoroutineContext().ensureActive()
    val admitted = head.copy(lane = card.lane, state = card.state, io = stageIo, effects = card.effects,
        phase = LcncCasePhase.ADMITTED, requestCid = frame.cid, run = run, priorStateCid = request.priorStateCid,
        admissionCid = null, evidenceCid = null)
    val admissionCid = admitted.retain(cas)
    val admission = advance(JobCommand.State(jobId, caseKey(frame.cid, "admit"), current.revision, current.stateCid, admissionCid.value))
    check(admission.snapshot.stateCid == admissionCid.value)
    val retainedAdmission = LcncCaseState.load(cas, admissionCid)
    check(retainedAdmission.phase == LcncCasePhase.ADMITTED && retainedAdmission.requestCid == frame.cid && retainedAdmission.run == run)
    for (source in stageSources.view) {
        val retained = LcncCaseState.load(cas, source.source.stateCid)
        check(caseCompletion(cas, LcncCaseHead(source.source.stateCid, retained), program).source == source.source) {
            "Counsel source changed at stage admission"
        }
    }
    val result = execute(LcncCaseProgram.arguments(stageInputs, card.lane, repeatArgue), null)
    currentCoroutineContext().ensureActive()
    caseYields(program, card.lane, result, repeatArgue)
    val evidenceCid = retainLcncCase(cas, mapOf("kind" to "lcnc-case-result", "version" to 1L,
        "caseId" to caseId, "programCid" to frame.programCid.value, "requestCid" to frame.cid.value,
        "admissionCid" to admissionCid.value, "run" to run.fields(), "output" to result.output, "invocations" to result.invocations))
    var completedIo = stageIo
    if (loops) {
        val round = checkNotNull(nextRound)
        if (card.lane == "argue") completedIo = completedIo + (LcncCaseRound.IO to round.copy(argueAdmissionCid = admissionCid).fields())
        if (card.lane == "deliberate") {
            val ruling = lcncCaseRuling(evidenceCid, result)
            completedIo = completedIo + (LCNC_CASE_RULING to ruling) + ("clarification" to ruling["needsClarification"])
        }
    }
    val completed = admitted.copy(phase = LcncCasePhase.COMPLETED, priorStateCid = admissionCid,
        admissionCid = admissionCid, evidenceCid = evidenceCid, io = completedIo)
    val completedCid = completed.retain(cas)
    val completion = advance(JobCommand.State(jobId, caseKey(frame.cid, "complete"), admission.snapshot.revision,
        admissionCid.value, completedCid.value))
    check(completion.snapshot.stateCid == completedCid.value)
    val retainedCompletion = LcncCaseState.load(cas, completedCid)
    check(retainedCompletion.phase == LcncCasePhase.COMPLETED && retainedCompletion.requestCid == frame.cid &&
        retainedCompletion.admissionCid == admissionCid && retainedCompletion.evidenceCid == evidenceCid && retainedCompletion.run == run)
    return LcncCaseOutcome(caseMetadata(cas, request, completion.cid, completion.snapshot, retainedCompletion), result.output)
}

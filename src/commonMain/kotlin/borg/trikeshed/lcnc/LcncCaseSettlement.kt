package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kanban.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private data class LcncCasePreparation(
    val point: LcncCaseHead, val evidence: LcncCaseTerminal,
    val snapshotCid: ContentId, val snapshot: JobSnapshot,
)

private fun terminalProgram(cas: CasStore, cid: ContentId): LcncProgram {
    val bytes = checkNotNull(cas.get(cid)) { "Missing terminal program: $cid" }
    check(ContentId.of(bytes) == cid) { "Terminal program digest mismatch: $cid" }
    return LcncProgramConfix.fromJson("case", bytes.decodeToString()).also { LcncCaseProgram.scope(it, "rule") }
}

private suspend fun CouncilCaseRegistry.Case.preparation(
    cas: CasStore, point: LcncCaseHead, program: LcncProgram,
): LcncCasePreparation {
    val head = LcncCaseState.load(cas, point.cid)
    val evidence = LcncCaseTerminal.load(cas, head)
    val accepted = receipt(caseKey(head.requestCid, "prepare")) ?: error("Terminal preparation has no accepted receipt")
    val prepared = snapshotAt(cas, accepted)
    val request = LcncCaseFrame.load(cas, head.requestCid).request as LcncCaseRequest.Transition
    check(prepared.stateCid == point.cid.value && prepared.lifecycle == "active" && prepared.revision == request.expectedRevision + 1L)
    val history = caseHistory(cas, evidence.source.stateCid, LcncCaseState.load(cas, evidence.source.stateCid), 25)
    for (entry in history.view) {
        val operation = when (entry.value.phase) {
            LcncCasePhase.IDLE -> "initialize"
            LcncCasePhase.ADMITTED -> "admit"
            LcncCasePhase.COMPLETED -> "complete"
            LcncCasePhase.PREPARED -> error("Nested terminal preparation")
        }
        val actual = receipt(caseKey(entry.value.requestCid, operation)) ?: error("Terminal source has no accepted receipt")
        val snapshot = snapshotAt(cas, actual)
        check(snapshot.stateCid == entry.cid.value && snapshot.lifecycle == "active") { "Terminal source receipt names another head" }
        if (entry.cid == evidence.source.stateCid) check(actual == evidence.source.snapshotCid && snapshot.revision == request.expectedRevision)
        if (entry.value.phase == LcncCasePhase.COMPLETED) {
            val completion = caseCompletion(cas, entry, program)
            check(evidence.sources.view.single { it.stateCid == entry.cid } == completion.source)
        }
    }
    val source = history[0].value
    val card = source.card(request.expectedRevision).let { current ->
        if (source.phase != LcncCasePhase.COMPLETED || source.lane != "deliberate") current else {
            val judge = caseCompletion(cas, history[0], program)
            val ruling = lcncCaseRuling(checkNotNull(judge.head.evidenceCid), judge.result)
            current.copy(io = current.io + ("clarification" to ruling["needsClarification"]) + (LCNC_CASE_RULING to ruling))
        }
    }
    val decision = KanbanGraphEngine.transition(checkNotNull(program.kanban).copy(cards = s_[card]),
        KanbanTransitionRequest(caseId, request.expectedRevision, request.requestedTarget), TribunalPredicates.registry())
    check(decision is KanbanTransitionResult.Committed && decision.edgeIds.single() == evidence.edge["edge"] &&
        decision.graph.cards.view.single { it.id == caseId }.effects.let { it[it.size - 1] } == evidence.edge) {
        "Terminal preparation does not follow its authored transition"
    }
    return LcncCasePreparation(LcncCaseHead(point.cid, head), evidence, accepted, prepared)
}

private suspend fun CouncilCaseRegistry.Case.terminalReceipt(
    cas: CasStore, prepared: LcncCasePreparation,
): Join<ContentId, JobSnapshot>? {
    val cid = receipt(caseKey(prepared.point.value.requestCid, "terminal")) ?: return null
    val snapshot = snapshotAt(cas, cid)
    check(snapshot.lifecycle == prepared.evidence.operation.lifecycle && snapshot.stateCid == prepared.point.cid.value &&
        snapshot.revision == prepared.snapshot.revision + 1L) { "Invalid terminal lifecycle receipt" }
    return cid j snapshot
}

internal suspend fun CouncilCaseRegistry.Case.terminalCaseMetadata(
    cas: CasStore, cid: ContentId, snapshot: JobSnapshot, head: LcncCaseState, base: Map<String, Any?>,
): Map<String, Any?> {
    val point = LcncCaseHead(ContentId(checkNotNull(snapshot.stateCid)), head)
    val prepared = preparation(cas, point, terminalProgram(cas, head.programCid))
    val terminal = terminalReceipt(cas, prepared)
    if (terminal == null) check(cid == prepared.snapshotCid && snapshot.lifecycle == "active" && snapshot.revision == prepared.snapshot.revision)
    else check(cid == terminal.a && snapshot == terminal.b && this.snapshotCid() == cid && this.snapshot() == snapshot) {
        "Terminal read does not name its actual current lifecycle receipt"
    }
    return base + mapOf("phase" to if (terminal == null) "terminal-pending" else "settled",
        "preparationSnapshotCid" to prepared.snapshotCid.value, "terminalReceiptCid" to terminal?.a?.value,
        "terminalEvidenceCid" to head.evidenceCid?.value, "terminalOperation" to prepared.evidence.operation.wire,
        "terminalTextCid" to prepared.evidence.textCid.value, "terminalTranscriptCid" to prepared.evidence.transcriptCid.value,
        "terminalSource" to prepared.evidence.source.fields())
}

/** The reason is the closed request, authored edge and actual source, never a caller-supplied failure string. */
internal fun terminalAbortText(
    caseId: String, programCid: ContentId, requestCid: ContentId, edge: Map<String, Any?>,
    source: LcncCaseTerminal.Source, head: LcncCaseState, judgeText: String?,
): String = if (judgeText != null && CouncilNodes.ruling(judgeText)["mistrial"] == true) judgeText else jsonOf(mapOf(
    "kind" to "lcnc-case-abort", "mistrial" to true, "caseId" to caseId, "programCid" to programCid.value,
    "requestCid" to requestCid.value, "edge" to edge["edge"], "source" to source.fields(),
    "sourceLane" to head.lane, "sourcePhase" to head.phase.wire, "judgeText" to judgeText,
))

internal suspend fun CouncilCaseRegistry.Case.prepareLcncCaseTerminal(
    cas: CasStore, frame: LcncCaseFrame, program: LcncProgram, run: LcncCaseRun,
    currentCid: ContentId, current: JobSnapshot, history: Series<LcncCaseHead>, card: KanbanCardState, edge: KanbanEdge,
    execute: suspend (Map<String, Any?>, (suspend (String) -> String)?) -> LcncCaseResult,
): LcncCaseOutcome {
    LcncCaseProgram.scope(program, "rule")
    val source = history[0]
    val completed = history.filter { it.value.phase == LcncCasePhase.COMPLETED }
    val ordered: Series<LcncCaseHead> = completed.size j { index: Int -> completed[completed.size - index - 1] }
    val acceptedSources = ArrayList<LcncCaseCompletion>()
    for (point in ordered.view) acceptedSources.add(caseCompletion(cas, point, program))
    val sources = acceptedSources.toSeries()
    val judge = if (source.value.phase == LcncCasePhase.COMPLETED && source.value.lane == "deliberate")
        sources[sources.size - 1] else null
    val operation = when {
        edge.mode == KanbanEdgeMode.ABORT && card.lane == "mistrial" -> LcncCaseTerminal.Operation.FAIL
        edge.mode == KanbanEdgeMode.DIRECT && card.lane == "rule" && judge != null -> {
            val ruling = lcncCaseRuling(checkNotNull(judge.head.evidenceCid), judge.result)
            check(ruling["needsClarification"] == false && ruling["mistrial"] == false)
            LcncCaseTerminal.Operation.COMPLETE
        }
        else -> throw LcncCaseException(LcncCaseFailure.UNSUPPORTED, "case_terminal_transition_not_implemented")
    }
    val selected = card.effects[card.effects.size - 1]
    val origin = LcncCaseTerminal.Source(source.cid, currentCid)
    val text = if (operation == LcncCaseTerminal.Operation.COMPLETE) caseOutput(checkNotNull(judge), "content")
        else terminalAbortText(caseId, frame.programCid, frame.cid, selected, origin, source.value, judge?.let { caseOutput(it, "content") })
    val initial = LcncCaseFrame.load(cas, source.value.initializeRequestCid)
    val transcript = caseTranscript(initial.inputs["brief"] as String, sources)
    val evidence = LcncCaseTerminal(caseId, frame.programCid, source.value.initializeRequestCid, frame.cid, run,
        operation, selected, origin, sources α { it.source }, retainTerminalText(cas, text), retainTerminalText(cas, transcript),
        terminalCounters(card.io))
    val evidenceCid = evidence.retain(cas)
    val head = source.value.copy(lane = card.lane, state = card.state,
        io = (card.io - LcncCaseRound.IO - LCNC_CASE_RULING) + ("clarification" to false), effects = card.effects,
        phase = LcncCasePhase.PREPARED, requestCid = frame.cid, run = run, priorStateCid = source.cid,
        admissionCid = null, evidenceCid = evidenceCid)
    val preparedCid = head.retain(cas)
    // All named sources and final text are freshly read before the only preparation State is accepted.
    LcncCaseState.load(cas, preparedCid)
    currentCoroutineContext().ensureActive()
    val accepted = advance(JobCommand.State(jobId, caseKey(frame.cid, "prepare"), current.revision, current.stateCid, preparedCid.value))
    check(accepted.snapshot.stateCid == preparedCid.value && accepted.snapshot.lifecycle == "active" && accepted.snapshot.revision == current.revision + 1L)
    return resumeLcncCaseTerminal(cas, frame, program, LcncCaseHead(preparedCid, head), execute)
}

internal suspend fun CouncilCaseRegistry.Case.resumeLcncCaseTerminal(
    cas: CasStore, frame: LcncCaseFrame, program: LcncProgram, point: LcncCaseHead,
    execute: suspend (Map<String, Any?>, (suspend (String) -> String)?) -> LcncCaseResult,
): LcncCaseOutcome {
    val prepared = preparation(cas, point, program)
    check(prepared.point.value.requestCid == frame.cid && prepared.point.value.programCid == frame.programCid)
    val text = readTerminalText(cas, prepared.evidence.textCid)
    terminalReceipt(cas, prepared)?.let { terminal ->
        val report = mapOf("text" to text, "cid" to prepared.evidence.textCid.value, "recorded" to terminal.a.value, "chars" to text.length)
        return LcncCaseOutcome(caseMetadata(cas, frame.request, terminal.a, terminal.b, prepared.point.value),
            mapOf("returns" to mapOf("report" to report), "outputs" to emptyMap<String, Any?>(), "terminalWalkObserved" to false))
    }
    check(snapshotCid() == prepared.snapshotCid && snapshot() == prepared.snapshot) { "Terminal preparation is no longer current and active" }
    val record = tribunalRecord(cas) { retained ->
        check(retained == prepared.evidence.textCid) { "Terminal scope supplied another verdict" }
        val fresh = preparation(cas, prepared.point, program)
        terminalReceipt(cas, fresh)?.a?.value ?: run {
            check(snapshotCid() == fresh.snapshotCid && snapshot() == fresh.snapshot) { "Terminal preparation is no longer current and active" }
            val command = when (fresh.evidence.operation) {
                LcncCaseTerminal.Operation.COMPLETE -> JobCommand.Complete(jobId, caseKey(frame.cid, "terminal"), fresh.snapshot.revision)
                LcncCaseTerminal.Operation.FAIL -> JobCommand.Fail(jobId, caseKey(frame.cid, "terminal"), fresh.snapshot.revision, text)
            }
            advance(command)
            val retained = preparation(cas, fresh.point, program)
            checkNotNull(terminalReceipt(cas, retained)).a.value
        }
    }
    val result = execute(LcncCaseProgram.arguments(mapOf("record" to text), "rule"), record)
    val terminal = checkNotNull(terminalReceipt(cas, preparation(cas, prepared.point, program))) { "Terminal walk returned without lifecycle settlement" }
    val entries = (result.invocations["entries"] as? List<*>)?.map(::stringMap) ?: error("Missing terminal invocation evidence")
    check(result.invocations["truncated"] == false && entries.none { it["type"] in setOf("mux.chat", "legal.ingest", "legal.evidence") })
    val node = LcncCaseProgram.scope(program, "rule").children.view.single { it.type == "tribunal.record" }
    val entry = entries.single { it["type"] == "tribunal.record" }
    check(entry["nodeId"] == node.id && entry["path"] == listOf("case.rule") && entry["status"] == "completed")
    val report = stringMap(stringMap(result.output["returns"])["report"])
    check(report.keys == setOf("text", "cid", "recorded", "chars") && report["text"] == text &&
        report["cid"] == prepared.evidence.textCid.value && report["recorded"] == terminal.a.value &&
        (report["chars"] as? Number)?.toLong() == text.length.toLong()) { "Terminal report does not name its actual receipt" }
    return LcncCaseOutcome(caseMetadata(cas, frame.request, terminal.a, terminal.b, prepared.point.value), result.output + ("terminalWalkObserved" to true))
}

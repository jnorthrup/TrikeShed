package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*

/** Preparation names retained evidence; only the separate keyed lifecycle snapshot settles it. */
data class LcncCaseTerminal(
    val caseId: String,
    val programCid: ContentId,
    val initializeRequestCid: ContentId,
    val requestCid: ContentId,
    val run: LcncCaseRun,
    val operation: Operation,
    val edge: Map<String, Any?>,
    val source: Source,
    val sources: Series<LcncCaseRound.Source>,
    val textCid: ContentId,
    val transcriptCid: ContentId,
    val counters: Map<String, Long>,
) {
    enum class Operation(val wire: String, val lifecycle: String) { COMPLETE("complete", "closed"), FAIL("fail", "failed") }
    data class Source(val stateCid: ContentId, val snapshotCid: ContentId) {
        fun fields(): Map<String, Any?> = mapOf("stateCid" to stateCid.value, "snapshotCid" to snapshotCid.value)
    }

    fun fields(): Map<String, Any?> = mapOf("kind" to "lcnc-case-terminal", "version" to 1L,
        "caseId" to caseId, "programCid" to programCid.value, "initializeRequestCid" to initializeRequestCid.value,
        "requestCid" to requestCid.value, "run" to run.fields(), "operation" to operation.wire,
        "edge" to edge, "source" to source.fields(), "sources" to (sources α { it.fields() }).view.toList(),
        "textCid" to textCid.value, "transcriptCid" to transcriptCid.value, "counters" to counters)

    fun retain(cas: CasStore): ContentId = retainLcncCase(cas, fields())

    companion object {
        fun load(cas: CasStore, head: LcncCaseState): LcncCaseTerminal {
            check(head.phase == LcncCasePhase.PREPARED)
            val cid = checkNotNull(head.evidenceCid)
            val bytes = checkNotNull(cas.get(cid)) { "Missing terminal evidence: $cid" }
            check(ContentId.of(bytes) == cid) { "Terminal evidence digest mismatch: $cid" }
            val fields = CanonicalCbor.decodeMap(bytes)
            fun text(name: String): String = fields[name] as? String ?: error("Invalid terminal $name")
            val origin = stringMap(fields["source"])
            check(origin.keys == setOf("stateCid", "snapshotCid"))
            val values = fields["sources"] as? List<*> ?: error("Invalid terminal sources")
            val sources: Series<LcncCaseRound.Source> = values.size j { index: Int ->
                val source = stringMap(values[index])
                check(source.keys == setOf("lane", "stateCid", "snapshotCid"))
                LcncCaseRound.Source(source["lane"] as? String ?: error("Invalid terminal source lane"),
                    ContentId(source["stateCid"] as? String ?: error("Invalid terminal source head")),
                    ContentId(source["snapshotCid"] as? String ?: error("Invalid terminal source receipt")))
            }
            val counts = stringMap(fields["counters"])
            val counters = counts.mapValues { (_, value) -> value as? Long ?: error("Invalid terminal counter") }
            val value = LcncCaseTerminal(text("caseId"), ContentId(text("programCid")), ContentId(text("initializeRequestCid")),
                ContentId(text("requestCid")), head.run, Operation.entries.single { it.wire == text("operation") },
                stringMap(fields["edge"]), Source(ContentId(origin["stateCid"] as? String ?: error("Invalid terminal origin")),
                    ContentId(origin["snapshotCid"] as? String ?: error("Invalid terminal origin receipt"))),
                sources, ContentId(text("textCid")), ContentId(text("transcriptCid")), counters)
            check(fields["kind"] == "lcnc-case-terminal" && fields["version"] == 1L &&
                encodeLcncCase(value.fields()).contentEquals(bytes)) { "Invalid canonical terminal evidence: $cid" }
            check(value.caseId == head.caseId && value.programCid == head.programCid &&
                value.initializeRequestCid == head.initializeRequestCid && value.requestCid == head.requestCid &&
                value.source.stateCid == head.priorStateCid && head.admissionCid == null && fields["run"] == head.run.fields()) {
                "Terminal evidence names another preparation"
            }
            val source = LcncCaseState.load(cas, value.source.stateCid)
            check(source.phase != LcncCasePhase.PREPARED && source.caseId == head.caseId && source.programCid == head.programCid &&
                source.initializeRequestCid == head.initializeRequestCid) { "Foreign terminal origin" }
            val request = LcncCaseFrame.load(cas, head.requestCid).request as? LcncCaseRequest.Transition
                ?: error("Terminal preparation has no transition request")
            val sourceSnapshot = terminalSnapshot(cas, value.source.snapshotCid, head.caseId, value.source.stateCid)
            check(sourceSnapshot.revision == request.expectedRevision && sourceSnapshot.lifecycle == "active" &&
                request.priorStateCid == value.source.stateCid && request.requestedTarget == head.lane && head.state == head.lane)
            check(value.edge.keys == setOf("edge", "from", "to", "mode", "iteration") &&
                value.edge["from"] == source.lane && value.edge["to"] == head.lane && value.edge["iteration"] == null &&
                ((value.operation == Operation.COMPLETE && value.edge["mode"] == "DIRECT" && head.lane == "rule") ||
                    (value.operation == Operation.FAIL && value.edge["mode"] == "ABORT" && head.lane == "mistrial"))) {
                "Invalid terminal operation"
            }
            check(head.effects.size == source.effects.size + 1 && (0 until source.effects.size).all { head.effects[it] == source.effects[it] } &&
                head.effects[head.effects.size - 1] == value.edge && value.counters == terminalCounters(source.io) &&
                value.counters == terminalCounters(head.io) &&
                head.io == (source.io - LcncCaseRound.IO - LCNC_CASE_RULING) + ("clarification" to false)) {
                "Terminal preparation changed its source card"
            }
            check(sources.size <= 12 && (sources α { it.stateCid }).view.toSet().size == sources.size &&
                (sources α { it.snapshotCid }).view.toSet().size == sources.size) { "Invalid terminal history" }
            var sourceIndex = sources.size - 1
            var pointCid = value.source.stateCid
            var point = source
            val seen = mutableSetOf<ContentId>()
            while (true) {
                check(seen.add(pointCid) && seen.size <= 25 && point.caseId == head.caseId && point.programCid == head.programCid &&
                    point.initializeRequestCid == head.initializeRequestCid && point.phase != LcncCasePhase.PREPARED) { "Invalid terminal history" }
                if (point.phase == LcncCasePhase.COMPLETED) {
                    check(sourceIndex >= 0) { "Missing terminal source" }
                    val retained = sources[sourceIndex--]
                    check(retained.stateCid == pointCid && retained.lane == point.lane)
                    terminalSnapshot(cas, retained.snapshotCid, head.caseId, pointCid).also { check(it.lifecycle == "active") }
                    LcncCaseResult.load(cas, point)
                }
                val prior = point.priorStateCid
                if (prior == null) {
                    check(point.phase == LcncCasePhase.IDLE && sourceIndex == -1) { "Missing terminal case origin" }
                    break
                }
                pointCid = prior
                point = LcncCaseState.load(cas, prior)
            }
            val completed: Series<LcncCaseCompletion> = sources.size j { index: Int ->
                val source = sources[index]
                val head = LcncCaseState.load(cas, source.stateCid)
                LcncCaseCompletion(source, head, LcncCaseResult.load(cas, head))
            }
            val initial = LcncCaseFrame.load(cas, head.initializeRequestCid)
            check(readTerminalText(cas, value.transcriptCid) == caseTranscript(initial.inputs["brief"] as String, completed)) {
                "Terminal transcript differs from its accepted sources"
            }
            val judgeText = if (source.phase == LcncCasePhase.COMPLETED && source.lane == "deliberate")
                stringMap(LcncCaseResult.load(cas, source).output["returns"])["content"] as? String ?: error("Missing final judge text") else null
            val expected = if (value.operation == Operation.COMPLETE) {
                val text = checkNotNull(judgeText) { "A ruling requires a completed judge" }
                val parsed = CouncilNodes.ruling(text)
                check(parsed["needsClarification"] == false && parsed["mistrial"] == false) { "Unresolved judge cannot settle a ruling" }
                text
            } else terminalAbortText(head.caseId, head.programCid, head.requestCid, value.edge, value.source, source, judgeText)
            check(readTerminalText(cas, value.textCid) == expected) { "Terminal text differs from its authenticated outcome" }
            return value
        }
    }
}

internal fun terminalCounters(io: Map<String, Any?>): Map<String, Long> =
    s_["loop.iterations.rebut-argue" j 3L, "loop.iterations.deliberate-clarify" j 1L].view.associate { (key, limit) ->
        val count = if (io.containsKey(key)) (io[key] as? Number)?.toDouble() else 0.0
        check(count != null && count.isFinite() && count >= 0 && count <= limit && count % 1.0 == 0.0) { "Invalid terminal counter" }
        key to count.toLong()
    }

internal fun terminalSnapshot(cas: CasStore, cid: ContentId, caseId: String, stateCid: ContentId): JobSnapshot {
    val bytes = checkNotNull(cas.get(cid)) { "Missing terminal source snapshot: $cid" }
    check(ContentId.of(bytes) == cid) { "Terminal source snapshot digest mismatch: $cid" }
    val snapshot = CanonicalCbor.decodeJobSnapshot(bytes)
    check(CanonicalCbor.encode(snapshot).contentEquals(bytes) && snapshot.jobId == JobId.of("case/$caseId") && snapshot.stateCid == stateCid.value)
    return snapshot
}

internal fun readTerminalText(cas: CasStore, cid: ContentId): String {
    val bytes = checkNotNull(cas.get(cid)) { "Missing terminal text: $cid" }
    check(ContentId.of(bytes) == cid) { "Terminal text digest mismatch: $cid" }
    val text = bytes.decodeToString()
    check(text.isNotBlank() && text.encodeToByteArray().contentEquals(bytes)) { "Invalid terminal text: $cid" }
    return text
}

internal fun retainTerminalText(cas: CasStore, text: String): ContentId {
    require(text.isNotBlank())
    val bytes = text.encodeToByteArray()
    val cid = ContentId.of(bytes)
    check(cas.put(bytes) == cid)
    cas.verifyState(cid.value, synchronize = true)
    check(readTerminalText(cas, cid) == text)
    return cid
}

package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*

/** H0 is the origin; v2 rounds are anchored by an actual accepted argue admission. */
data class LcncCaseRound(
    val initialStateCid: ContentId,
    val sources: Series<Source>,
    val version: Long = 1L,
    val argueAdmissionCid: ContentId? = null,
    val clarificationSource: Source? = null,
) {
    data class Source(val lane: String, val stateCid: ContentId, val snapshotCid: ContentId) {
        fun fields(): Map<String, Any?> = mapOf("lane" to lane, "stateCid" to stateCid.value, "snapshotCid" to snapshotCid.value)
    }

    fun fields(): Map<String, Any?> = mapOf("kind" to "lcnc-case-round", "version" to version,
        "initialStateCid" to initialStateCid.value, "sources" to (sources α { it.fields() }).view.toList()) +
        if (version == 1L) emptyMap() else mapOf("argueAdmissionCid" to argueAdmissionCid?.value,
            "clarificationSource" to clarificationSource?.fields())

    /** Metadata and effect admission freshly authenticate exactly the retained source bytes. */
    fun verify(cas: CasStore, head: LcncCaseState, headCid: ContentId) {
        val lanes = when (head.lane) {
            "argue" -> emptySeriesOf()
            "rebut" -> s_["argue"]
            "deliberate" -> s_["argue", "rebut"]
            else -> error("Case round does not match its stage")
        }
        check(head.phase != LcncCasePhase.IDLE && (version == 2L || head.lane != "argue") &&
            sources.size == lanes.size && (0 until sources.size).all { sources[it].lane == lanes[it] }) {
            "Case round does not match its stage"
        }
        val initial = LcncCaseState.load(cas, initialStateCid)
        check(initial.phase == LcncCasePhase.IDLE && initial.lane == LcncCaseProgram.initialLane &&
            initial.caseId == head.caseId && initial.programCid == head.programCid &&
            initial.initializeRequestCid == head.initializeRequestCid) { "Foreign case round anchor" }
        val completed: Series<LcncCaseState> = sources.size j { index: Int -> readSource(cas, head, sources[index]) }
        for (source in completed.view) check(source.phase == LcncCasePhase.COMPLETED)
        if (version == 1L) return

        val pending = head.lane == "argue" && head.phase == LcncCasePhase.ADMITTED
        check((argueAdmissionCid == null) == pending) { "Invalid pending argument identity" }
        val anchorCid = argueAdmissionCid ?: headCid
        val anchor = if (pending) head else LcncCaseState.load(cas, anchorCid)
        check(anchor.caseId == head.caseId && anchor.programCid == head.programCid &&
            anchor.initializeRequestCid == head.initializeRequestCid && anchor.lane == "argue" &&
            anchor.phase == LcncCasePhase.ADMITTED) { "Invalid argument admission anchor" }
        val anchorRound = anchor.io[IO]?.let { from(anchor.io) }
        if (anchorRound == null) check(anchor.priorStateCid == initialStateCid && clarificationSource == null) {
            "Missing argument round identity"
        } else check(anchorRound.version == 2L && anchorRound.initialStateCid == initialStateCid &&
            anchorRound.argueAdmissionCid == null && anchorRound.sources.size == 0 &&
            anchorRound.clarificationSource == clarificationSource) { "Foreign argument admission round" }
        if (head.lane == "argue" && !pending)
            check(head.admissionCid == anchorCid && head.requestCid == anchor.requestCid && head.run == anchor.run)
        for (source in completed.view) {
            val sourceAnchor = if (source.lane == "argue") checkNotNull(source.admissionCid) else {
                val round = from(source.io)
                round.argueAdmissionCid ?: checkNotNull(LcncCaseState.load(cas, round.sources[0].stateCid).admissionCid)
            }
            check(sourceAnchor == anchorCid) { "Counsel belongs to another argument round" }
            source.io[IO]?.let {
                val round = from(source.io)
                check(round.initialStateCid == initialStateCid && round.clarificationSource == clarificationSource) {
                    "Counsel belongs to another clarification epoch"
                }
            }
        }
        clarificationSource?.let { source ->
            check(source.lane == "deliberate") { "Invalid clarification source lane" }
            val judge = readSource(cas, head, source)
            val ruling = lcncCaseRuling(checkNotNull(judge.evidenceCid), LcncCaseResult.load(cas, judge))
            check(ruling["needsClarification"] == true && ruling["mistrial"] == false) { "Judge did not request clarification" }
        }
        if (head.lane == "deliberate" && head.phase == LcncCasePhase.COMPLETED) {
            val ruling = lcncCaseRuling(checkNotNull(head.evidenceCid), LcncCaseResult.load(cas, head))
            check(head.io[LCNC_CASE_RULING] == ruling && head.io["clarification"] == ruling["needsClarification"]) {
                "Judge predicate differs from its actual retained result"
            }
        } else check(head.io["clarification"] == false && !head.io.containsKey(LCNC_CASE_RULING)) {
            "A previous judge predicate survived argument admission"
        }
    }

    private fun readSource(cas: CasStore, head: LcncCaseState, source: Source): LcncCaseState {
        val completed = LcncCaseState.load(cas, source.stateCid)
        check(completed.phase == LcncCasePhase.COMPLETED && completed.lane == source.lane &&
            completed.caseId == head.caseId && completed.programCid == head.programCid &&
            completed.initializeRequestCid == head.initializeRequestCid) { "Foreign counsel source" }
        val bytes = checkNotNull(cas.get(source.snapshotCid)) { "Missing counsel receipt: ${source.snapshotCid}" }
        check(ContentId.of(bytes) == source.snapshotCid) { "Counsel receipt digest mismatch: ${source.snapshotCid}" }
        val snapshot = CanonicalCbor.decodeJobSnapshot(bytes)
        check(CanonicalCbor.encode(snapshot).contentEquals(bytes) && snapshot.jobId == JobId.of("case/${head.caseId}") &&
            snapshot.stateCid == source.stateCid.value && snapshot.lifecycle == "active") { "Invalid counsel receipt" }
        return completed
    }

    companion object {
        const val IO = "lcnc.case.round"

        fun from(io: Map<String, Any?>): LcncCaseRound {
            val fields = stringMap(io[IO])
            val version = fields["version"]
            check(version == 1L || version == 2L) { "Invalid case round version" }
            val keys = setOf("kind", "version", "initialStateCid", "sources") +
                if (version == 1L) emptySet() else setOf("argueAdmissionCid", "clarificationSource")
            check(fields.keys == keys && fields["kind"] == "lcnc-case-round") { "Invalid case round" }
            fun source(value: Any?, lanes: Set<String>): Source {
                val fields = stringMap(value)
                check(fields.keys == setOf("lane", "stateCid", "snapshotCid") && fields["lane"] in lanes) { "Invalid counsel source" }
                return Source(fields["lane"] as String,
                    ContentId(fields["stateCid"] as? String ?: error("Invalid counsel head")),
                    ContentId(fields["snapshotCid"] as? String ?: error("Invalid counsel receipt")))
            }
            val values = fields["sources"] as? List<*> ?: error("Invalid counsel sources")
            val sources: Series<Source> = values.size j { index: Int -> source(values[index], setOf("argue", "rebut")) }
            check(sources.size in (if (version == 1L) 1 else 0)..2 &&
                (sources α { it.lane }).view.toSet().size == sources.size) { "Invalid counsel source lanes" }
            val admission = fields["argueAdmissionCid"]?.let { ContentId(it as? String ?: error("Invalid argument admission")) }
            val clarification = fields["clarificationSource"]?.let { source(it, setOf("deliberate")) }
            return LcncCaseRound(ContentId(fields["initialStateCid"] as? String ?: error("Invalid round anchor")),
                sources, version as Long, admission, clarification)
        }
    }
}

package borg.trikeshed.utils.kanban

import borg.trikeshed.parse.reify

import borg.trikeshed.kanban.SessionCause
import borg.trikeshed.kanban.SessionSnapshot
import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt

/**
 * Confix record codec for the Kanban causal log.
 *
 * The quandary resolution: causes are heterogeneous sealed events → Confix
 * records (per-record schema, agent-inspectable, CBOR-gate compliant).
 * Snapshots are homogeneous telemetry destined for ISAM columns; until volume
 * demands the spool, they ride the same log as records.
 *
 * Records are JSON-syntax Confix objects, one per WAL entry. We control both
 * writer and reader; [reify] (CharSeries parser, no kotlinx) reads back.
 */
object KanbanEventCodec {

    // ---- encode ----

    fun encodeSnapshot(s: SessionSnapshot, drained: Boolean): String = buildString {
        append("{\"t\":\"snap\"")
        field("sid", s.sessionId); field("state", s.state); field("title", s.title)
        append(",\"patchBytes\":").append(s.patchBytes)
        field("headSha", s.headSha)
        append(",\"activeCount\":").append(s.activeCount)
        append(",\"awaitingCount\":").append(s.awaitingCount)
        append(",\"capturedAt\":").append(s.capturedAt)
        append(",\"drained\":").append(drained)
        append('}')
    }

    fun encodeCause(sid: String, c: SessionCause): String = buildString {
        append("{\"t\":\"cause\"")
        field("sid", sid)
        field("kind", c.kind())
        append(",\"at\":").append(c.at)
        c.activityId?.let { field("activityId", it) }
        c.activitySeq?.let { append(",\"activitySeq\":").append(it) }
        when (c) {
            is SessionCause.AgentMessaged -> field("excerpt", c.excerpt)
            is SessionCause.HumanAnswered -> field("message", c.message)
            is SessionCause.PatchArrived -> append(",\"bytes\":").append(c.bytes)
            is SessionCause.PatchSnapshotObserved -> {
                field("patchCid", c.patchCid.value)
                append(",\"causalOrdinal\":").append(c.causalOrdinal)
                append(",\"artifactSeq\":").append(c.artifactSeq)
                stringArray("touchedFiles", c.touchedFiles)
                stringArray("missingFromCandidate", c.missingFromCandidate)
                append(",\"reviewCandidate\":").append(c.reviewCandidate)
            }
            is SessionCause.PatchReviewSelected -> {
                field("patchCid", c.patchCid.value)
                append(",\"causalOrdinal\":").append(c.causalOrdinal)
                c.latestPatchCid?.let { field("latestPatchCid", it.value) }
                c.latestReportCid?.let { field("latestReportCid", it.value) }
                field("reviewedBy", c.reviewedBy)
                field("receiptRef", c.receiptRef)
            }
            is SessionCause.PatchRejected -> {
                field("patchCid", c.patchCid.value)
                append(",\"causalOrdinal\":").append(c.causalOrdinal)
                c.latestPatchCid?.let { field("latestPatchCid", it.value) }
                c.latestReportCid?.let { field("latestReportCid", it.value) }
                field("reason", c.reason)
                field("reviewedBy", c.reviewedBy)
                field("receiptRef", c.receiptRef)
            }
            is SessionCause.AgentReportObserved -> {
                field("reportCid", c.reportCid.value)
                append(",\"causalOrdinal\":").append(c.causalOrdinal)
                append(",\"bytes\":").append(c.bytes)
                field("apiCreateTime", c.apiCreateTime)
            }
            is SessionCause.AgentReportReviewSelected -> {
                field("reportCid", c.reportCid.value)
                append(",\"causalOrdinal\":").append(c.causalOrdinal)
                c.latestPatchCid?.let { field("latestPatchCid", it.value) }
                c.latestReportCid?.let { field("latestReportCid", it.value) }
                field("disposition", c.disposition)
                field("reviewedBy", c.reviewedBy)
                field("receiptRef", c.receiptRef)
            }
            is SessionCause.DrainApplied -> { field("commitSha", c.commitSha); append(",\"rejects\":").append(c.rejects) }
            is SessionCause.DrainFailed -> field("reason", c.reason)
            is SessionCause.PredicateFlipped -> { field("predicate", c.predicate); append(",\"nowPassing\":").append(c.nowPassing) }
            is SessionCause.SessionFailed -> field("reason", c.reason)
            is SessionCause.SessionArchived -> Unit
            is SessionCause.StateObserved -> { field("from", c.from); field("to", c.to) }
            is SessionCause.WorkQueued -> {
                field("workId", c.workId); field("tier", c.tier); field("title", c.title)
                field("spec", c.spec)
                c.parent?.let { field("parent", it) }
                append(",\"score\":").append(c.score)
            }
            is SessionCause.WorkDispatched -> {
                field("workId", c.workId); field("sessionId", c.sessionId)
                append(",\"attempt\":").append(c.attempt)
            }
            is SessionCause.WorkDrained -> {
                field("workId", c.workId); field("sessionId", c.sessionId)
                field("commitSha", c.commitSha); field("taskId", c.taskId)
                c.receipt?.let {
                    field("receiptProducer", it.producer)
                    field("receiptProducerRef", it.producerRef)
                    field("receiptPatchCid", it.patchCid.value)
                    field("receiptRevision", it.revision)
                    field("receiptVersionTag", it.versionTag)
                    field("receiptSummary", it.lexicalMemory.summary)
                    field("receiptTitle", it.lexicalMemory.title)
                    field("receiptContent", it.lexicalMemory.content)
                    append(",\"receiptClaimedAt\":").append(it.claimedAt)
                    it.prUrl?.let { url -> field("receiptPrUrl", url) }
                }
            }
            is SessionCause.WorkIdentitySynthesized -> {
                field("workId", c.workId)
                field("identityWorkId", c.identity.workId)
                field("identitySessionId", c.identity.sessionId)
                field("identitySessionUrl", c.identity.sessionUrl)
                c.identity.gitBranch?.let { field("identityGitBranch", it) }
                c.identity.prUrl?.let { field("identityPrUrl", it) }
                c.identity.gitTag?.let { field("identityGitTag", it) }
                c.identity.commitSha?.let { field("identityCommitSha", it) }
            }
        }
        append('}')
    }

    // ---- decode ----

    sealed interface KanbanEvent
    data class SnapEvent(val snapshot: SessionSnapshot, val drained: Boolean) : KanbanEvent
    data class CauseEvent(val sid: String, val cause: SessionCause) : KanbanEvent

    fun decode(record: String): KanbanEvent? {
        // Return null (not throw) when a record isn't a JSON object. decode is
        // declared KanbanEvent? and callers (load/loadQueue/buildCausalGraph)
        // have explicit null-skip branches for forward-compat. Throwing here
        // aborts the entire WAL replay on a single malformed record.
        val m = reify(record) as? Map<*, *> ?: return null
        return when (m["t"]) {
            "snap" -> SnapEvent(
                SessionSnapshot(
                    sessionId = m.str("sid"),
                    state = m.str("state"),
                    title = m.str("title"),
                    patchBytes = m.num("patchBytes"),
                    headSha = m.str("headSha"),
                    activeCount = m.num("activeCount").toInt(),
                    awaitingCount = m.num("awaitingCount").toInt(),
                    capturedAt = m.num("capturedAt"),
                ),
                drained = m["drained"]?.toString() == "true",
            )
            "cause" -> {
                val sid = m.str("sid")
                val at = m.num("at")
                val actId = m.optStr("activityId")
                val actSeq = (m["activitySeq"] as? Number)?.toInt()
                val cause: SessionCause = when (m["kind"]) {
                    "AgentMessaged" -> SessionCause.AgentMessaged(m.str("excerpt"), at, actId, actSeq)
                    "HumanAnswered" -> SessionCause.HumanAnswered(m.str("message"), at, actId, actSeq)
                    "PatchArrived" -> SessionCause.PatchArrived(m.num("bytes"), at, actId, actSeq)
                    "PatchSnapshotObserved" -> SessionCause.PatchSnapshotObserved(
                        patchCid = ContentId(m.str("patchCid")),
                        causalOrdinal = m.num("causalOrdinal").toInt(),
                        artifactSeq = m.num("artifactSeq").toInt(),
                        touchedFiles = m.strings("touchedFiles"),
                        missingFromCandidate = m.strings("missingFromCandidate"),
                        reviewCandidate = m["reviewCandidate"]?.toString() == "true",
                        at = at,
                        activityId = actId.orEmpty(),
                        activitySeq = actSeq ?: -1,
                    )
                    "PatchReviewSelected" -> SessionCause.PatchReviewSelected(
                        patchCid = ContentId(m.str("patchCid")),
                        causalOrdinal = m.num("causalOrdinal").toInt(),
                        latestPatchCid = m.optStr("latestPatchCid")?.let(::ContentId),
                        latestReportCid = m.optStr("latestReportCid")?.let(::ContentId),
                        reviewedBy = m.str("reviewedBy"),
                        receiptRef = m.str("receiptRef"),
                        at = at,
                    )
                    "PatchRejected" -> SessionCause.PatchRejected(
                        patchCid = ContentId(m.str("patchCid")),
                        causalOrdinal = m.num("causalOrdinal").toInt(),
                        latestPatchCid = m.optStr("latestPatchCid")?.let(::ContentId),
                        latestReportCid = m.optStr("latestReportCid")?.let(::ContentId),
                        reason = m.str("reason"),
                        reviewedBy = m.str("reviewedBy"),
                        receiptRef = m.str("receiptRef"),
                        at = at,
                    )
                    "AgentReportObserved" -> SessionCause.AgentReportObserved(
                        reportCid = ContentId(m.str("reportCid")),
                        causalOrdinal = m.num("causalOrdinal").toInt(),
                        bytes = m.num("bytes"),
                        apiCreateTime = m.str("apiCreateTime"),
                        at = at,
                        activityId = actId.orEmpty(),
                        activitySeq = actSeq ?: -1,
                    )
                    "AgentReportReviewSelected" -> SessionCause.AgentReportReviewSelected(
                        reportCid = ContentId(m.str("reportCid")),
                        causalOrdinal = m.num("causalOrdinal").toInt(),
                        latestPatchCid = m.optStr("latestPatchCid")?.let(::ContentId),
                        latestReportCid = m.optStr("latestReportCid")?.let(::ContentId),
                        disposition = m.str("disposition"),
                        reviewedBy = m.str("reviewedBy"),
                        receiptRef = m.str("receiptRef"),
                        at = at,
                    )
                    "DrainApplied" -> SessionCause.DrainApplied(m.str("commitSha"), m.num("rejects").toInt(), at)
                    "DrainFailed" -> SessionCause.DrainFailed(m.str("reason"), at)
                    "PredicateFlipped" -> SessionCause.PredicateFlipped(m.str("predicate"), m["nowPassing"]?.toString() == "true", at)
                    "SessionFailed" -> SessionCause.SessionFailed(m.str("reason"), at)
                    "SessionArchived" -> SessionCause.SessionArchived(at)
                    "StateObserved" -> SessionCause.StateObserved(m.str("from"), m.str("to"), at)
                    "WorkQueued" -> SessionCause.WorkQueued(
                        workId = m.str("workId"),
                        tier = m.str("tier"),
                        title = m.str("title"),
                        spec = m.str("spec"),
                        parent = m.optStr("parent"),
                        score = m["score"]?.toString()?.toDoubleOrNull() ?: 0.5,
                        at = at,
                    )
                    "WorkDispatched" -> SessionCause.WorkDispatched(
                        workId = m.str("workId"),
                        sessionId = m.str("sessionId"),
                        attempt = m.num("attempt").toInt(),
                        at = at,
                    )
                    "WorkDrained" -> SessionCause.WorkDrained(
                        workId = m.str("workId"),
                        sessionId = m.str("sessionId"),
                        commitSha = m.str("commitSha"),
                        taskId = m.str("taskId"),
                        receipt = m.optStr("receiptPatchCid")?.let { cid ->
                            MergeReceipt(
                                workId = m.str("workId"),
                                producer = m.str("receiptProducer"),
                                producerRef = m.str("receiptProducerRef"),
                                patchCid = ContentId(cid),
                                revision = m.str("receiptRevision"),
                                versionTag = m.str("receiptVersionTag"),
                                lexicalMemory = LexicalMemory(
                                    summary = m.str("receiptSummary"),
                                    title = m.str("receiptTitle"),
                                    content = m.str("receiptContent"),
                                ),
                                claimedAt = m.num("receiptClaimedAt"),
                                prUrl = m.optStr("receiptPrUrl"),
                            )
                        },
                        at = at,
                    )
                    "WorkIdentitySynthesized" -> SessionCause.WorkIdentitySynthesized(
                        workId = m.str("workId"),
                        identity = borg.trikeshed.kanban.WorkIdentity(
                            workId = m.optStr("identityWorkId") ?: m.str("workId"),
                            sessionId = m.optStr("identitySessionId")
                                ?: m.optStr("identity")
                                ?: sid.substringAfter("session:", sid),
                            sessionUrl = m.optStr("identitySessionUrl")
                                ?: "https://jules.google.com/session/${
                                    m.optStr("identitySessionId")
                                        ?: m.optStr("identity")
                                        ?: sid.substringAfter("session:", sid)
                                }",
                            gitBranch = m.optStr("identityGitBranch"),
                            prUrl = m.optStr("identityPrUrl"),
                            gitTag = m.optStr("identityGitTag"),
                            commitSha = m.optStr("identityCommitSha"),
                        ),
                        at = at,
                    )
                    else -> return null // tolerant decode: skip-unknown-kind
                }
                CauseEvent(sid, cause)
            }
            else -> return null
        }
    }

    private fun SessionCause.kind(): String = when (this) {
        is SessionCause.AgentMessaged -> "AgentMessaged"
        is SessionCause.HumanAnswered -> "HumanAnswered"
        is SessionCause.PatchArrived -> "PatchArrived"
        is SessionCause.PatchSnapshotObserved -> "PatchSnapshotObserved"
        is SessionCause.PatchReviewSelected -> "PatchReviewSelected"
        is SessionCause.PatchRejected -> "PatchRejected"
        is SessionCause.AgentReportObserved -> "AgentReportObserved"
        is SessionCause.AgentReportReviewSelected -> "AgentReportReviewSelected"
        is SessionCause.DrainApplied -> "DrainApplied"
        is SessionCause.DrainFailed -> "DrainFailed"
        is SessionCause.PredicateFlipped -> "PredicateFlipped"
        is SessionCause.SessionFailed -> "SessionFailed"
        is SessionCause.SessionArchived -> "SessionArchived"
        is SessionCause.StateObserved -> "StateObserved"
        is SessionCause.WorkQueued -> "WorkQueued"
        is SessionCause.WorkDispatched -> "WorkDispatched"
        is SessionCause.WorkDrained -> "WorkDrained"
        is SessionCause.WorkIdentitySynthesized -> "WorkIdentitySynthesized"
    }

    // NO second unescape pass here. `reify` (confix `decodeTextSpan`)
    // already decodes string content, so re-decoding it ate one layer of
    // backslashes: a title containing `\"` came back as `"`, and `\\` collapsed
    // to `\`. The escapes people notice — \n and \t — looked fine either way,
    // which is why it survived.
    // KanbanEventCodecEscapeTest round-trips every escape the task named.
    private fun Map<*, *>.str(k: String): String = this[k]?.toString() ?: ""
    private fun Map<*, *>.optStr(k: String): String? = this[k]?.toString()
    private fun Map<*, *>.num(k: String): Long = (this[k] as? Number)?.toLong() ?: 0L
    private fun Map<*, *>.strings(k: String): List<String> =
        (this[k] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

    private fun StringBuilder.field(k: String, v: String) {
        append(",\"").append(k).append("\":")
        append('"')
        for (ch in v) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
        append('"')
    }

    private fun StringBuilder.stringArray(k: String, values: List<String>) {
        append(",\"").append(k).append("\":[")
        values.forEachIndexed { index, value ->
            if (index != 0) append(',')
            append('"')
            for (ch in value) when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
            append('"')
        }
        append(']')
    }
}

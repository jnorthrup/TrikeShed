package borg.trikeshed.utils.kanban

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.jules.JulesSnapshot
import borg.trikeshed.parse.json.JsonSupport
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
 * writer and reader; [JsonSupport] (CharSeries parser, no kotlinx) reads back.
 */
object KanbanEventCodec {

    // ---- encode ----

    fun encodeSnapshot(s: JulesSnapshot, drained: Boolean): String = buildString {
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

    fun encodeCause(sid: String, c: JulesCause): String = buildString {
        append("{\"t\":\"cause\"")
        field("sid", sid)
        field("kind", c.kind())
        append(",\"at\":").append(c.at)
        c.activityId?.let { field("activityId", it) }
        c.activitySeq?.let { append(",\"activitySeq\":").append(it) }
        when (c) {
            is JulesCause.AgentMessaged -> field("excerpt", c.excerpt)
            is JulesCause.HumanAnswered -> field("message", c.message)
            is JulesCause.PatchArrived -> append(",\"bytes\":").append(c.bytes)
            is JulesCause.PatchSnapshotObserved -> {
                field("patchCid", c.patchCid.value)
                append(",\"causalOrdinal\":").append(c.causalOrdinal)
                append(",\"artifactSeq\":").append(c.artifactSeq)
                stringArray("touchedFiles", c.touchedFiles)
                stringArray("missingFromCandidate", c.missingFromCandidate)
                append(",\"reviewCandidate\":").append(c.reviewCandidate)
            }
            is JulesCause.PatchReviewSelected -> {
                field("patchCid", c.patchCid.value)
                append(",\"causalOrdinal\":").append(c.causalOrdinal)
                c.latestPatchCid?.let { field("latestPatchCid", it.value) }
                c.latestReportCid?.let { field("latestReportCid", it.value) }
                field("reviewedBy", c.reviewedBy)
                field("receiptRef", c.receiptRef)
            }
            is JulesCause.PatchRejected -> {
                field("patchCid", c.patchCid.value)
                append(",\"causalOrdinal\":").append(c.causalOrdinal)
                c.latestPatchCid?.let { field("latestPatchCid", it.value) }
                c.latestReportCid?.let { field("latestReportCid", it.value) }
                field("reason", c.reason)
                field("reviewedBy", c.reviewedBy)
                field("receiptRef", c.receiptRef)
            }
            is JulesCause.AgentReportObserved -> {
                field("reportCid", c.reportCid.value)
                append(",\"causalOrdinal\":").append(c.causalOrdinal)
                append(",\"bytes\":").append(c.bytes)
                field("apiCreateTime", c.apiCreateTime)
            }
            is JulesCause.AgentReportReviewSelected -> {
                field("reportCid", c.reportCid.value)
                append(",\"causalOrdinal\":").append(c.causalOrdinal)
                c.latestPatchCid?.let { field("latestPatchCid", it.value) }
                c.latestReportCid?.let { field("latestReportCid", it.value) }
                field("disposition", c.disposition)
                field("reviewedBy", c.reviewedBy)
                field("receiptRef", c.receiptRef)
            }
            is JulesCause.DrainApplied -> { field("commitSha", c.commitSha); append(",\"rejects\":").append(c.rejects) }
            is JulesCause.DrainFailed -> field("reason", c.reason)
            is JulesCause.PredicateFlipped -> { field("predicate", c.predicate); append(",\"nowPassing\":").append(c.nowPassing) }
            is JulesCause.SessionFailed -> field("reason", c.reason)
            is JulesCause.SessionArchived -> Unit
            is JulesCause.StateObserved -> { field("from", c.from); field("to", c.to) }
            is JulesCause.WorkQueued -> {
                field("workId", c.workId); field("tier", c.tier); field("title", c.title)
                field("spec", c.spec)
                c.parent?.let { field("parent", it) }
                append(",\"score\":").append(c.score)
            }
            is JulesCause.WorkDispatched -> {
                field("workId", c.workId); field("sessionId", c.sessionId)
                append(",\"attempt\":").append(c.attempt)
            }
            is JulesCause.WorkDrained -> {
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
            is JulesCause.WorkIdentitySynthesized -> {
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
    data class SnapEvent(val snapshot: JulesSnapshot, val drained: Boolean) : KanbanEvent
    data class CauseEvent(val sid: String, val cause: JulesCause) : KanbanEvent

    fun decode(record: String): KanbanEvent? {
        // Return null (not throw) when a record isn't a JSON object. decode is
        // declared KanbanEvent? and callers (load/loadQueue/buildCausalGraph)
        // have explicit null-skip branches for forward-compat. Throwing here
        // aborts the entire WAL replay on a single malformed record.
        val m = JsonSupport.parse(record) as? Map<*, *> ?: return null
        return when (m["t"]) {
            "snap" -> SnapEvent(
                JulesSnapshot(
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
                val cause: JulesCause = when (m["kind"]) {
                    "AgentMessaged" -> JulesCause.AgentMessaged(m.str("excerpt"), at, actId, actSeq)
                    "HumanAnswered" -> JulesCause.HumanAnswered(m.str("message"), at, actId, actSeq)
                    "PatchArrived" -> JulesCause.PatchArrived(m.num("bytes"), at, actId, actSeq)
                    "PatchSnapshotObserved" -> JulesCause.PatchSnapshotObserved(
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
                    "PatchReviewSelected" -> JulesCause.PatchReviewSelected(
                        patchCid = ContentId(m.str("patchCid")),
                        causalOrdinal = m.num("causalOrdinal").toInt(),
                        latestPatchCid = m.optStr("latestPatchCid")?.let(::ContentId),
                        latestReportCid = m.optStr("latestReportCid")?.let(::ContentId),
                        reviewedBy = m.str("reviewedBy"),
                        receiptRef = m.str("receiptRef"),
                        at = at,
                    )
                    "PatchRejected" -> JulesCause.PatchRejected(
                        patchCid = ContentId(m.str("patchCid")),
                        causalOrdinal = m.num("causalOrdinal").toInt(),
                        latestPatchCid = m.optStr("latestPatchCid")?.let(::ContentId),
                        latestReportCid = m.optStr("latestReportCid")?.let(::ContentId),
                        reason = m.str("reason"),
                        reviewedBy = m.str("reviewedBy"),
                        receiptRef = m.str("receiptRef"),
                        at = at,
                    )
                    "AgentReportObserved" -> JulesCause.AgentReportObserved(
                        reportCid = ContentId(m.str("reportCid")),
                        causalOrdinal = m.num("causalOrdinal").toInt(),
                        bytes = m.num("bytes"),
                        apiCreateTime = m.str("apiCreateTime"),
                        at = at,
                        activityId = actId.orEmpty(),
                        activitySeq = actSeq ?: -1,
                    )
                    "AgentReportReviewSelected" -> JulesCause.AgentReportReviewSelected(
                        reportCid = ContentId(m.str("reportCid")),
                        causalOrdinal = m.num("causalOrdinal").toInt(),
                        latestPatchCid = m.optStr("latestPatchCid")?.let(::ContentId),
                        latestReportCid = m.optStr("latestReportCid")?.let(::ContentId),
                        disposition = m.str("disposition"),
                        reviewedBy = m.str("reviewedBy"),
                        receiptRef = m.str("receiptRef"),
                        at = at,
                    )
                    "DrainApplied" -> JulesCause.DrainApplied(m.str("commitSha"), m.num("rejects").toInt(), at)
                    "DrainFailed" -> JulesCause.DrainFailed(m.str("reason"), at)
                    "PredicateFlipped" -> JulesCause.PredicateFlipped(m.str("predicate"), m["nowPassing"]?.toString() == "true", at)
                    "SessionFailed" -> JulesCause.SessionFailed(m.str("reason"), at)
                    "SessionArchived" -> JulesCause.SessionArchived(at)
                    "StateObserved" -> JulesCause.StateObserved(m.str("from"), m.str("to"), at)
                    "WorkQueued" -> JulesCause.WorkQueued(
                        workId = m.str("workId"),
                        tier = m.str("tier"),
                        title = m.str("title"),
                        spec = m.str("spec"),
                        parent = m.optStr("parent"),
                        score = m["score"]?.toString()?.toDoubleOrNull() ?: 0.5,
                        at = at,
                    )
                    "WorkDispatched" -> JulesCause.WorkDispatched(
                        workId = m.str("workId"),
                        sessionId = m.str("sessionId"),
                        attempt = m.num("attempt").toInt(),
                        at = at,
                    )
                    "WorkDrained" -> JulesCause.WorkDrained(
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
                    "WorkIdentitySynthesized" -> JulesCause.WorkIdentitySynthesized(
                        workId = m.str("workId"),
                        identity = borg.trikeshed.jules.WorkIdentity(
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

    private fun JulesCause.kind(): String = when (this) {
        is JulesCause.AgentMessaged -> "AgentMessaged"
        is JulesCause.HumanAnswered -> "HumanAnswered"
        is JulesCause.PatchArrived -> "PatchArrived"
        is JulesCause.PatchSnapshotObserved -> "PatchSnapshotObserved"
        is JulesCause.PatchReviewSelected -> "PatchReviewSelected"
        is JulesCause.PatchRejected -> "PatchRejected"
        is JulesCause.AgentReportObserved -> "AgentReportObserved"
        is JulesCause.AgentReportReviewSelected -> "AgentReportReviewSelected"
        is JulesCause.DrainApplied -> "DrainApplied"
        is JulesCause.DrainFailed -> "DrainFailed"
        is JulesCause.PredicateFlipped -> "PredicateFlipped"
        is JulesCause.SessionFailed -> "SessionFailed"
        is JulesCause.SessionArchived -> "SessionArchived"
        is JulesCause.StateObserved -> "StateObserved"
        is JulesCause.WorkQueued -> "WorkQueued"
        is JulesCause.WorkDispatched -> "WorkDispatched"
        is JulesCause.WorkDrained -> "WorkDrained"
        is JulesCause.WorkIdentitySynthesized -> "WorkIdentitySynthesized"
    }

    private fun Map<*, *>.str(k: String): String = this[k]?.toString()?.let { unescape(it) } ?: ""
    private fun Map<*, *>.optStr(k: String): String? = this[k]?.toString()?.let { unescape(it) }
    private fun Map<*, *>.num(k: String): Long = (this[k] as? Number)?.toLong() ?: 0L
    private fun Map<*, *>.strings(k: String): List<String> =
        (this[k] as? List<*>)?.mapNotNull { it?.toString()?.let(::unescape) } ?: emptyList()

    private fun unescape(v: String): String =
        if (!v.contains('\\')) v else borg.trikeshed.util.jsonUnescape(v)

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

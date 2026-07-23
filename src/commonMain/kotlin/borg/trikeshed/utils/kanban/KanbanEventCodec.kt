/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
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
            is JulesCause.DrainApplied -> { field("commitSha", c.commitSha); append(",\"rejects\":").append(c.rejects) }
            is JulesCause.DrainFailed -> field("reason", c.reason)
            is JulesCause.PredicateFlipped -> { field("predicate", c.predicate); append(",\"nowPassing\":").append(c.nowPassing) }
            is JulesCause.SessionFailed -> field("reason", c.reason)
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
                }
            }
        }
        append('}')
    }

    // ---- decode ----

    sealed interface KanbanEvent
    data class SnapEvent(val snapshot: JulesSnapshot, val drained: Boolean) : KanbanEvent
    data class CauseEvent(val sid: String, val cause: JulesCause) : KanbanEvent

    fun decode(record: String): KanbanEvent? {
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
                val actId = m["activityId"]?.toString()
                val actSeq = (m["activitySeq"] as? Number)?.toInt()
                val cause: JulesCause = when (m["kind"]) {
                    "AgentMessaged" -> JulesCause.AgentMessaged(m.str("excerpt"), at, actId, actSeq)
                    "HumanAnswered" -> JulesCause.HumanAnswered(m.str("message"), at, actId, actSeq)
                    "PatchArrived" -> JulesCause.PatchArrived(m.num("bytes"), at, actId, actSeq)
                    "DrainApplied" -> JulesCause.DrainApplied(m.str("commitSha"), m.num("rejects").toInt(), at)
                    "DrainFailed" -> JulesCause.DrainFailed(m.str("reason"), at)
                    "PredicateFlipped" -> JulesCause.PredicateFlipped(m.str("predicate"), m["nowPassing"]?.toString() == "true", at)
                    "SessionFailed" -> JulesCause.SessionFailed(m.str("reason"), at)
                    "WorkQueued" -> JulesCause.WorkQueued(
                        workId = m.str("workId"),
                        tier = m.str("tier"),
                        title = m.str("title"),
                        spec = m.str("spec"),
                        parent = m["parent"]?.toString(),
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
                        receipt = m["receiptPatchCid"]?.toString()?.let { cid ->
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
                            )
                        },
                        at = at,
                    )
                    else -> JulesCause.StateObserved(m.str("from"), m.str("to"), at)
                }
                CauseEvent(sid, cause)
            }
            else -> null
        }
    }

    private fun JulesCause.kind(): String = when (this) {
        is JulesCause.AgentMessaged -> "AgentMessaged"
        is JulesCause.HumanAnswered -> "HumanAnswered"
        is JulesCause.PatchArrived -> "PatchArrived"
        is JulesCause.DrainApplied -> "DrainApplied"
        is JulesCause.DrainFailed -> "DrainFailed"
        is JulesCause.PredicateFlipped -> "PredicateFlipped"
        is JulesCause.SessionFailed -> "SessionFailed"
        is JulesCause.StateObserved -> "StateObserved"
        is JulesCause.WorkQueued -> "WorkQueued"
        is JulesCause.WorkDispatched -> "WorkDispatched"
        is JulesCause.WorkDrained -> "WorkDrained"
    }

    private fun Map<*, *>.str(k: String): String = this[k]?.toString() ?: ""
    private fun Map<*, *>.num(k: String): Long = (this[k] as? Number)?.toLong() ?: 0L

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
}

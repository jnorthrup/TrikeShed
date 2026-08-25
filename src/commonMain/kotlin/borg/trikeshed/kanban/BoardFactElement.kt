package borg.trikeshed.kanban

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobCommand

/**
 * BoardFactElement — the committed board → Rete working-memory bridge, with
 * RETRACTION CORRECTNESS first (the industry's #1 sinkhole): a Retract or an
 * ARCHIVED landing retracts the card fact, which already un-fires derived
 * state (agenda.removeBySupport + refraction.invalidateBySupport).
 *
 * The clock arrives as a FACT: one `{kind:"now", ms}` fact per partition,
 * modified on a coarse tick — temporal expiry via ordinary modify/refraction,
 * ZERO temporal machinery inside Rete (production lesson: the tick-fact IS the
 * mechanism).
 */
class BoardFactElement(
    private val rete: ReteNetwork,
    val partitionId: String = "board",
) {
    private val known = mutableSetOf<String>()
    private val board = BlackboardContext(partitionId)

    suspend fun onCommitted(ev: BoardCommitted) {
        val jobId = ev.jobId
        val factId = FactId(partitionId, jobId)
        if (ev.command is JobCommand.Retract || ev.command is JobCommand.Cancel || ev.col == BoardCol.ARCHIVED) {
            if (known.remove(jobId)) rete.retract(factId)
            return
        }
        val fields = mapOf(
            "kind" to "card",
            "jobId" to jobId,
            "lifecycle" to ev.snapshot.lifecycle,
            "column" to ev.col.wire,
            "dependencies" to ev.snapshot.dependencies.map { it.value },
            "revision" to ev.snapshot.revision,
            "lastSequence" to ev.sequence,
            "lastMoveMs" to ev.lastMoveMs,
        )
        if (known.add(jobId)) rete.assert(factId, fields, ev.cid, board)
        else rete.modify(factId, fields, ev.cid)
    }

    /** Coarse clock pulse: modify the single now-fact; stall evaluation follows through ordinary matching. */
    suspend fun tick(nowMs: Long) {
        val factId = FactId(partitionId, "now")
        val fields = mapOf("kind" to "now", "ms" to nowMs)
        val cid = ContentId.of("board-now-$nowMs".encodeToByteArray())
        if (known.add("now")) rete.assert(factId, fields, cid, board)
        else rete.modify(factId, fields, cid)
    }

    /** Detach hygiene: take the board's facts out of working memory with proper retraction. */
    suspend fun retractAll() {
        for (id in known.toList()) rete.retract(FactId(partitionId, id))
        known.clear()
    }
}

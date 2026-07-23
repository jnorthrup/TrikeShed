package borg.trikeshed.utils.kanban

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.jules.JulesSessionCard
import borg.trikeshed.jules.JulesSnapshot
import borg.trikeshed.util.oroboros.OroborosCoordinator
import borg.trikeshed.util.oroboros.Mutation
import borg.trikeshed.reduction.TrajectoryVerdict
import borg.trikeshed.reduction.verdictFor
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get

/** Queue entry projected from the unified WAL. */
data class QueueEntry(
    val workId: String,
    val tier: String,
    val title: String,
    val spec: String,
    val parent: String? = null,
    val score: Double = 0.5,
    val queuedAt: Long = 0L,
    val sessionId: String? = null,
    val attempt: Int = 0,
    val dispatchedAt: Long? = null,
    val commitSha: String? = null,
    val taskId: String? = null,
    val drainedAt: Long? = null,
) {
    val isDispatched: Boolean get() = sessionId != null
    val isDrained: Boolean get() = drainedAt != null
}

/**
 * Common abstraction for the Unified Causal WAL.
 * This class combines board and queue projections and is wired to OROBOROS submit().
 */
class UnifiedCausalWAL(
    private val coordinator: OroborosCoordinator,
    private val path: String,
    private val fileOps: FileOperations
) {

    suspend fun append(snapshot: JulesSnapshot, drained: Boolean, cause: JulesCause?) {
        val payload = KanbanEventCodec.encodeSnapshot(snapshot, drained).encodeToByteArray()
        coordinator.submit(Mutation.Upsert("$path/${snapshot.sessionId}_snap", payload, "application/json"))

        if (cause != null) {
            val causePayload = KanbanEventCodec.encodeCause(snapshot.sessionId, cause).encodeToByteArray()
            coordinator.submit(Mutation.Upsert("$path/${snapshot.sessionId}_cause_${cause.at}", causePayload, "application/json"))
        }
    }

    suspend fun appendWork(workId: String, cause: JulesCause) {
        val payload = KanbanEventCodec.encodeCause(workId, cause).encodeToByteArray()
        coordinator.submit(Mutation.Upsert("$path/${workId}_cause_${cause.at}", payload, "application/json"))
    }

    fun computeVerdict(causes: Series<JulesCause>, fingerprint: String, attemptCount: Int, deps: Series<String>): TrajectoryVerdict {
        val listCauses = mutableListOf<JulesCause>()
        for (i in 0 until causes.size) { listCauses.add(causes[i]) }
        val listDeps = mutableListOf<String>()
        for (i in 0 until deps.size) { listDeps.add(deps[i]) }
        return verdictFor(listCauses, fingerprint, attemptCount, listDeps)
    }

    fun replay(): Sequence<Pair<String, ByteArray>> = sequence {
        if (!fileOps.exists(path) || !fileOps.isDir(path)) return@sequence

        val files = fileOps.listDir(path).sorted()
        for (f in files) {
            val bytes = fileOps.readAllBytes("$path/$f")
            val key = f.substringBefore("_")
            yield(key to bytes)
        }
    }

    fun replayCauses(workId: String): Series<JulesCause> {
        val causes = mutableListOf<JulesCause>()
        for ((key, payload) in replay()) {
            if (key == workId) {
                val decoded = KanbanEventCodec.decode(payload.decodeToString())
                if (decoded is KanbanEventCodec.CauseEvent) {
                    causes.add(decoded.cause)
                }
            }
        }
        return causes.size j { causes[it] }
    }

    fun load(): MutableMap<String, JulesSessionCard> {
        val snapshots = mutableMapOf<String, KanbanEventCodec.SnapEvent>()
        val causes = mutableMapOf<String, MutableList<JulesCause>>()
        for ((sid, payload) in replay()) {
            when (val ev = KanbanEventCodec.decode(payload.decodeToString())) {
                is KanbanEventCodec.SnapEvent -> snapshots[sid] = ev
                is KanbanEventCodec.CauseEvent -> causes.getOrPut(ev.sid) { mutableListOf() }.add(ev.cause)
                null -> {}
            }
        }
        val board = mutableMapOf<String, JulesSessionCard>()
        for ((sid, snap) in snapshots) {
            val card = JulesSessionCard.capture(snap.snapshot)
            board[sid] = card.copy(
                drained = snap.drained,
                causes = causes[sid] ?: card.causes,
            )
        }
        return board
    }

    fun loadQueue(): Series<QueueEntry> {
        val byWorkId = mutableMapOf<String, QueueEntry>()
        for ((workId, payload) in replay()) {
            val ev = KanbanEventCodec.decode(payload.decodeToString()) as? KanbanEventCodec.CauseEvent ?: continue
            val c = ev.cause
            when (c) {
                is JulesCause.WorkQueued -> byWorkId.getOrPut(c.workId) {
                    QueueEntry(
                        workId = c.workId,
                        tier = c.tier,
                        title = c.title,
                        spec = c.spec,
                        parent = c.parent,
                        score = c.score,
                        queuedAt = c.at,
                    )
                }
                is JulesCause.WorkDispatched -> byWorkId[c.workId]?.let {
                    byWorkId[c.workId] = it.copy(sessionId = c.sessionId, attempt = c.attempt, dispatchedAt = c.at)
                }
                is JulesCause.WorkDrained -> byWorkId[c.workId]?.let {
                    byWorkId[c.workId] = it.copy(
                        commitSha = c.commitSha,
                        taskId = c.taskId,
                        drainedAt = c.at,
                    )
                }
                else -> {}
            }
        }
        val list = byWorkId.values.toList()
        return list.size j { list[it] }
    }
}

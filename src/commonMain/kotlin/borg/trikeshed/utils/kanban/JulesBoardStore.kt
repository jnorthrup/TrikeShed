package borg.trikeshed.utils.kanban

import borg.trikeshed.lib.j

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.jules.JulesSessionCard
import borg.trikeshed.jules.JulesSnapshot
import borg.trikeshed.lib.AppendWal
import kotlinx.datetime.Clock

/**
 * Durable board store: the Kanban's causal truth on disk.
 *
 * Every card mutation appends Confix records to [wal] — one snapshot
 * record (the card's assumpsis) plus one cause record (what provoked it).
 * Replay folds the log back into cards: latest snapshot per session wins,
 * causes accumulate in append order.
 *
 * Lives at ~/.local/forge/jules-board.wal — the TrikeShed state default.
 * The ISAM snapshot spool (high-volume telemetry side of the quandary) lands
 * here later as a sibling file when poll volume demands it.
 *
 * @param wal    the append-only WAL — [borg.trikeshed.lib.AppendWal] SPI.
 *                 JVM: JvmAppendWal (Panama mmap); JS: in-memory; Native: posix mmap.
 */
class JulesBoardStore(
    private val wal: AppendWal,
) {

    companion object {
        /** Canonical WAL filename under the forge home directory. */
        const val WAL_FILENAME = "jules-board.wal"
        private const val DRAIN_BATCH_KEY = "__jules_drain_batch__"
        private const val DRAIN_BATCH_MAGIC = 0x4A444231 // JDB1
    }

    /**
     * Persist a card mutation: new snapshot + the cause of the change.
     * Both records are appended under the sessionId key so replay folds correctly.
     */
    suspend fun append(snapshot: JulesSnapshot, drained: Boolean, cause: JulesCause?) {
        val snapshotRecord = KanbanEventCodec.encodeSnapshot(snapshot, drained).encodeToByteArray()
        if (cause == null) {
            wal.append(snapshot.sessionId, snapshotRecord)
            return
        }
        appendLogicalBatch(listOf(
            snapshot.sessionId to snapshotRecord,
            snapshot.sessionId to KanbanEventCodec.encodeCause(snapshot.sessionId, cause).encodeToByteArray(),
        ))
    }

    /**
     * Append an immutable causal fact without duplicating the card snapshot.
     * Patch-continuity observations use this path: their bytes already live in
     * CAS and the WAL carries only the session/ordinal/hash ordering bond.
     */
    suspend fun appendCause(sessionId: String, cause: JulesCause) {
        wal.append(
            sessionId,
            KanbanEventCodec.encodeCause(sessionId, cause).encodeToByteArray(),
        )
    }

    /**
     * Atomically append every drained card in one binary WAL record. The
     * payload contains length-prefixed snapshot/cause records and projects
     * through [records] as if each nested record had been appended separately.
     */
    suspend fun appendDrainBatch(cards: List<JulesSessionCard>) {
        if (cards.isEmpty()) return
        val nested = mutableListOf<Pair<String, ByteArray>>()
        for (card in cards) {
            val sid = card.snapshot.sessionId
            val cause = requireNotNull(card.causes.lastOrNull()) {
                "drained card ${card.snapshot.sessionId} has no cause"
            }
            nested += sid to KanbanEventCodec.encodeSnapshot(card.snapshot, drained = true).encodeToByteArray()
            nested += sid to KanbanEventCodec.encodeCause(card.snapshot.sessionId, cause).encodeToByteArray()
        }
        appendLogicalBatch(nested)
    }

    /** One physical WAL frame for one logical state transition. */
    private suspend fun appendLogicalBatch(records: List<Pair<String, ByteArray>>) {
        val nested = records.map { (key, payload) -> key.encodeToByteArray() to payload }
        val size = nested.fold(8L) { total, (key, payload) ->
            total + 4L + key.size + 4L + payload.size
        }
        require(size <= Int.MAX_VALUE) { "drain batch exceeds WAL record limit: $size bytes" }
        val bytes = ByteArray(size.toInt())
        var offset = bytes.putInt(0, DRAIN_BATCH_MAGIC)
        offset = bytes.putInt(offset, nested.size)
        for ((key, payload) in nested) {
            offset = bytes.putInt(offset, key.size)
            key.copyInto(bytes, offset)
            offset += key.size
            offset = bytes.putInt(offset, payload.size)
            payload.copyInto(bytes, offset)
            offset += payload.size
        }
        wal.append(DRAIN_BATCH_KEY, bytes)
    }

    /**
     * Append a work-queue cause (WorkQueued/WorkDispatched/WorkDrained) under the
     * workId as the WAL key. Idempotent on (workId, kind): a second WorkQueued for
     * the same workId is a no-op (dedup at dispatch time).
     */
    suspend fun appendWork(workId: String, cause: JulesCause) {
        wal.append(workId, KanbanEventCodec.encodeCause(workId, cause).encodeToByteArray())
    }

    /**
     * Replay causes for a specific workId — used by the trajectory reducer
     * to compute the dispatch verdict without loading the full board.
     */
    fun replayCauses(workId: String): List<JulesCause> {
        val causes = mutableListOf<JulesCause>()
        for ((key, payload) in records()) {
            if (key == workId) {
                val decoded = KanbanEventCodec.decode(payload.decodeToString())
                if (decoded is KanbanEventCodec.CauseEvent) {
                    require(key == decoded.sid) { "WAL key/cause mismatch: $key != ${decoded.sid}" }
                    causes.add(decoded.cause)
                }
            }
        }
        return causes
    }

    /** Replay all logical records in insertion order, expanding drain batches. */
    fun replayAll(): Sequence<Pair<String, ByteArray>> = records()

    /**
     * Fold the WAL into cards. Card state is a projection; the WAL is truth.
     * Returns the frozen board keyed by sessionId — a projection, not a
     * second mutable copy of the truth.
     */
    fun load(): Map<String, JulesSessionCard> {
        val snapshots = mutableMapOf<String, KanbanEventCodec.SnapEvent>()
        val causes = mutableMapOf<String, MutableList<JulesCause>>()
        for ((sid, payload) in records()) {
            when (val ev = KanbanEventCodec.decode(payload.decodeToString())) {
                is KanbanEventCodec.SnapEvent -> {
                    require(sid == ev.snapshot.sessionId) { "WAL key/session mismatch: $sid" }
                    snapshots[sid] = ev
                }
                is KanbanEventCodec.CauseEvent -> {
                    require(sid == ev.sid) { "WAL key/cause mismatch: $sid != ${ev.sid}" }
                    causes.getOrPut(ev.sid) { mutableListOf() }.add(ev.cause)
                }
                null -> {} // forward-compat: skip unknown record shapes
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

    /**
     * Fold the work-cause records into a queue projection keyed by workId.
     *
     * State per workId is derived: latest WorkQueued wins, WorkDispatched attaches
     * a sessionId, WorkDrained marks drained. This is the unified queue — dispatch
     * reads from here, not from a separate state.json.
     *
     * Priority is [QueueEntry.score] descending — caller sorts before dispatch.
     * Idempotent: same workId seen twice → first entry wins (getOrPut).
     */
    fun buildCausalGraph(): borg.trikeshed.causal.CausalGraph {
        val builder = borg.trikeshed.causal.CausalGraphBuilder()
        for ((recordKey, payload) in records()) {
            val ev = KanbanEventCodec.decode(payload.decodeToString()) as? KanbanEventCodec.CauseEvent ?: continue
            require(recordKey == ev.sid) { "WAL key/cause mismatch: $recordKey != ${ev.sid}" }
            val c = ev.cause
            val epochMs = c.at
            when (c) {
                is JulesCause.WorkQueued -> builder.append(
                    workId = c.workId,
                    epochMs = epochMs,
                    edgeKind = borg.trikeshed.causal.CausalEdgeKind.Inducted,
                    payload = borg.trikeshed.causal.EventPayload.Queued(
                        tier = c.tier, title = c.title, spec = c.spec,
                        score = c.score, lexicalMemory = borg.trikeshed.util.oroboros.LexicalMemory(summary = c.title, title = c.title, content = ""), parentWorkId = c.parent
                    )
                )
                is JulesCause.WorkDispatched -> builder.append(
                    workId = c.workId,
                    epochMs = epochMs,
                    edgeKind = borg.trikeshed.causal.CausalEdgeKind.Dispatched,
                    payload = borg.trikeshed.causal.EventPayload.Dispatched(
                        sessionId = c.sessionId, attempt = c.attempt
                    )
                )
                is JulesCause.PatchArrived -> {
                    val wid = c.workId
                    if (wid != null) {
                        builder.append(
                            workId = wid,
                            epochMs = epochMs,
                            edgeKind = borg.trikeshed.causal.CausalEdgeKind.Delivered,
                            payload = borg.trikeshed.causal.EventPayload.Delivered(
                                patchCid = borg.trikeshed.job.ContentId("todo"),
                                touchedFiles = 0 j { _: Int -> "" }
                            )
                        )
                    }
                }
                is JulesCause.WorkDrained -> builder.append(
                    workId = c.workId,
                    epochMs = epochMs,
                    edgeKind = borg.trikeshed.causal.CausalEdgeKind.Settled,
                    payload = borg.trikeshed.causal.EventPayload.Settled(
                        commitSha = c.commitSha, versionTag = "unknown",
                        receiptCid = c.receipt?.patchCid ?: borg.trikeshed.job.ContentId(""),
                        lexicalMemory = c.receipt?.lexicalMemory ?: borg.trikeshed.util.oroboros.LexicalMemory("","",""),
                        prUrl = c.receipt?.prUrl
                    )
                )
                else -> {}
            }
        }
        return builder.toGraph()
    }

    fun loadQueue(): List<QueueEntry> {
        val byWorkId = mutableMapOf<String, QueueEntry>()
        for ((workId, payload) in records()) {
            val ev = KanbanEventCodec.decode(payload.decodeToString()) as? KanbanEventCodec.CauseEvent ?: continue
            require(workId == ev.sid) { "WAL key/cause mismatch: $workId != ${ev.sid}" }
            val c = ev.cause
            when (c) {
                is JulesCause.WorkQueued -> {
                    require(workId == c.workId) { "WAL key/work mismatch: $workId != ${c.workId}" }
                    byWorkId.getOrPut(c.workId) {
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
                }
                is JulesCause.WorkDispatched -> {
                    require(workId == c.workId) { "WAL key/work mismatch: $workId != ${c.workId}" }
                    byWorkId[c.workId]?.let {
                    byWorkId[c.workId] = it.copy(
                        sessionId = c.sessionId,
                        attempt = c.attempt,
                        dispatchedAt = c.at
                    )
                    }
                }
                is JulesCause.WorkDrained -> {
                    require(workId == c.workId) { "WAL key/work mismatch: $workId != ${c.workId}" }
                    val entry = byWorkId.getOrPut(c.workId) {
                        QueueEntry(
                            workId = c.workId,
                            tier = "drained",
                            title = "CLI Settled",
                            spec = ""
                        )
                    }
                    byWorkId[c.workId] = entry.copy(
                        commitSha = c.commitSha,
                        taskId = c.taskId,
                        receipt = c.receipt,
                        drainedAt = c.at,
                    )
                }
                else -> {} // session-cause records do not carry workId; skip
            }
        }
        return byWorkId.values.toList()
    }

    /** Expand one atomic binary drain batch back into ordinary logical records. */
    private fun records(): Sequence<Pair<String, ByteArray>> = sequence {
        for ((key, payload) in wal.replay()) {
            if (key != DRAIN_BATCH_KEY) {
                yield(key to payload)
                continue
            }
            var offset = 0
            val magic = payload.readInt(offset)
            offset += 4
            require(magic == DRAIN_BATCH_MAGIC) { "invalid drain batch magic: $magic" }
            val count = payload.readInt(offset)
            offset += 4
            require(count >= 0) { "negative drain batch record count: $count" }
            repeat(count) {
                val keySize = payload.readInt(offset)
                offset += 4
                require(keySize >= 0 && offset + keySize <= payload.size) { "invalid drain batch key length" }
                val nestedKey = payload.decodeToString(offset, offset + keySize)
                offset += keySize
                val payloadSize = payload.readInt(offset)
                offset += 4
                require(payloadSize >= 0 && offset + payloadSize <= payload.size) { "invalid drain batch payload length" }
                val nestedPayload = payload.copyOfRange(offset, offset + payloadSize)
                offset += payloadSize
                yield(nestedKey to nestedPayload)
            }
            require(offset == payload.size) { "trailing bytes in drain batch: ${payload.size - offset}" }
        }
    }

    private fun ByteArray.putInt(offset: Int, value: Int): Int {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
        return offset + 4
    }

    private fun ByteArray.readInt(offset: Int): Int {
        require(offset >= 0 && offset + 4 <= size) { "truncated drain batch integer" }
        return ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
    }
}

/**
 * Queue entry projected from the unified WAL.
 * [score] drives dispatch priority — sort by score descending before dispatch.
 */
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
    val receipt: borg.trikeshed.util.oroboros.MergeReceipt? = null,
    val drainedAt: Long? = null,
) {
    val isDispatched: Boolean get() = sessionId != null
    val isDrained: Boolean get() = drainedAt != null
    val isUnclaimedDrain: Boolean get() = isDrained && receipt == null
}

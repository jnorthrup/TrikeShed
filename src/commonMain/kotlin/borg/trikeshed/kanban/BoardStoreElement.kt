package borg.trikeshed.kanban

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableJob
import borg.trikeshed.parse.reify
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.causal.CausalEdgeKind
import borg.trikeshed.causal.CausalGraph
import borg.trikeshed.causal.CausalGraphBuilder
import borg.trikeshed.causal.EventPayload
import borg.trikeshed.context.Drain
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobCommand
import borg.trikeshed.job.JobReducer
import borg.trikeshed.job.JobSnapshot
import borg.trikeshed.lib.*
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/**
 * BoardWalPort — the thin ordering log under the board (durability contract:
 * CAS payloads, the WAL carries only `jobId TAB cid` records; group commit =
 * ONE flush per drained batch; torn tails truncate on replay).
 * [BoardFileWal] retains the legacy frames over userspace positional I/O.
 */
interface BoardWalPort {
    val durability: StorageDurability get() = StorageDurability.VOLATILE

    /** Append one record (no flush — the element batches). Returns the sequence. */
    fun append(record: ByteArray): Long

    /** The single per-batch flush (group commit). */
    fun flush()

    /** Release the log after admitted board commands and their flush have finished. */
    fun close() = Unit

    suspend fun replay(onRecord: suspend (Long, ByteArray) -> Unit)
}

/** One browser/production command entering the store: the RAW map is the durable truth
 *  (title/priority/tags ride it into CAS; replay re-lowers the same bytes). */
class BoardIntake(
    val raw: Map<*, *>,
    val reply: CompletableDeferred<BoardApply>? = null,
)

sealed class BoardApply {
    data class Committed(
        val jobId: String,
        val sequence: Long,
        val revision: Long,
        val idempotencyKey: String,
        val cid: ContentId,
    ) : BoardApply()

    data class Rejected(
        val idempotencyKey: String?,
        val reason: String,
    ) : BoardApply()
}

/** Committed fanout — live listeners only (replay is the WAL's job, never the flow's). */
data class BoardCommitted(
    val sequence: Long,
    val jobId: String,
    val snapshot: JobSnapshot,
    val cid: ContentId,
    val command: JobCommand,
    val col: BoardCol,
    val previousCol: BoardCol?,
    /** Card's transition clock (row state) — the stall production's input. */
    val lastMoveMs: Long = 0L,
    /** Delta (reaper): the row's owner after the commit — `claim:*` marks the brain's work, which the reaper watches. */
    val owner: String = "",
    /**
     * Delta 2026-09-05 (fan-out): the row's spec after the commit. [BoardFactElement]
     * parses it (`MODELS:` / `FANOUT:`) so the fan-out production can see which cards
     * split; the raw string itself never lands on the fact (PlaneBrief.select scans
     * string fields and would self-match).
     */
    val spec: String = "",
    /** Delta 2026-09-05 (fan-out): the parent card this row branched off (`""` = a root). */
    val parent: String = "",
    /**
     * Delta 2026-09-05 (fan-out): the store's stamp on the committing command (`raw["atMs"]`,
     * stamped in [BoardStoreElement.applyOne]) — the one server time a witness surface may
     * print for this commit. 0 = the command carried no stamp (a seed or a test).
     */
    val atMs: Long = 0L,
    /** One root LCNC execution budget, shared by its validated nested runs. */
    val executionFamily: String = "",
    val priority: Int = 2,
    val order: Int = 0,
    val strikes: Int = 0,
    /** A positional move changes sibling order under the same committed command. */
    val ordering: Map<String, Int> = emptyMap(),
)

/** The WAL orders evidence; CAS retains its complete payload independently of the current card. */
data class BoardReceiptRef(val id: String, val jobId: String, val cid: ContentId, val sequence: Long)

/** One card = one row (the SoA projection reads these; strings only at the wire). */
data class CardRow(
    val jobId: String,
    val title: String,
    val col: BoardCol,
    val revision: Long,
    val lastSequence: Long,
    val lastMoveMs: Long,
    val priority: Int,
    val order: Int,
    val dependencies: List<String>,
    val tags: List<String>,
    /** Explicit Hermes owner, persisted as part of the command payload. */
    val owner: String = "",
    /**
     * The card's spec as RFC 2119 lines (GOAL / MUST / SHOULD / MAY / OUT-OF-SCOPE /
     * REVIEW: human / MODEL / TOKENS), persisted from the submit payload's `spec`.
     * Delta 2026-09-04: the plane judge reads MUSTs from here ([PlaneBrief.parseSpec]).
     */
    val spec: String = "",
    /** Judge/reaper strikes so far — persisted from the move payload's `strikes`, so the breaker survives a restart. */
    val strikes: Int = 0,
    /**
     * Delta 2026-09-05 (fan-out, Jim's ruling: store `parent` at submit): the card this
     * one branched off, persisted from the submit payload's `parent` — the same key the
     * orphan guard checks, which until now was verified at the door and then dropped.
     * `""` is a root. WAL replay re-derives it from the raw map; there is no re-parenting
     * op, so a later command without the key keeps the value.
     */
    val parent: String = "",
    /** Rebuildable pointer to this row's latest command in the existing CAS/WAL. */
    val commandCid: ContentId? = null,
    /** Empty for ordinary cards and legacy independent runs. */
    val executionFamily: String = "",
    /** Retained service parent; ordinary cards use [parent] instead. */
    val parentRunId: String? = null,
    /** Identity of the committed RUNNING claim, preserved through receipt acknowledgments. */
    val claimAttemptId: String = "",
)

/** Ordinary cards and LCNC execution families occupy disjoint WIP units. */
fun boardWipUnit(jobId: String, executionFamily: String): String =
    if (executionFamily.isEmpty()) "card/$jobId" else "lcnc/$executionFamily"

/**
 * BoardStoreElement — D2: ONE card store. `/api/invoke` maps → [InvokeLowering]
 * → [JobCommand] → [JobReducer] (pure; idempotency dedupe + expectedRevision
 * CAS) → group-committed WAL (CAS payloads) → [JobKanbanProjection.applyCommit]
 * (invariant C09: only committed frames advance cards) → causal EventNode
 * (card causal identity; CausalKernel: "This IS the kanban column") →
 * committed fanout.
 *
 * Board-level guards the reducer can't see run BEFORE reduction: WIP-full Move
 * refusal and dependency-cycle refusal (first caller of KanbanTypes.hasCycle).
 * State is WAL-rebuildable by construction — the detach→attach upgrade
 * contract of the module system, and the restart gate's proof obligation.
 *
 * Delta (claim → work → review): two more guards at the same door — the claim
 * gate ([claimGuard]: claimed work reaches DONE only from REVIEW, and never by
 * its claimant) and the orphan gate ([orphanGuard]: a Submit carrying `parent`
 * must branch off a live card). Both read the raw intake map, nothing else.
 */
class BoardStoreElement(
    private val wal: BoardWalPort?,
    private val cas: CasStore,
    private val clock: () -> Long = { 0L },
    parentJob: Job? = null,
) : CoroutineContext.Element, Drain {
    val supervisor: CompletableJob = SupervisorJob(parentJob)

    companion object Key : CoroutineContext.Key<BoardStoreElement> {
        /** Owner prefix that marks a card as claimed by the daemon's brain (`claim:brain`). */
        const val CLAIM_OWNER_PREFIX: String = "claim:"

        /** The longest `parent` chain the orphan guard walks; a real tree of work is a handful deep. */
        const val MAX_PARENT_HOPS: Int = 64
    }

    override val key: CoroutineContext.Key<*> get() = Key

    val intake: Channel<BoardIntake> = Channel(capacity = 256)

    private val _committed = MutableSharedFlow<BoardCommitted>(replay = 0, extraBufferCapacity = 1024)
    val committed: SharedFlow<BoardCommitted> get() = _committed

    // Single-writer state (the intake consumer is the only mutator).
    private val reducer = JobReducer()
    private val projection = JobKanbanProjection.rebuild(emptyList())
    private val causal = CausalGraphBuilder()
    private var sequence = 0L
    private var consumer: Job? = null
    @Volatile
    var failure: Throwable? = null
        private set

    // Private working rows let guards see earlier commands in the same batch.
    private var rows: Map<String, CardRow> = emptyMap()
    private var receipts: Map<String, BoardReceiptRef> = emptyMap()
    private data class Published(
        val rows: Map<String, CardRow>,
        val sequence: Long,
        val receipts: Map<String, BoardReceiptRef> = emptyMap(),
    )
    @Volatile
    private var published = Published(emptyMap(), 0L)

    fun cards(): Collection<CardRow> = published.rows.values

    fun card(jobId: String): CardRow? = published.rows[jobId]

    fun receiptRefs(): Series<BoardReceiptRef> = published.receipts.values.toSeries()

    suspend fun receipt(id: String): Map<*, *>? {
        val ref = published.receipts[id] ?: return null
        return withContext(Dispatchers.Unconfined) {
            (cas.get(ref.cid)?.let { reify(it) } as? Map<*, *>)?.get("receipt") as? Map<*, *>
        }
    }

    /** Evidence admission preserves the card's column, order and transition clock. */
    suspend fun recordReceipt(jobId: String, receipt: Map<String, Any?>): BoardApply {
        val id = receipt["id"] as? String ?: return BoardApply.Rejected(null, "receipt without id")
        while (true) {
            val row = card(jobId) ?: return BoardApply.Rejected(id, "receipt of unknown card '$jobId'")
            val reply = CompletableDeferred<BoardApply>()
            intake.send(BoardIntake(mapOf(
                "type" to "receipt", "jobId" to jobId, "receipt" to receipt,
                "idempotencyKey" to "$id#receipt#${row.revision}",
                "expectedRevision" to row.revision,
            ), reply))
            val applied = reply.await()
            if (applied !is BoardApply.Rejected || !applied.reason.startsWith("stale expectedRevision")) return applied
        }
    }

    suspend fun command(jobId: String): Map<*, *>? {
        val cid = published.rows[jobId]?.commandCid ?: return null
        return withContext(Dispatchers.Unconfined) {
            cas.get(cid)?.let { reify(it) as? Map<*, *> }
        }
    }

    fun projection(): JobKanbanProjection = projection

    fun graph(): CausalGraph = causal.toGraph()

    val lastSequence: Long get() = published.sequence

    /** A replayable pointer needs both its payload and ordering log to survive restart. */
    val durability: StorageDurability get() =
        if (cas.durability == StorageDurability.DURABLE && wal?.durability == StorageDurability.DURABLE)
            StorageDurability.DURABLE else StorageDurability.VOLATILE

    // ── lifecycle ─────────────────────────────────────────────────────

    suspend fun open() {
        check(failure == null) { "Board store failed; create a new owner before reopening" }
        try {
            wal?.replay { seq, record ->
                check(seq > sequence) { "Non-monotonic board WAL sequence: $seq after $sequence" }
                val text = record.decodeToString(throwOnInvalidSequence = true)
                // The legacy writer permits TAB inside job IDs; the final TAB separates the CID.
                val tab = text.lastIndexOf('\t')
                check(tab > 0) { "Invalid board WAL pointer at sequence $seq" }
                val jobId = text.substring(0, tab)
                val cid = ContentId(text.substring(tab + 1))
                val payload = checkNotNull(withContext(Dispatchers.Unconfined) { cas.get(cid) }) {
                    "Missing board WAL payload at sequence $seq: ${cid.value}"
                }
                check(ContentId.of(payload) == cid) { "Board WAL payload digest mismatch at sequence $seq: ${cid.value}" }
                val raw = checkNotNull(reifyStrict(payload.decodeToString(throwOnInvalidSequence = true)) as? Map<*, *>) {
                    "Board WAL command is not an object at sequence $seq"
                }
                when (val result = applyOne(raw, durable = false, replaySeq = seq, replayCid = cid, replayJobId = jobId)) {
                    is BoardApply.Committed -> Unit
                    is BoardApply.Rejected -> error("Board WAL command rejected at sequence $seq: ${result.reason}")
                }
            }
        } catch (caught: Throwable) {
            failure = caught
            intake.close(caught)
            while (true) (intake.tryReceive().getOrNull() ?: break).reply?.completeExceptionally(caught)
            withContext(NonCancellable) {
                try { close() } catch (cleanup: Throwable) { if (cleanup !== caught) caught.addSuppressed(cleanup) }
            }
            throw caught
        }
        published = Published(rows, sequence, receipts)
        consumer = CoroutineScope(currentCoroutineContext() + this + supervisor + Dispatchers.Default).launch {
            for (first in intake) {
                // Group commit: drain whatever queued behind the first, ONE flush for the batch.
                val batch = ArrayList<BoardIntake>(4).apply { add(first) }
                while (batch.size < 256) batch.add(intake.tryReceive().getOrNull() ?: break)
                val events = ArrayList<PendingCommit>()
                try {
                    val results = batch.map { applyOne(it.raw, durable = true, pending = events) }
                    if (events.isNotEmpty()) withContext(Dispatchers.Unconfined) {
                        // The flushed WAL names these payloads; publish its pointers only after CAS is durable.
                        cas.sync()
                        wal?.flush()
                    }
                    events.forEach { publishCommit(it) }
                    published = Published(rows, sequence, receipts)
                    // Acknowledgments and causal fanout are released only after the durability barrier.
                    events.forEach { _committed.emit(it.event) }
                    batch.zip(results).forEach { (cmd, result) -> cmd.reply?.complete(result) }
                } catch (failure: Throwable) {
                    // The private reducer cannot resume after an uncertain append/flush.
                    // Fail closed; a fresh element must recover from the WAL.
                    rows = published.rows
                    sequence = published.sequence
                    receipts = published.receipts
                    this@BoardStoreElement.failure = failure
                    intake.close(failure)
                    batch.forEach { it.reply?.completeExceptionally(failure) }
                    while (true) (intake.tryReceive().getOrNull() ?: break).reply?.completeExceptionally(failure)
                    return@launch
                }
            }
        }
    }

    override suspend fun drain() {
        intake.close()
        consumer?.join()
        if (supervisor.complete()) {
            supervisor.join()
            close()
        } else supervisor.join()
    }

    suspend fun close() {
        intake.close()
        consumer?.join()
        try { wal?.close() } finally { supervisor.cancelAndJoin() }
    }

    // ── the spine (single consumer; also the replay path with durable=false) ──

    private data class PendingCommit(val event: BoardCommitted, val raw: Map<*, *>)

    private fun publishCommit(commit: PendingCommit) {
        val event = commit.event
        projection.applyCommit(event.jobId, event.snapshot, event.cid)
        appendCausal(event.jobId, event.command, event.snapshot, event.cid, commit.raw)
    }

    private suspend fun applyOne(incoming: Map<*, *>, durable: Boolean, replaySeq: Long? = null, replayCid: ContentId? = null, replayJobId: String? = null, pending: MutableList<PendingCommit>? = null): BoardApply {
        // Delta (reaper): the transition clock is part of the WAL truth. The live path
        // stamps `atMs` into the raw map before it is serialized, and advanceRow reads
        // it back — so a replay re-derives the SAME lastMoveMs. Before this, replay
        // took clock() at boot and every restart zeroed every card's idle time: the
        // stall and reaper thresholds started over, and a dead claim was never reaped.
        val raw: Map<*, *> = if (durable && incoming["atMs"] == null) incoming + ("atMs" to clock()) else incoming
        val lowered = when (val o = InvokeLowering.lower(raw)) {
            is InvokeLowering.Outcome.Rejected -> return BoardApply.Rejected(o.idempotencyKey, o.reason)
            is InvokeLowering.Outcome.Lowered -> o.command
        }
        val jobId = lowered.jobId.value
        check(replayJobId == null || replayJobId == jobId) { "Board WAL job identity mismatch at sequence $replaySeq" }

        // Board guards the reducer can't see — refuse BEFORE reducer state mutates.
        boardGuard(lowered, raw)?.let { return BoardApply.Rejected(lowered.idempotencyKey, it) }

        val reduced = reducer.reduce(lowered)
        if (!reduced.accepted || reduced.snapshot == null) {
            val reason = (reduced.event as? borg.trikeshed.job.JobEvent.Rejected)?.reason ?: "rejected"
            return BoardApply.Rejected(lowered.idempotencyKey, reason)
        }
        val snapshot = reduced.snapshot!!

        // Durable truth: the raw command map, canonical-serialized once, CAS-addressed.
        val (cid, seq) = withContext(Dispatchers.Unconfined) {
            // Parsing may normalize numbers; replay must never re-address the payload.
            val cid = replayCid ?: cas.put(jsonOf(raw).encodeToByteArray())
            cid to (replaySeq
                ?: if (durable && wal != null) wal.append("$jobId\t${cid.value}".encodeToByteArray())
                else sequence + 1)
        }
        if (seq > sequence) sequence = seq

        val prev = rows[jobId]
        val row = advanceRow(prev, jobId, raw, lowered, snapshot, seq)?.copy(
            commandCid = cid,
            parentRunId = if (prev?.executionFamily.isNullOrEmpty())
                (raw["lcncRun"] as? Map<*, *>)?.get("parentRunId") as? String else prev?.parentRunId,
        )
        rows = if (row == null) rows - jobId else rows + (jobId to row)
        (raw["receipt"] as? Map<*, *>)?.let { value ->
            val id = value["id"] as String
            receipts = receipts + (id to BoardReceiptRef(id, jobId, cid, seq))
        }

        // Positional insert: a move carrying beforeJobId lands BETWEEN cards, not
        // at the bottom. The raw map is the WAL truth, so replay re-derives the
        // same packing; ordering never depends on client state.
        val beforeId = (raw["beforeJobId"] as? String)?.takeIf { it.isNotBlank() && it != jobId }
        if (lowered is JobCommand.Move && row != null && beforeId != null) {
            rows = repackColumn(rows, row.col, jobId, beforeId)
        }

        val event = BoardCommitted(
            sequence = seq,
            jobId = jobId,
            snapshot = snapshot,
            cid = cid,
            command = lowered,
            col = row?.col ?: BoardCol.ARCHIVED,
            previousCol = prev?.col,
            lastMoveMs = row?.lastMoveMs ?: 0L,
            owner = row?.owner ?: "",
            // Delta 2026-09-05 (fan-out): spec/parent ride the event so the fact bridge can
            // parse MODELS:/FANOUT: and the tree without a second read of the row table;
            // atMs is the stamp on THIS command (live: just now; replay: what the WAL says).
            spec = row?.spec ?: "",
            parent = row?.parent ?: "",
            atMs = (raw["atMs"] as? Number)?.toLong() ?: 0L,
            executionFamily = row?.executionFamily.orEmpty(),
            priority = row?.priority ?: 2,
            order = rows[jobId]?.order ?: 0,
            strikes = row?.strikes ?: 0,
            ordering = if (beforeId != null && row != null) rows.values
                .filter { it.col == row.col }.associate { it.jobId to it.order } else emptyMap(),
        )
        val commit = PendingCommit(event, raw)
        if (pending != null) pending.add(commit) else publishCommit(commit)
        return BoardApply.Committed(jobId, seq, snapshot.revision, lowered.idempotencyKey, cid)
    }

    /** Re-pack one column 0..n with [movedId] positioned before [beforeId].
     *  A beforeId outside the column degrades to append — never an error. */
    private fun repackColumn(
        current: Map<String, CardRow>,
        col: BoardCol,
        movedId: String,
        beforeId: String,
    ): Map<String, CardRow> {
        val moved = current[movedId] ?: return current
        val rest = current.values.filter { it.col == col && it.jobId != movedId }.sortedBy { it.order }
        val at = rest.indexOfFirst { it.jobId == beforeId }
        val packed = ArrayList<CardRow>(rest.size + 1)
        if (at < 0) { packed.addAll(rest); packed.add(moved) }
        else { packed.addAll(rest.subList(0, at)); packed.add(moved); packed.addAll(rest.subList(at, rest.size)) }
        var out = current
        packed.forEachIndexed { i, r -> if (r.order != i) out = out + (r.jobId to r.copy(order = i)) }
        return out
    }

    /**
     * Board guards, in order: WIP, the claim/review gate, the dependency cycle.
     * [raw] is the intake map (the WAL truth) — a Move may carry `"actor"` (who
     * is moving); it is read here and nowhere else.
     */
    private suspend fun boardGuard(cmd: JobCommand, raw: Map<*, *>): String? {
        if (raw.containsKey("receipt")) {
            if (raw["type"]?.toString()?.lowercase() != "receipt") return "receipt requires type=receipt"
            val value = raw["receipt"] as? Map<*, *> ?: return "invalid receipt"
            val id = value["id"] as? String ?: return "receipt without id"
            if (id.isBlank() || value["jobId"] != cmd.jobId.value) return "invalid receipt identity"
            if (rows[cmd.jobId.value] == null) return "receipt of unknown card '${cmd.jobId.value}'"
            if (id in receipts) return "receipt already retained: $id"
            val attemptId = value["attemptId"] as? String
            if (attemptId.isNullOrBlank()) return "receipt without attempt identity"
            when (value["kind"]) {
                "claim-admission" -> if (id != "$attemptId/admission") return "invalid claim admission identity"
                "claim" -> if (id != "$attemptId/result") return "invalid claim result identity"
                "grade" -> {
                    val grade = borg.trikeshed.agent.modelmux.modelGrade(value, sequence + 1) ?: return "invalid model grade"
                    if (grade.basis != borg.trikeshed.agent.modelmux.ModelGradeBasis.CORRECTNESS) return "external grade requires human-correctness-v1"
                    val ref = receipts["$attemptId/result"] ?: return "grade requires retained claim result"
                    if (ref.jobId != cmd.jobId.value) return "grade belongs to another card"
                    val result = withContext(Dispatchers.Unconfined) {
                        (cas.get(ref.cid)?.let { reify(it) } as? Map<*, *>)?.get("receipt") as? Map<*, *>
                    } ?: return "grade result unavailable"
                    for (field in s_["model", "modelVersion", "harness", "taskKind"].view)
                        if (value[field] != result[field]) return "grade changes attempt $field"
                    if ((value["actor"] as? String).isNullOrBlank() || (value["note"] as? String).isNullOrBlank())
                        return "grade requires actor and assessment note"
                    val evidence = InvokeLowering.listishOf(value["evidenceIds"]) ?: return "grade requires retained evidence"
                    // The result points to the admission's exact prompt and source snapshot.
                    val identities = setOf("$attemptId/result", "$attemptId/admission")
                    if (evidence.isEmpty() || evidence.any { it !in identities || it !in receipts })
                        return "grade evidence must name this attempt's retained admission or result"
                }
                else -> return "unknown receipt kind"
            }
        }
        executionFamilyGuard(cmd.jobId.value, raw)?.let { return it }
        return when (cmd) {
            is JobCommand.Move -> {
                val target = BoardCol.fromWire(cmd.toColumn.value)
                val wip = target?.let { wipGuard(it, cmd.jobId.value, raw) }
                if (wip != null) wip
                else if (target == BoardCol.RUNNING && (raw["owner"] as? String)?.startsWith(CLAIM_OWNER_PREFIX) == true)
                    admissionGuard(cmd.jobId.value) ?: ownerGuard(cmd.jobId.value, raw, target)
                // Delta 2026-09-04 (verifier finding): ARCHIVED is a settling column too, and a
                // re-owning Move was a two-step way around the gate — both go through the guards.
                else if (target == BoardCol.DONE || target == BoardCol.ARCHIVED) claimGuard(cmd.jobId.value, raw) ?: ownerGuard(cmd.jobId.value, raw, target)
                else ownerGuard(cmd.jobId.value, raw, target)
            }

            // `complete`/`done` lowers to Complete → lifecycle closed → DONE: the same gate, or a
            // claimant could close its own card by the lifecycle verb instead of the column.
            is JobCommand.Complete -> claimGuard(cmd.jobId.value, raw)

            // `cancel` / `retract` settle a card to ARCHIVED: claimed work in RUNNING is released
            // by its judge or reaper, never cancelled out from under them (verifier finding).
            is JobCommand.Cancel, is JobCommand.Retract -> claimGuard(cmd.jobId.value, raw)

            is JobCommand.Start -> {
                wipGuard(BoardCol.RUNNING, cmd.jobId.value, raw)
            }

            is JobCommand.Submit -> {
                orphanGuard(cmd.jobId.value, raw) ?: if (cmd.dependencies.isEmpty()) null
                else {
                    // First caller of the KanbanTypes verbs: refuse a dependency cycle at the door.
                    val board = boardOf(extra = cmd)
                    if (board.hasCycle()) "dependency cycle via ${cmd.jobId.value}" else null
                }
            }

            else -> null
        }
    }

    private fun executionFamily(jobId: String, raw: Map<*, *>): String {
        val previous = rows[jobId]
        val owner = if (raw.containsKey("owner")) (raw["owner"] as? String)?.trim().orEmpty() else previous?.owner
        if (owner != "lcnc-runner") return ""
        val receipt = raw["lcncRun"] as? Map<*, *>
        return (receipt?.get("executionFamily") as? String) ?: previous?.executionFamily.orEmpty()
    }

    /** Family membership follows retained LCNC run identities and cannot move between roots. */
    private fun executionFamilyGuard(jobId: String, raw: Map<*, *>): String? {
        val previous = rows[jobId]
        val owner = if (raw.containsKey("owner")) (raw["owner"] as? String)?.trim().orEmpty() else previous?.owner
        if (!previous?.executionFamily.isNullOrEmpty() && owner != "lcnc-runner")
            return "LCNC execution family owner cannot change"
        val priorFamily = previous?.executionFamily.orEmpty()
        val receipt = raw["lcncRun"] as? Map<*, *>
            ?: return if (priorFamily.isEmpty()) null else "LCNC execution receipt is required"
        if (!receipt.containsKey("executionFamily"))
            return if (priorFamily.isEmpty()) null else "LCNC execution family is required"
        val family = receipt["executionFamily"] as? String
        val runId = receipt["runId"] as? String
        if (owner != "lcnc-runner" || runId.isNullOrBlank() || jobId != "lcnc/run/$runId" ||
            family == null || !family.startsWith("lcnc/run/") || family.removePrefix("lcnc/run/").isBlank())
            return "invalid LCNC execution family"
        if (priorFamily.isNotEmpty()) return when {
            priorFamily != family -> "LCNC execution family cannot change"
            receipt["parentRunId"] != previous?.parentRunId -> "LCNC execution parent cannot change"
            else -> null
        }
        if (family == jobId) return if (receipt["parentRunId"] == null) null else "LCNC root cannot inherit itself"
        val parentId = (receipt["parentRunId"] as? String)?.takeIf { it.isNotBlank() }
            ?: return "LCNC execution family requires a parent run"
        val parent = rows["lcnc/run/$parentId"]
        return if (parent?.owner == "lcnc-runner" && parent.executionFamily == family) null
            else "LCNC execution family does not match its parent run"
    }

    private fun wipGuard(target: BoardCol, jobId: String, raw: Map<*, *>): String? {
        val limit = target.wipLimit ?: return null
        val occupied = rows.values.asSequence().filter { it.col == target && it.jobId != jobId }
            .map { boardWipUnit(it.jobId, it.executionFamily) }.toSet()
        val unit = boardWipUnit(jobId, executionFamily(jobId, raw))
        return if (unit !in occupied && occupied.size >= limit) "WIP limit $limit full for '${target.wire}'" else null
    }

    /** Queued activations cannot overtake a newer priority or positional edit at admission. */
    private fun admissionGuard(jobId: String): String? {
        if (rows[jobId]?.col != BoardCol.READY) return null
        val occupied = rows.values.filter { it.col == BoardCol.RUNNING }
            .map { boardWipUnit(it.jobId, it.executionFamily) }.toSet().size
        val available = (BoardCol.RUNNING.wipLimit ?: return null) - occupied
        val ready = rows.values.toSeries().filter { row ->
            row.col == BoardCol.READY && row.executionFamily.isEmpty() &&
                !(PlaneBrief.parseSpec(row.title, row.spec).fansOut &&
                    rulesFanOutPending(row))
        }.sortedWith(compareBy({ it.priority }, { it.order }, { it.lastMoveMs }, { it.jobId }))
        for ((index, row) in ready.view.withIndex()) {
            if (index >= available) break
            if (row.jobId == jobId) return null
        }
        return "claim allocation changed for '$jobId'"
    }

    private fun rulesFanOutPending(row: CardRow): Boolean =
        borg.trikeshed.kanban.rules.BoardRules.fanOutPending(row.jobId, row.dependencies,
            rows.values.filter { it.parent == row.jobId &&
                borg.trikeshed.kanban.rules.BoardRules.isMintedChild(row.jobId, it.jobId) }.map { it.jobId })

    /**
     * Claimed work passes review first. A card whose owner is `claim:*` was
     * worked by the daemon's brain; it reaches DONE only from REVIEW, and only
     * when the mover is somebody else (a Move map carrying `"actor"` equal to
     * the owner is the claimant closing its own ticket — refused). A move with
     * no actor is an unlabeled human gesture and passes.
     */
    private fun claimGuard(jobId: String, raw: Map<*, *>): String? {
        val prev = rows[jobId] ?: return null
        if (!prev.owner.startsWith(CLAIM_OWNER_PREFIX)) return null
        if (prev.col != BoardCol.REVIEW && prev.col != BoardCol.BLOCKED) {
            return "claimed work passes review first: '$jobId' is owned by ${prev.owner} and sits in '${prev.col.wire}', not 'review'"
        }
        val actor = (raw["actor"] as? String)?.trim().orEmpty()
        return if (actor.isNotEmpty() && actor == prev.owner) {
            "review needs a second pair of eyes: actor '$actor' is the claimant of '$jobId'"
        } else null
    }

    /** Actors allowed to release a claim (change or clear a `claim:*` owner). */
    private val releasers = setOf("reaper", "judge:plane")

    /**
     * A claim is released by the reaper or the judge, not by re-owning. A Move whose
     * map carries `owner` different from a `claim:*` owner is refused unless the
     * actor is a releaser or the target is BLOCKED (the strike-out hands the card
     * back to a person). Closes the two-step bypass: re-own to blank, then DONE.
     */
    private fun ownerGuard(jobId: String, raw: Map<*, *>, target: BoardCol?): String? {
        val prev = rows[jobId] ?: return null
        if (!prev.owner.startsWith(CLAIM_OWNER_PREFIX) || !raw.containsKey("owner")) return null
        val next = (raw["owner"] as? String)?.trim().orEmpty()
        if (next == prev.owner) return null
        val actor = (raw["actor"] as? String)?.trim().orEmpty()
        if (actor in releasers || target == BoardCol.BLOCKED) return null
        return "a claim is released by the reaper or the judge, not by re-owning: '$jobId' is owned by ${prev.owner}"
    }


    /**
     * Orphan guard. The tree of work lives ON THE BOARD: a Submit whose intake
     * map carries `"parent"` is a split of that card and must name one that
     * exists and is still live (not DONE/ARCHIVED) — else it is a branch off a
     * dead tree and is refused at the door. A Submit without `parent` is intake
     * (a root), and passes. The raw map is the WAL truth, so the parent edge
     * replays with the card.
     *
     * Delta 2026-09-05 (fan-out): the edge this guard verifies is also STORED now
     * ([CardRow.parent], read in [advanceRow]); before, it was checked here and then
     * forgotten, so no reader could reconstruct the tree the guard protected. Because
     * it is stored — and a re-Submit of an existing card (the fan-out join) passes
     * through here too — the guard also refuses a card as its own parent and a parent
     * edge that would close a cycle through the existing `parent` chain (bounded to
     * [MAX_PARENT_HOPS]); a tree reader walking `parent` must always reach a root.
     */
    private fun orphanGuard(jobId: String, raw: Map<*, *>): String? {
        val parent = (raw["parent"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (parent == jobId) return "orphan: $jobId cannot be its own parent"
        val row = rows[parent] ?: return "orphan: parent $parent is not live (no such card)"
        if (row.col == BoardCol.DONE || row.col == BoardCol.ARCHIVED) {
            return "orphan: parent $parent is not live (it is '${row.col.wire}')"
        }
        var cur = row.parent
        var hops = 0
        while (cur.isNotEmpty() && hops++ < MAX_PARENT_HOPS) {
            if (cur == jobId) return "orphan: parent $parent would close a tree cycle via $jobId"
            cur = rows[cur]?.parent.orEmpty()
        }
        return null
    }

    /** Materialize the current rows (+ an incoming submit) as a KanbanBoard for the DAG verbs. */
    private fun boardOf(extra: JobCommand.Submit?): KanbanBoard {
        val cards = ArrayList<KanbanCard>(rows.size + 1)
        for (r in rows.values) cards.add(
            KanbanCard(
                id = KanbanCardId(r.jobId),
                title = r.title,
                columnId = KanbanColumnId(r.col.wire),
                order = r.order,
                dependencies = r.dependencies.map { KanbanCardId(it) },
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        if (extra != null && rows[extra.jobId.value] == null) cards.add(
            KanbanCard(
                id = KanbanCardId(extra.jobId.value),
                title = extra.jobId.value,
                columnId = KanbanColumnId(BoardCol.TRIAGE.wire),
                dependencies = extra.dependencies.map { KanbanCardId(it.value) },
                createdAt = 0L,
                updatedAt = 0L,
            ),
        ) else if (extra != null) {
            // re-submit of an existing card: splice the new dependency edges in
            val i = cards.indexOfFirst { it.id.value == extra.jobId.value }
            if (i >= 0) cards[i] = cards[i].copy(dependencies = extra.dependencies.map { KanbanCardId(it.value) })
        }
        return KanbanBoard(
            id = KanbanBoardId("board-store"),
            name = "board-store",
            columns = BoardCol.rendered.map { KanbanColumn(KanbanColumnId(it.wire), it.wire, it.order, wipLimit = it.wipLimit) },
            cards = cards,
        )
    }

    private fun advanceRow(
        prev: CardRow?,
        jobId: String,
        raw: Map<*, *>,
        cmd: JobCommand,
        snapshot: JobSnapshot,
        seq: Long,
    ): CardRow? {
        // An edit changes metadata, not the lifecycle projection, claim or transition clock.
        if (cmd is JobCommand.Update || cmd is JobCommand.Acknowledge && cmd.receipt && prev != null) return checkNotNull(prev).copy(
            title = (cmd as? JobCommand.Update)?.title ?: prev.title,
            priority = (cmd as? JobCommand.Update)?.priority ?: prev.priority,
            revision = snapshot.revision,
            lastSequence = seq,
        )
        // The stamped transition clock (live: stamped just now; replay: what the WAL says).
        val now = (raw["atMs"] as? Number)?.toLong() ?: clock()
        val col = when (cmd) {
            is JobCommand.Move -> BoardCol.fromWire(cmd.toColumn.value) ?: prev?.col ?: BoardCol.TRIAGE
            is JobCommand.Acknowledge -> prev?.col ?: BoardCol.fromLifecycle(snapshot.lifecycle)
            is JobCommand.Retract, is JobCommand.Cancel -> BoardCol.ARCHIVED
            else -> BoardCol.fromLifecycle(snapshot.lifecycle)
        }
        val title = (raw["title"] as? String)?.takeIf { it.isNotBlank() } ?: prev?.title ?: jobId
        val priority = (raw["priority"] as? Number)?.toInt() ?: prev?.priority ?: 2
        val tags = InvokeLowering.listishOf(raw["tags"])?.mapNotNull { it?.toString() } ?: prev?.tags ?: emptyList()
        val moved = prev == null || prev.col != col
        return CardRow(
            jobId = jobId,
            title = title,
            col = col,
            revision = snapshot.revision,
            lastSequence = seq,
            lastMoveMs = if (moved) now else prev?.lastMoveMs ?: now,
            priority = priority,
            // A move is appended to the target column's ordered sequence. The
            // browser supplies only the gesture; ordering is derived here from
            // the single-writer store so reload/replay produces the same board.
            order = if (cmd is JobCommand.Move) {
                rows.values.count { it.col == col && it.jobId != jobId }
            } else prev?.order ?: rows.size,
            dependencies = snapshot.dependencies.map { it.value },
            tags = tags,
            // An absent key keeps the owner; a key present and blank CLEARS it (the
            // reaper's third strike hands a card back to a human that way).
            owner = if (raw.containsKey("owner")) (raw["owner"] as? String)?.trim().orEmpty() else prev?.owner ?: "",
            spec = (raw["spec"] as? String)?.takeIf { it.isNotBlank() } ?: prev?.spec ?: "",
            strikes = (raw["strikes"] as? Number)?.toInt() ?: raw["strikes"]?.toString()?.toIntOrNull() ?: prev?.strikes ?: 0,
            // Delta 2026-09-05 (fan-out): the parent edge the orphan guard verified is now kept.
            // A blank or absent key keeps the previous value — a re-submit that only sets
            // dependencies (the fan-out join) must not detach a child from its tree.
            parent = (raw["parent"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: prev?.parent ?: "",
            executionFamily = executionFamily(jobId, raw),
            claimAttemptId = if (cmd is JobCommand.Move && col == BoardCol.RUNNING &&
                (raw["owner"] as? String)?.startsWith(CLAIM_OWNER_PREFIX) == true)
                cmd.idempotencyKey else prev?.claimAttemptId.orEmpty(),
        )
    }

    /** Card lifecycle → the causal plane (coarse: lifecycle ops only; Move stays a board refinement). */
    private fun appendCausal(jobId: String, cmd: JobCommand, snapshot: JobSnapshot, cid: ContentId, raw: Map<*, *>) {
        val lex = LexicalMemory(summary = snapshot.lifecycle, title = (raw["title"] as? String) ?: jobId, content = "")
        val (kind, payload) = when (cmd) {
            is JobCommand.Submit -> CausalEdgeKind.Inducted to EventPayload.Queued(
                tier = "board", title = lex.title, spec = cid.value, score = 0.0, lexicalMemory = lex,
            )

            is JobCommand.Start -> CausalEdgeKind.Dispatched to EventPayload.Dispatched(
                sessionId = snapshot.attemptId.ifEmpty { jobId }, attempt = snapshot.attemptCount,
            )

            is JobCommand.Complete -> CausalEdgeKind.Settled to EventPayload.Settled(
                commitSha = "", versionTag = "", receiptCid = cid, lexicalMemory = lex,
            )

            is JobCommand.Fail -> CausalEdgeKind.Retired to EventPayload.Retired(cmd.reason, snapshot.lifecycle)
            is JobCommand.Cancel -> CausalEdgeKind.Retired to EventPayload.Retired("cancelled", snapshot.lifecycle)
            is JobCommand.Retract -> CausalEdgeKind.Retired to EventPayload.Retired("retracted", snapshot.lifecycle)
            is JobCommand.Retry -> CausalEdgeKind.Superseded to EventPayload.Superseded(
                parentWorkId = jobId, reason = "retry", reworkDepth = snapshot.attemptCount,
            )

            else -> return // Move/Progress/Block/Acknowledge are board refinements, not causal events
        }
        causal.append(workId = jobId, epochMs = (raw["atMs"] as? Number)?.toLong() ?: clock(), edgeKind = kind, payload = payload)
    }
}

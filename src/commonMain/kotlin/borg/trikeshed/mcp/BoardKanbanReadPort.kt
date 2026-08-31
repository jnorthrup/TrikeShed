package borg.trikeshed.mcp

import borg.trikeshed.kanban.BoardCommitted
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.CardRow
import borg.trikeshed.lcnc.LcncKanbanExperience
import kotlin.concurrent.Volatile

/**
 * Receipts kept addressable by sequence.
 *
 * The store's durable truth is the WAL line (`jobId \t cid`) plus the raw
 * command in CAS; what it does NOT keep is a sequence → receipt index, because
 * nothing in-process needed one until MCP wanted `receipts/{sequence}` to be a
 * readable resource. This is that index and nothing more: a bounded ring fed
 * from the store's committed flow, holding the last [capacity] changes.
 *
 * Retention is honest and bounded — a read past the window returns null rather
 * than a fabricated receipt, and the CAS id inside each live entry is the
 * durable anchor that outlives the ring.
 *
 * Restart is where the honesty costs something. The store replays its WAL
 * inside `open()`, before anything subscribes, and `committed` has no replay
 * buffer — so a fresh process has no committed events to catch. [seedFrom]
 * therefore rebuilds one entry per card from the replayed row table, marked
 * `source: "replay"`, carrying the fields the row genuinely proves (sequence,
 * revision, column, lastMoveMs) and a NULL cid, because the store keeps no
 * sequence → cid index a reader could consult. A synthesized content id would
 * be a lie that reads exactly like the truth, so there isn't one.
 *
 * COW, like the store's own row table: the collector writes a new map, readers
 * take one volatile read and never lock.
 */
class KanbanReceiptLog(private val capacity: Int = 512) {

    @Volatile
    private var retained: Map<Long, Map<String, Any?>> = emptyMap()

    @Volatile
    private var order: List<Long> = emptyList()

    val size: Int get() = retained.size

    fun record(event: BoardCommitted) {
        put(
            event.sequence,
            linkedMapOf(
                "sequence" to event.sequence,
                "jobId" to event.jobId,
                "revision" to event.snapshot.revision,
                "lifecycle" to event.snapshot.lifecycle,
                "command" to event.command.operationName,
                "idempotencyKey" to event.command.idempotencyKey,
                "column" to event.col.wire,
                "previousColumn" to event.previousCol?.wire,
                "lastMoveMs" to event.lastMoveMs,
                // The durable anchor: the raw command map, canonical-serialized and
                // content-addressed by the store. Survives this ring.
                "cid" to event.cid.value,
                "source" to "committed",
                "cardResource" to "${LcncKanbanMcp.URI_CARD_PREFIX}${event.jobId}",
            ),
        )
    }

    /**
     * Rebuild one entry per card after a WAL replay, so a card's
     * `receiptResource` resolves in a freshly started daemon. Only what the row
     * proves — no cid, because none is recoverable here.
     */
    fun seedFrom(rows: Collection<CardRow>) {
        for (row in rows.sortedBy { it.lastSequence }) {
            if (retained.containsKey(row.lastSequence)) continue
            put(
                row.lastSequence,
                linkedMapOf(
                    "sequence" to row.lastSequence,
                    "jobId" to row.jobId,
                    "revision" to row.revision,
                    "column" to row.col.wire,
                    "lastMoveMs" to row.lastMoveMs,
                    "cid" to null,
                    "source" to "replay",
                    "note" to "Rebuilt from the replayed board at startup; the committing command's " +
                        "content id is not indexed by sequence and is therefore not reported.",
                    "cardResource" to "${LcncKanbanMcp.URI_CARD_PREFIX}${row.jobId}",
                ),
            )
        }
    }

    private fun put(sequence: Long, entry: Map<String, Any?>) {
        var keys = order + sequence
        var map = retained + (sequence to entry)
        while (keys.size > capacity) {
            map = map - keys.first()
            keys = keys.drop(1)
        }
        retained = map
        order = keys
    }

    fun get(sequence: Long): Map<String, Any?>? = retained[sequence]
}

/**
 * [KanbanReadPort] over the live board: sheets come from the LCNC experience,
 * cards from the store's row table, receipts from the ring above. Reads only —
 * this class holds the store to *project* it, and the MCP handler never sees
 * the store at all.
 */
class BoardKanbanReadPort(
    private val store: BoardStoreElement,
    private val experience: LcncKanbanExperience,
    private val receipts: KanbanReceiptLog,
) : KanbanReadPort {

    override fun sheets(): Map<String, Any?> = experience.activeSheets()

    override fun card(jobId: String): Map<String, Any?>? = store.card(jobId)?.let(::cardMap)

    override fun receipt(sequence: Long): Map<String, Any?>? = receipts.get(sequence)

    override fun watermark(): Long = store.lastSequence

    companion object {
        /**
         * The full card, addressed by id. `/api/board` carries the same fields
         * (its `enrich` closed the audit's "read projection is thinner still"),
         * but it is a whole-board read with no per-card route and no link to the
         * change that produced the row — so this adds `lastSequence` and the
         * receipt reference an agent needs to trace what it just did.
         */
        fun cardMap(row: CardRow): Map<String, Any?> = linkedMapOf(
            "id" to row.jobId,
            "title" to row.title,
            "status" to row.col.wire,
            "priority" to row.priority,
            "order" to row.order,
            "revision" to row.revision,
            "lastSequence" to row.lastSequence,
            "lastMoveMs" to row.lastMoveMs,
            "dependencies" to row.dependencies,
            "tags" to row.tags,
            "owner" to row.owner,
            "receiptResource" to "${LcncKanbanMcp.URI_RECEIPT_PREFIX}${row.lastSequence}",
        )
    }
}

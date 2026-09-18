@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge.board

import borg.trikeshed.forge.asBool
import borg.trikeshed.forge.asInt
import borg.trikeshed.forge.asList
import borg.trikeshed.forge.asMaps
import borg.trikeshed.forge.asStr
import borg.trikeshed.forge.asStrOrNull
import kotlinx.datetime.Clock

/**
 * Board + command-queue decisions — script.js sections "Command queue → reactor ingress" and
 * "Board commands". The batching rules, command shapes, watermark reconciliation, and sync-note
 * text live here; the timer and `fetch` live in the jsForge adapter.
 */

/** A board column as the UI keeps it (id + name, plus the server-reported wip/count when hydrated). */
class ForgeBoardColumn(
    val id: String,
    val name: String,
    val wipLimit: Int? = null,
    val count: Int? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "name" to name, "wipLimit" to wipLimit, "count" to count)

    companion object {
        fun fromMap(map: Map<String, Any?>): ForgeBoardColumn = ForgeBoardColumn(
            id = map["id"].asStr(),
            name = map["name"].asStr(),
            wipLimit = (map["wipLimit"] as? Number)?.toInt(),
            count = (map["count"] as? Number)?.toInt(),
        )
    }
}

/** A board card. `column` mutates on optimistic moves; `revision` is the server's OCC watermark. */
class ForgeBoardCard(
    val id: String,
    var title: String,
    var column: String,
    var revision: Int? = null,
    var meta: String = "",
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id, "title" to title, "column" to column, "revision" to revision, "meta" to meta,
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): ForgeBoardCard = ForgeBoardCard(
            id = map["id"].asStr(),
            title = map["title"].asStr(),
            column = map["column"].asStr(),
            revision = (map["revision"] as? Number)?.toInt(),
            meta = map["meta"].asStr(),
        )
    }
}

class ForgeBoard(
    val columns: MutableList<ForgeBoardColumn>,
    val cards: MutableList<ForgeBoardCard>,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "columns" to columns.map { it.toMap() },
        "cards" to cards.map { it.toMap() },
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): ForgeBoard = ForgeBoard(
            columns = map["columns"].asMaps().map { ForgeBoardColumn.fromMap(it) }.toMutableList(),
            cards = map["cards"].asMaps().map { ForgeBoardCard.fromMap(it) }.toMutableList(),
        )
    }
}

/** The column a card click cycles to: next in column order, wrapping. */
fun ForgeBoard.nextColumnId(of: String): String? {
    if (columns.isEmpty()) return null
    val order = columns.map { it.id }
    val idx = order.indexOf(of)
    return order[((if (idx < 0) 0 else idx) + 1) % order.size]
}

// ── Command shapes (the real spine: invoke → lowering → WAL → projection) ──

/** `jobId#ui#<epochMs>` — minted lazily, only when a command enters the queue without one. */
fun forgeIdempotencyKey(jobId: String, nowMs: Long = Clock.System.now().toEpochMilliseconds()): String =
    "$jobId#ui#$nowMs"

/** Card click → move command (only when the card carries a server revision). */
fun boardMoveCommand(jobId: String, expectedRevision: Int, toColumn: String): Map<String, Any?> = mapOf(
    "type" to "move",
    "jobId" to jobId,
    "expectedRevision" to expectedRevision,
    "toColumn" to toColumn,
)

/** "+ New" → submit command. */
fun boardSubmitCommand(jobId: String, title: String): Map<String, Any?> = mapOf(
    "type" to "submit",
    "jobId" to jobId,
    "title" to title,
)

/**
 * The command queue: every mutation is a command; commands batch for a short window (the adapter's
 * timer) and POST as one body. `drain` takes the whole pending batch — `splice(0, length)`.
 */
class ForgeCommandQueue {
    val pending: MutableList<Map<String, Any?>> = mutableListOf()

    fun enqueue(command: Map<String, Any?>, nowMs: Long = Clock.System.now().toEpochMilliseconds()) {
        val keyed = if (command["idempotencyKey"] != null) command
        else command + ("idempotencyKey" to forgeIdempotencyKey(command["jobId"].asStr(), nowMs))
        pending.add(keyed)
    }

    /** The flush body: `{userId, commands}` over the drained batch. */
    fun drain(userId: String): Map<String, Any?>? {
        if (pending.isEmpty()) return null
        val batch = pending.toList()
        pending.clear()
        return mapOf("userId" to userId, "commands" to batch)
    }
}

// ── Hydration (the server verdict is the truth) ─────────────────────────

/** The watermark rule: adopt a hydrated board when forced, or when the sequence moved. */
fun shouldAdoptBoard(force: Boolean, localSequence: Long?, incomingSequence: Long?): Boolean =
    force || localSequence == null || incomingSequence == null || incomingSequence != localSequence

/** JS `Number(x).toFixed(2)`. */
fun jsToFixed2(value: Double): String {
    val scaled = kotlin.math.floor(kotlin.math.abs(value) * 100 + 0.5) / 100
    val sign = if (value < 0) "-" else ""
    val whole = scaled.toLong()
    val frac = ((scaled - whole) * 100 + 0.5).toLong().coerceIn(0, 99)
    return "$sign$whole.${frac.toString().padStart(2, '0')}"
}

/**
 * `hydrateBoard`'s mapping half: `/api/board` JSON → board state. Null when the body lacks the
 * columns/items shape (the seed/local board stands until the daemon answers).
 */
fun forgeBoardFromApi(body: Map<String, Any?>): ForgeBoard? {
    val columns = body["columns"]?.asMaps() ?: return null
    val items = body["items"]?.asMaps() ?: return null
    if (body["columns"] !is List<*> && body["columns"] !is Array<*>) return null
    if (body["items"] !is List<*> && body["items"] !is Array<*>) return null
    return ForgeBoard(
        columns = columns.sortedBy { it["order"].asInt() }.map { c ->
            ForgeBoardColumn(
                id = c["id"].asStr(),
                name = c["name"].asStr(),
                wipLimit = (c["wipLimit"] as? Number)?.toInt(),
                count = (c["count"] as? Number)?.toInt(),
            )
        }.toMutableList(),
        cards = items.map { it ->
            val contested = it["contested"].asBool()
            val attention = (it["attention"] as? Number)?.toDouble()
            val meta = listOfNotNull(
                if (contested) "⚡ contested" else null,
                attention?.let { a -> "attn " + jsToFixed2(a) },
            ).joinToString("  ·  ")
            ForgeBoardCard(
                id = it["id"].asStr(),
                title = it["title"].asStrOrNull() ?: it["id"].asStr(),
                column = it["status"].asStr(),
                revision = (it["revision"] as? Number)?.toInt(),
                meta = meta,
            )
        }.toMutableList(),
    )
}

/** The sequence carried by a hydrate response or an invoke verdict. */
fun boardSequenceOf(body: Map<String, Any?>): Long? = (body["sequence"] as? Number)?.toLong()

// ── Sync note text ──────────────────────────────────────────────────────

fun syncNoteQueued(batchSize: Int): String = "Offline — $batchSize queued for sync"

fun syncNoteSynced(flushedCount: Int, rejected: Int, sequence: Long?): String =
    "Synced $flushedCount" +
        (if (rejected > 0) "  ·  $rejected rejected" else "") +
        (sequence?.let { "  ·  seq $it" } ?: "")

const val SYNC_NOTE_UNREACHABLE = "Local only — reactor unreachable"
const val SYNC_NOTE_ONLINE = "Back online"
const val SYNC_NOTE_OFFLINE = "Offline — edits stay local"

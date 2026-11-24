package borg.trikeshed.transfer

import borg.trikeshed.lib.*
import kotlin.time.TimeSource

/** The protocol a transfer, or one of its sources, speaks; its wire text is the lowercase name. */
enum class TransferProtocol { BITTORRENT, IPFS, HTTP }

/** Where a transfer, or one of its sources, stands; its wire text is the lowercase name. */
enum class TransferState { WAITING, ACTIVE, PAUSED, COMPLETE, ERROR }

/** What a source is reached over; its wire text is the lowercase name. */
enum class TransferTransport { TCP, UTP, UDP, TLS, YAMUX }

/** How many pieces a transfer has, how many are held verified, and the bytes of each piece but the last. */
data class TransferPieces(val count: Int, val have: Int, val length: Int)

/** What asked for a transfer: an LCNC run, a node, a curated book, a stored document. */
data class TransferContext(val kind: String, val id: String)

/** A peer, mirror or web seed that feeds a transfer or is fed by it: bytes a second now, and the verified bytes it gave. */
data class TransferSource(
    val address: String,
    val protocol: TransferProtocol,
    val transport: TransferTransport,
    val state: TransferState,
    val down: Long,
    val up: Long,
    val completed: Long,
)

/**
 * One transfer as every transport reports it and the transfers panel reads it. [total] is null while unknown,
 * [completed] counts verified bytes, [down] and [up] are bytes a second now, [content] is the content id the
 * transfer lands under in the store, [class] the SUMO class its content is typed by, [error] the message of an
 * [TransferState.ERROR].
 */
data class Transfer(
    val id: String,
    val name: String,
    val protocol: TransferProtocol,
    val state: TransferState,
    val total: Long?,
    val completed: Long,
    val down: Long,
    val up: Long,
    val pieces: TransferPieces?,
    val sources: Series<TransferSource>,
    val context: TransferContext?,
    val content: String?,
    val `class`: String?,
    val error: String?,
)

/** The row on the wire: the contract's field names, its lowercase values, and null for a field with no value. */
val Transfer.wire: Map<String, Any?>
    get() = _m["id" j id, "name" j name, "protocol" j protocol.name.lowercase(), "state" j state.name.lowercase(),
        "total" j total, "completed" j completed, "down" j down, "up" j up,
        "pieces" j pieces?.let { _m["count" j it.count, "have" j it.have, "length" j it.length] },
        "sources" j (sources α { it.wire }).toList(),
        "context" j context?.let { _m["kind" j it.kind, "id" j it.id] },
        "content" j content, "class" j `class`, "error" j error]

val TransferSource.wire: Map<String, Any?>
    get() = _m["address" j address, "protocol" j protocol.name.lowercase(), "transport" j transport.name.lowercase(),
        "state" j state.name.lowercase(), "down" j down, "up" j up, "completed" j completed]

/**
 * Bytes a second over the last [window] seconds of a monotonic clock, the current second included. It holds no
 * lock: its owner serializes [add] and [now].
 */
class TransferRate(val window: Int = 4) {
    val clock = TimeSource.Monotonic.markNow()
    val seconds = LongArray(window)
    var second = 0L

    fun add(bytes: Long) { roll(); seconds[(second % window).toInt()] += bytes }

    fun now(): Long { roll(); return seconds.sum() / window }

    fun roll() {
        val at = clock.elapsedNow().inWholeSeconds
        if (at - second >= window) seconds.fill(0) else for (s in second + 1..at) seconds[(s % window).toInt()] = 0
        second = at
    }
}

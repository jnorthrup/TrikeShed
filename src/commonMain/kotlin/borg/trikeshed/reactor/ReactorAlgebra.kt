package borg.trikeshed.reactor

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/** Reactor-flavoured projection of the litebike Protocol enum (subset only).
 *  Numeric IDs match the litebike table so byte-level tests can compare. */
enum class Protocol(val id: UByte) {
    Http(1u), Socks5(2u), Tls(3u), Dns(4u), Json(5u), Http2(6u), WebSocket(7u);
    companion object {
        fun fromId(id: UByte): Protocol? = entries.firstOrNull { it.id == id }
    }
}

enum class HttpMethod { Get, Head, Post, Put, Delete, Connect, Options, Patch, Trace }
enum class Socks5Command { Connect, Bind, UdpAssociate }

/** Wire-bound per-protocol verb. Identity must be the unit element under
 *  composition: applying it to a `WamBlock` returns the input unchanged. */
sealed class TransformCode {
    data class HttpTransform(val method: HttpMethod) : TransformCode()
    data class Socks5Transform(val cmd: Socks5Command) : TransformCode()
    object Identity : TransformCode()
    companion object { val identity: TransformCode = Identity }
}

/** Forward-only connection lifecycle. */
enum class ConnectionState { Idle, Active, Parsing, Forwarding, Closed }

/** Reactor session state — Join-shaped so it composes with the rest of
 *  the TrikeShed Join/Series algebra. */
data class SessionState(
    val protocolData: Series<Byte>,
    val connectionState: ConnectionState,
    val parsingPosition: Int = 0,
    val continuationPoint: Long? = null,
    val protocolSpec: UByte,
) {
    companion object {
        fun new(spec: UByte): SessionState = SessionState(0 j { error("empty") }, ConnectionState.Idle, 0, null, spec)
    }
}

/** WamBlock = Join<SessionState, TransformCode>. Compose with the existing
 *  `borg.trikeshed.lib.Join` algebra — no new types. */
typealias WamElement = SessionState
typealias WamKey = TransformCode
typealias WamBlock = Join<WamElement, WamKey>

/** Pure projection of a TransformCode against a WamBlock.
 *  Identity must be the unit: `project(block, Identity) == block`. */
fun project(block: WamBlock, code: TransformCode): WamBlock {
    if (code is TransformCode.Identity) return block

    val closedSession = block.a.copy(connectionState = ConnectionState.Closed)
    return closedSession j code
}

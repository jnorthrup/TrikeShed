@file:Suppress("UNCHECKED_CAST", "FunctionName")

package borg.trikeshed.litebike.taxonomy

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Clean-room Kotlin re-implementation of litebike's taxonomy concepts.
 *
 * This is NOT an FFI port — there is no Rust linkage, no shared wire
 * format, no `protocol_to_spec_id` call across a boundary. Litebike
 * (Rust) is the conceptual source; the IDs below are TrikeShed-local
 * conventions chosen for internal consistency.
 *
 * Concepts borrowed from litebike:
 *   - Protocol enum with single-byte IDs driving channel dispatch.
 *   - RFC-bound marker metadata per protocol.
 *   - WamBlock / WamElement / WamKey session-transform algebra.
 *   - Discrete-sequence continuation tracking.
 *
 * Numeric IDs are TrikeShed-local:
 *
 *   Http=1, Socks5=2, Tls=3, Dns=4, Json=5, Http2=6, WebSocket=7,
 *   Bonjour=8, Upnp=9.
 *
 * They match litebike's `taxonomy.rs` for 1-7 only because we copied
 * the conceptual table; there is no runtime compatibility requirement.
 */

// ── Protocol enum + RFC markers ─────────────────────────────────────────

/**
 * Single-byte protocol identifier. The integer value is wire-stable
 * across the Rust ↔ Kotlin boundary; do not renumber.
 */
enum class Protocol(val id: UByte) {
    Http(1u), Socks5(2u), Tls(3u), Dns(4u), Json(5u), Http2(6u), WebSocket(7u),
    Bonjour(8u), Upnp(9u);

    companion object {
        fun fromId(id: UByte): Protocol? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Marker objects with RFC bindings — the Kotlin analogue of the
 * `pub type HttpSpec = ProtocolSpec<{ Protocol::Http as u8 }>` family.
 *
 * Each marker carries its protocol ID and an RFC reference; they are
 * not const-generic because Kotlin lacks them, but their `id` payload
 * is byte-compatible.
 */
sealed class ProtocolMark(val protocolId: UByte, val specName: String, val rfcReference: String) {
    object HttpMark      : ProtocolMark(Protocol.Http.id,      "HTTP/1.1",   "RFC 7230-7237")
    object Socks5Mark    : ProtocolMark(Protocol.Socks5.id,    "SOCKS5",     "RFC 1928")
    object TlsMark       : ProtocolMark(Protocol.Tls.id,       "TLS 1.3",    "RFC 8446")
    object DnsMark       : ProtocolMark(Protocol.Dns.id,       "DNS",        "RFC 1035")
    object JsonMark      : ProtocolMark(Protocol.Json.id,      "JSON",       "RFC 8259")  // lit uses RFC 7159 historically; current is 8259
    object Http2Mark     : ProtocolMark(Protocol.Http2.id,     "HTTP/2",     "RFC 7540")
    object WebSocketMark : ProtocolMark(Protocol.WebSocket.id, "WebSocket",  "RFC 6455")
    object BonjourMark   : ProtocolMark(Protocol.Bonjour.id,   "mDNS/DNS-SD","RFC 6762/6763")
    object UpnpMark      : ProtocolMark(Protocol.Upnp.id,      "UPnP/SSDP",  "UPnP DA 1.1")

    companion object {
        fun forProtocol(p: Protocol): ProtocolMark = when (p) {
            Protocol.Http      -> HttpMark
            Protocol.Socks5    -> Socks5Mark
            Protocol.Tls       -> TlsMark
            Protocol.Dns       -> DnsMark
            Protocol.Json      -> JsonMark
            Protocol.Http2     -> Http2Mark
            Protocol.WebSocket -> WebSocketMark
            Protocol.Bonjour   -> BonjourMark
            Protocol.Upnp      -> UpnpMark
        }
    }
}

// ── Session / connection state ─────────────────────────────────────────

/** Forward-only connection lifecycle. */
enum class ConnectionState { Idle, Active, Parsing, Forwarding, Closed }

/**
 * Protocol-agnostic session payload. The Rust side stores `Vec<u8>` for
 * `protocol_data`; the Kotlin side uses `Series<Byte>` so it can be a
 * lazy cursor instead of a materialised buffer.
 */
data class SessionState(
    val protocolData: Series<Byte>,
    val connectionState: ConnectionState,
    val parsingPosition: Int,
    val continuationPoint: Long?,        // SequenceId is usize in Rust; Long here
    val protocolSpec: UByte,
) {
    companion object {
        fun new(protocolSpec: UByte): SessionState = SessionState(
            protocolData = emptyByteSeries(),
            connectionState = ConnectionState.Idle,
            parsingPosition = 0,
            continuationPoint = null,
            protocolSpec = protocolSpec,
        )

        fun withData(protocolSpec: UByte, data: ByteArray): SessionState = SessionState(
            protocolData = data.size j { data[it] },
            connectionState = ConnectionState.Active,
            parsingPosition = 0,
            continuationPoint = null,
            protocolSpec = protocolSpec,
        )
    }
}

private fun emptyByteSeries(): Series<Byte> = 0 j { error("empty") }

// ── Transform codes (per-protocol verbs) ───────────────────────────────

/** Subset of per-protocol verbs the Rust side enumerates; we collapse
 *  them to tags. Add concrete values when a worker actually needs them. */
enum class HttpMethod { Get, Head, Post, Put, Delete, Connect, Options, Patch, Trace }
enum class Socks5Command { Connect, Bind, UdpAssociate }
enum class TlsVersion { Tls10, Tls11, Tls12, Tls13 }
enum class DnsOpCode { Query, Iquery, Status, Notify, Update }
enum class JsonType { Object, Array, String, Number, Boolean, Null }
enum class Http2FrameType { Data, Headers, Priority, RstStream, Settings, PushPromise, Ping, GoAway, WindowUpdate, Continuation }
enum class WebSocketOpCode { Continuation, Text, Binary, Close, Ping, Pong }

/**
 * Pure transform — same shape as Rust `TransformCode`. Tagged union over
 * protocol verbs; the `apply(state)` projection is pure and idempotent.
 */
sealed class TransformCode {
    data class HttpTransform(val method: HttpMethod) : TransformCode()
    data class Socks5Transform(val cmd: Socks5Command) : TransformCode()
    data class TlsTransform(val version: TlsVersion) : TransformCode()
    data class DnsTransform(val opCode: DnsOpCode) : TransformCode()
    data class JsonTransform(val jsonType: JsonType) : TransformCode()
    data class Http2Transform(val frameType: Http2FrameType) : TransformCode()
    data class WebSocketTransform(val opCode: WebSocketOpCode) : TransformCode()
    object Identity : TransformCode()
}

// ── WAM block / element / key (algebra over session × transform) ──────

/**
 * Discrete-sequence element. The Rust `WamBlock<WamElement, WamKey>` is a
 * 2-tuple of (state, transform); on the Kotlin side we represent it as
 * a `Join` so it composes with the rest of the TrikeShed `Join` algebra.
 */
typealias WamElement = SessionState
typealias WamKey = TransformCode
typealias WamBlock = Join<WamElement, WamKey>

/** Sequence identifier — Long rather than usize for cross-platform stability. */
typealias SequenceId = Long

/**
 * Continuation point — same name as Rust; the `nextId` is the
 * SequenceId this block hands off to. Null = terminal block.
 */
data class DiscreteSequence<E, K>(
    val sequenceId: SequenceId,
    val element: E,
    val key: K,
    val nextSequenceId: SequenceId? = null,
)

// ── Protocol priority (rust ChannelizedReactor::with_config) ───────────

/**
 * The Rust side's `ReactorConfig::default` assigns per-protocol
 * priorities; we expose them as a `Series<Int>` so the consumer can
 * sort protocols by priority without re-implementing the table.
 */
data class ProtocolPriority(val protocol: Protocol, val priority: Int)

fun defaultProtocolPriorities(): Series<ProtocolPriority> {
    val list = listOf(
        ProtocolPriority(Protocol.Http,      1),
        ProtocolPriority(Protocol.Socks5,    1),
        ProtocolPriority(Protocol.Tls,       2),
        ProtocolPriority(Protocol.Dns,       3),
        ProtocolPriority(Protocol.Json,      4),
        ProtocolPriority(Protocol.Http2,     1),
        ProtocolPriority(Protocol.WebSocket, 2),
    )
    return list.size j { i -> list[i] }
}

// ── Wire-stable helper ────────────────────────────────────────────────

/** Convert an `HttpMethod` to its HTTP/1.1 wire token. */
fun HttpMethod.wireToken(): String = when (this) {
    HttpMethod.Get -> "GET"
    HttpMethod.Head -> "HEAD"
    HttpMethod.Post -> "POST"
    HttpMethod.Put -> "PUT"
    HttpMethod.Delete -> "DELETE"
    HttpMethod.Connect -> "CONNECT"
    HttpMethod.Options -> "OPTIONS"
    HttpMethod.Patch -> "PATCH"
    HttpMethod.Trace -> "TRACE"
}
